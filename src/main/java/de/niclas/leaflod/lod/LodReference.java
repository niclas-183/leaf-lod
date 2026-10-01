package de.niclas.leaflod.lod;

import net.minecraft.core.BlockPos;

/**
 * Holds the current reference camera positions as an immutable snapshot in a {@code volatile} field.
 *
 * <p>Chunk meshes are built on worker threads; they read one consistent snapshot per block via
 * {@link #current()}. Only the client (render) thread replaces the snapshot.
 *
 * <p>Leaves and grass have independent references (position + radius), so that the grass radius (small, frequent
 * updates) does not force re-meshing the much larger leaf boundary and vice versa.
 */
public final class LodReference {
	/**
	 * Reference for one kind of block.
	 *
	 * @param has    a reference position exists; while {@code false} (e.g. right after joining a world) every block
	 *               of this kind counts as far
	 * @param x      reference position (world coordinates)
	 * @param radius full-model distance in blocks
	 */
	public record Ref(boolean has, double x, double y, double z, double radius, double radiusSq) {
		static Ref none(double radius) {
			return new Ref(false, 0, 0, 0, radius, radius * radius);
		}

		static Ref at(double x, double y, double z, double radius) {
			return new Ref(true, x, y, z, radius, radius * radius);
		}

		Ref withRadius(double radius) {
			return new Ref(this.has, this.x, this.y, this.z, radius, radius * radius);
		}

		/** Whether the block at (bx, by, bz) is within the radius. Distance is measured to the block centre. */
		public boolean isNear(int bx, int by, int bz) {
			if (!this.has) {
				return false;
			}

			double dx = bx + 0.5 - this.x;
			double dy = by + 0.5 - this.y;
			double dz = bz + 0.5 - this.z;
			return dx * dx + dy * dy + dz * dz <= this.radiusSq;
		}
	}

	/**
	 * @param active          mod enabled; if {@code false} every block gets its full model
	 * @param leaves          leaf reference
	 * @param outerLeavesOnly near leaves keep the bush geometry only if they are exposed
	 * @param clipUnderSnow   near leaves with snow (or another thin cover) on top get their bush geometry clipped at the top
	 * @param grassActive     grass/fern LOD enabled; if {@code false} grass always gets its full model
	 * @param grass           grass reference
	 * @param cone            zoom view cone that extends the full-model range while zoomed ({@link Cone#NONE} if not zoomed)
	 */
	public record Snapshot(boolean active, Ref leaves, boolean outerLeavesOnly, boolean clipUnderSnow, boolean grassActive, Ref grass, Cone cone) {
		/** Whether the full (bushy) leaf model applies at {@code pos} (ignoring the exposure test). */
		public boolean isLeafNear(BlockPos pos) {
			return this.leaves.isNear(pos.getX(), pos.getY(), pos.getZ())
					|| this.cone.contains(pos.getX(), pos.getY(), pos.getZ(), this.cone.leafRadiusSq());
		}

		/** Whether the full grass model applies for a block whose distance is measured at (bx, by, bz). */
		public boolean isGrassNear(int bx, int by, int bz) {
			return this.grass.isNear(bx, by, bz) || this.cone.contains(bx, by, bz, this.cone.grassRadiusSq());
		}

		Snapshot withCone(Cone newCone) {
			return new Snapshot(this.active, this.leaves, this.outerLeavesOnly, this.clipUnderSnow, this.grassActive, this.grass, newCone);
		}

		/** Convenience for diagnostics/tests: current leaf radius. */
		public double radius() {
			return this.leaves.radius();
		}

		/** Convenience for diagnostics/tests: whether a leaf reference exists. */
		public boolean hasReference() {
			return this.leaves.has();
		}

		static Snapshot inactive(double leafRadius, double grassRadius) {
			return new Snapshot(false, Ref.none(leafRadius), false, false, false, Ref.none(grassRadius), Cone.NONE);
		}
	}

	/**
	 * View cone used while zooming: blocks inside the cone (apex at the camera, axis = view direction) and within the
	 * kind's extended radius get the full model, in addition to the normal sphere.
	 *
	 * @param x            apex (camera position)
	 * @param ax           axis (unit view direction)
	 * @param cosHalf      cosine of the half-angle
	 * @param sinHalf      sine of the half-angle
	 * @param leafRadius   extended leaf radius (0 = cone does not affect leaves)
	 * @param grassRadius  extended grass radius (0 = cone does not affect grass)
	 * @param zoomFactor   magnification the cone was built for (diagnostics)
	 */
	public record Cone(boolean active, double x, double y, double z, double ax, double ay, double az,
					   double cosHalf, double sinHalf, double leafRadius, double leafRadiusSq,
					   double grassRadius, double grassRadiusSq, double zoomFactor) {
		public static final Cone NONE = new Cone(false, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 1);

		static Cone of(double x, double y, double z, double ax, double ay, double az, double halfAngle,
					   double leafRadius, double grassRadius, double zoomFactor) {
			return new Cone(true, x, y, z, ax, ay, az, Math.cos(halfAngle), Math.sin(halfAngle),
					leafRadius, leafRadius * leafRadius, grassRadius, grassRadius * grassRadius, zoomFactor);
		}

		/** Whether the centre of block (bx, by, bz) lies inside the cone and within {@code radiusSq}. Allocation-free. */
		public boolean contains(int bx, int by, int bz, double radiusSq) {
			if (!this.active || radiusSq <= 0.0) {
				return false;
			}

			double vx = bx + 0.5 - this.x;
			double vy = by + 0.5 - this.y;
			double vz = bz + 0.5 - this.z;
			double distSq = vx * vx + vy * vy + vz * vz;

			if (distSq > radiusSq) {
				return false;
			}

			double along = vx * this.ax + vy * this.ay + vz * this.az;
			return along > 0.0 && along * along >= this.cosHalf * this.cosHalf * distSq;
		}

		/** Half-angle in degrees (diagnostics). */
		public double halfAngleDegrees() {
			return Math.toDegrees(Math.acos(this.cosHalf));
		}
	}

	private static volatile Snapshot current = Snapshot.inactive(32, 24);

	private LodReference() {
	}

	public static Snapshot current() {
		return current;
	}

	static void set(Snapshot snapshot) {
		current = snapshot;
	}
}
