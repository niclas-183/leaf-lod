package de.niclas.leaflod.model;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadTransform;

/**
 * Clips quads at the top of the block (y = 1): used for leaves with snow (or another thin cover) on top, so the bush
 * planes don't poke up through the cover. Parts below y = 1 (sides, bottom) are kept unchanged.
 *
 * <p>Polygon clipping (Sutherland-Hodgman) against the plane y = 1: every vertex above is replaced by the point where its
 * quad edge crosses the plane, with position, UV, colour and lightmap interpolated along that edge - exact for any UV
 * orientation/rotation, since attributes vary linearly along an edge. Results:
 * <ul>
 *     <li>nothing above: unchanged (fast path, covers all cube faces);</li>
 *     <li>2 vertices above (the usual case for vertical bush planes): a smaller quad;</li>
 *     <li>3 above: a triangle (emitted as a quad with a repeated vertex);</li>
 *     <li>1 above: a pentagon, approximated by a quad (the two crossing points merged into their midpoint on the clip
 *     line - nothing pokes above, only a sliver at the clip line is lost);</li>
 *     <li>all above, or degenerate result: quad dropped.</li>
 * </ul>
 * Stateless apart from a per-thread scratch buffer; no allocation per quad.
 */
public final class TopClipFilter implements QuadTransform {
	public static final TopClipFilter INSTANCE = new TopClipFilter();

	private static final float TOP = 1.0f;
	private static final float EPS = 1.0e-4f;
	/** Attributes per polygon vertex: x, y, z, u, v (floats) and colour, lightmap (ints, stored as raw bits). */
	private static final int STRIDE = 7;
	private static final int MAX_VERTICES = 6;
	private static final ThreadLocal<float[][]> SCRATCH = ThreadLocal.withInitial(() -> new float[][] {
			new float[4 * STRIDE], new float[MAX_VERTICES * STRIDE]});

	private TopClipFilter() {
	}

	@Override
	public boolean transform(MutableQuadView quad) {
		int above = 0;

		for (int i = 0; i < 4; i++) {
			if (quad.y(i) > TOP + EPS) {
				above++;
			}
		}

		if (above == 0) {
			return true;
		}

		if (above == 4) {
			return false;
		}

		float[][] scratch = SCRATCH.get();
		float[] in = scratch[0];
		float[] out = scratch[1];

		for (int i = 0; i < 4; i++) {
			int o = i * STRIDE;
			in[o] = quad.x(i);
			in[o + 1] = quad.y(i);
			in[o + 2] = quad.z(i);
			in[o + 3] = quad.u(i);
			in[o + 4] = quad.v(i);
			in[o + 5] = Float.intBitsToFloat(quad.color(i));
			in[o + 6] = Float.intBitsToFloat(quad.lightmap(i));
		}

		int count = 0;

		for (int i = 0; i < 4; i++) {
			int c = i * STRIDE;
			int n = ((i + 1) & 3) * STRIDE;
			boolean cInside = in[c + 1] <= TOP + EPS;
			boolean nInside = in[n + 1] <= TOP + EPS;

			if (cInside) {
				System.arraycopy(in, c, out, count * STRIDE, STRIDE);
				count++;
			}

			if (cInside != nInside) {
				float t = (TOP - in[c + 1]) / (in[n + 1] - in[c + 1]);
				int o = count * STRIDE;
				out[o] = in[c] + t * (in[n] - in[c]);
				out[o + 1] = TOP;
				out[o + 2] = in[c + 2] + t * (in[n + 2] - in[c + 2]);
				out[o + 3] = in[c + 3] + t * (in[n + 3] - in[c + 3]);
				out[o + 4] = in[c + 4] + t * (in[n + 4] - in[c + 4]);
				// Colour/lightmap: taken from the vertex that stays (model quads have uniform values anyway).
				int keep = cInside ? c : n;
				out[o + 5] = in[keep + 5];
				out[o + 6] = in[keep + 6];
				count++;
			}
		}

		if (count == 5) {
			// One vertex was above: merge the two crossing points (consecutive, on the clip line) into their midpoint.
			int first = -1;

			for (int i = 0; i < 5; i++) {
				int j = (i + 1) % 5;

				if (Math.abs(out[i * STRIDE + 1] - TOP) <= EPS && Math.abs(out[j * STRIDE + 1] - TOP) <= EPS
						&& isCrossing(out, i, in) && isCrossing(out, j, in)) {
					first = i;
					break;
				}
			}

			if (first < 0) {
				return false;
			}

			int second = (first + 1) % 5;
			int a = first * STRIDE;
			int b = second * STRIDE;

			for (int k = 0; k < 5; k++) {
				out[a + k] = (out[a + k] + out[b + k]) * 0.5f;
			}

			// Remove the second crossing point.
			System.arraycopy(out, (second + 1) * STRIDE, out, second * STRIDE, (4 - second) * STRIDE);
			count = 4;
		} else if (count == 3) {
			System.arraycopy(out, 2 * STRIDE, out, 3 * STRIDE, STRIDE);
			count = 4;
		}

		if (count != 4 || isDegenerate(out)) {
			return false;
		}

		for (int i = 0; i < 4; i++) {
			int o = i * STRIDE;
			quad.pos(i, out[o], out[o + 1], out[o + 2]);
			quad.uv(i, out[o + 3], out[o + 4]);
			quad.color(i, Float.floatToRawIntBits(out[o + 5]));
			quad.lightmap(i, Float.floatToRawIntBits(out[o + 6]));
		}

		return true;
	}

	/** Whether output vertex {@code index} is a newly created crossing point (not one of the original vertices). */
	private static boolean isCrossing(float[] out, int index, float[] in) {
		int o = index * STRIDE;

		for (int i = 0; i < 4; i++) {
			int s = i * STRIDE;

			if (in[s] == out[o] && in[s + 1] == out[o + 1] && in[s + 2] == out[o + 2]) {
				return false;
			}
		}

		return true;
	}

	/** Area check via the cross product of the diagonals (also catches the repeated-vertex triangle only if it collapsed). */
	private static boolean isDegenerate(float[] q) {
		float ax = q[2 * STRIDE] - q[0], ay = q[2 * STRIDE + 1] - q[1], az = q[2 * STRIDE + 2] - q[2];
		float bx = q[3 * STRIDE] - q[STRIDE], by = q[3 * STRIDE + 1] - q[STRIDE + 1], bz = q[3 * STRIDE + 2] - q[STRIDE + 2];
		float cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
		return cx * cx + cy * cy + cz * cz < 1.0e-10f;
	}
}
