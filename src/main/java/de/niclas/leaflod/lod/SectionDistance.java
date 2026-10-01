package de.niclas.leaflod.lod;

/**
 * Squared distances from a point to the nearest / farthest block centre of a 16x16x16 chunk section
 * (block centres span {@code s*16+0.5 .. s*16+15.5} on each axis). Same metric as {@link LodReference.Snapshot#isNear}.
 */
final class SectionDistance {
	private SectionDistance() {
	}

	static double minSq(double px, double py, double pz, int sx, int sy, int sz) {
		return minSq(px, py, pz, sx, sy, sz, 0);
	}

	static double maxSq(double px, double py, double pz, int sx, int sy, int sz) {
		return maxSq(px, py, pz, sx, sy, sz, 0);
	}

	/**
	 * @param extendDown extra blocks below the section whose centres are included (upper halves of double plants
	 *                   are classified by the block below them)
	 */
	static double minSq(double px, double py, double pz, int sx, int sy, int sz, int extendDown) {
		return sq(nearestOnAxis(px, sx, 0)) + sq(nearestOnAxis(py, sy, extendDown)) + sq(nearestOnAxis(pz, sz, 0));
	}

	static double maxSq(double px, double py, double pz, int sx, int sy, int sz, int extendDown) {
		return sq(farthestOnAxis(px, sx, 0)) + sq(farthestOnAxis(py, sy, extendDown)) + sq(farthestOnAxis(pz, sz, 0));
	}

	private static double nearestOnAxis(double v, int s, int extendDown) {
		double min = s * 16 + 0.5 - extendDown;
		double max = s * 16 + 15.5;
		return v < min ? min - v : (v > max ? v - max : 0.0);
	}

	private static double farthestOnAxis(double v, int s, int extendDown) {
		double min = s * 16 + 0.5 - extendDown;
		double max = s * 16 + 15.5;
		return Math.max(Math.abs(v - min), Math.abs(v - max));
	}

	private static double sq(double v) {
		return v * v;
	}
}
