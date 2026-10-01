package de.niclas.leaflod.model;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadTransform;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.joml.Vector3fc;

/**
 * Geometric test that decides whether a quad is one of the faces of the plain unit cube
 * (axis-aligned and lying on the boundary of the 0..1 block space).
 *
 * <p>Used as a {@link QuadTransform} for far-away leaves: returning {@code false} drops the quad.
 * Allocation-free; only reads the 12 vertex coordinates of the quad.
 */
public final class CubeFaceFilter implements QuadTransform {
	public static final CubeFaceFilter INSTANCE = new CubeFaceFilter();

	/**
	 * Tolerance in block units (0.01 = 0.16 model pixels). Tolerates tiny inflations some packs use
	 * against z-fighting while rejecting everything that really sticks out of the block.
	 */
	private static final float EPS = 0.01f;

	private CubeFaceFilter() {
	}

	@Override
	public boolean transform(MutableQuadView quad) {
		return isCubeFace(
				quad.x(0), quad.y(0), quad.z(0),
				quad.x(1), quad.y(1), quad.z(1),
				quad.x(2), quad.y(2), quad.z(2),
				quad.x(3), quad.y(3), quad.z(3));
	}

	public static boolean isCubeFace(BakedQuad quad) {
		Vector3fc p0 = quad.position0();
		Vector3fc p1 = quad.position1();
		Vector3fc p2 = quad.position2();
		Vector3fc p3 = quad.position3();
		return isCubeFace(
				p0.x(), p0.y(), p0.z(),
				p1.x(), p1.y(), p1.z(),
				p2.x(), p2.y(), p2.z(),
				p3.x(), p3.y(), p3.z());
	}

	@SuppressWarnings("SuspiciousNameCombination")
	static boolean isCubeFace(float x0, float y0, float z0,
							  float x1, float y1, float z1,
							  float x2, float y2, float z2,
							  float x3, float y3, float z3) {
		// Every vertex must lie inside the unit cube (with tolerance) ...
		if (!inUnit(x0) || !inUnit(x1) || !inUnit(x2) || !inUnit(x3)
				|| !inUnit(y0) || !inUnit(y1) || !inUnit(y2) || !inUnit(y3)
				|| !inUnit(z0) || !inUnit(z1) || !inUnit(z2) || !inUnit(z3)) {
			return false;
		}

		// ... and the quad must be flat on one axis at 0 or 1 (= on the cube boundary).
		return onBoundaryPlane(x0, x1, x2, x3)
				|| onBoundaryPlane(y0, y1, y2, y3)
				|| onBoundaryPlane(z0, z1, z2, z3);
	}

	private static boolean inUnit(float v) {
		return v >= -EPS && v <= 1.0f + EPS;
	}

	private static boolean onBoundaryPlane(float a, float b, float c, float d) {
		if (Math.abs(a) <= EPS) {
			return Math.abs(b) <= EPS && Math.abs(c) <= EPS && Math.abs(d) <= EPS;
		}

		if (Math.abs(a - 1.0f) <= EPS) {
			return Math.abs(b - 1.0f) <= EPS && Math.abs(c - 1.0f) <= EPS && Math.abs(d - 1.0f) <= EPS;
		}

		return false;
	}
}
