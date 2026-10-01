package de.niclas.leaflod.lod;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import de.niclas.leaflod.LeafLodClient;
import de.niclas.leaflod.config.LeafLodConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Runs on the client thread (end of every client tick): moves the reference camera positions (leaves and grass
 * independently) when the camera has travelled far enough, determines the leaf radius (fixed, or from the leaf
 * budget) and re-meshes only the chunk sections whose near/far split changes.
 */
public final class LodController {
	/**
	 * Camera must move at least this many blocks away from a reference before it is updated.
	 * Scales with the radius (radius / 8, clamped to 4..16 blocks): a larger radius has a much larger
	 * boundary shell to re-mesh, while a slightly lagging boundary is less noticeable.
	 */
	static final double MIN_UPDATE_STEP = 4.0;
	static final double MAX_UPDATE_STEP = 16.0;
	/** Minimum time between two reference updates (rate limit for sprinting / elytra / spectator). */
	static final long MIN_INTERVAL_NANOS = 250_000_000L;
	/** Upper bound for the adaptive interval after an update that marked very many sections. */
	static final long MAX_INTERVAL_NANOS = 2_000_000_000L;
	/** Extra delay per section marked in the previous update (keeps the rebuild rate bounded). */
	static final long NANOS_PER_MARKED_SECTION = 1_500_000L;
	/** How often the section tracking sets are pruned. */
	static final long PRUNE_INTERVAL_NANOS = 10_000_000_000L;
	/** BUDGET mode: re-evaluate the radius at most this often while the camera stands still (chunks loading, edits). */
	static final long BUDGET_RECHECK_NANOS = 1_000_000_000L;
	/** BUDGET mode: radius granularity in blocks. */
	static final int RADIUS_SNAP = 4;
	/** BUDGET mode: the radius only changes if the new value differs by at least this much (prevents oscillation). */
	static final int RADIUS_HYSTERESIS = 8;

	/** Zoom is considered active from this magnification on (sprint/fluid FOV changes stay well below). */
	static final double ZOOM_THRESHOLD = 1.5;
	/** Consecutive client ticks the zoom must last before the cone is built (and that count as "stable"). */
	static final int ZOOM_MIN_TICKS = 3;
	/** Relative change of the zoom factor per tick below which the zoom level counts as stable. */
	static final double ZOOM_STABLE_CHANGE = 0.02;
	/** Minimum time between two cone-only updates (panning or changing the zoom). */
	static final long CONE_MIN_INTERVAL_NANOS = 150_000_000L;
	/** Extra half-angle around the visible frame, so small pans do not need an immediate cone update. */
	static final double CONE_MARGIN_DEGREES = 4.0;
	/** The cone is re-aimed once the view direction deviates by more than half of the margin. */
	static final double COS_REAIM = Math.cos(Math.toRadians(CONE_MARGIN_DEGREES / 2.0));
	/** The cone apex follows the camera after it moved this far. */
	static final double CONE_MOVE_BLOCKS = 4.0;
	/** Zoom radii are snapped down to multiples of this, so a smooth zoom animation causes only a few updates. */
	static final int ZOOM_RADIUS_SNAP = 16;
	/** Grass is very numerous and its detail is small: the zoom extends it at most this far. */
	static final double GRASS_ZOOM_CAP = 96.0;
	static final double MAX_CONE_HALF_ANGLE_DEGREES = 80.0;
	/** Bounding-sphere radius of a section's block centres (incl. the 1-block downward extension for grass). */
	static final double SECTION_SPHERE_RADIUS = 13.3;

	private static final int ALL_NEAR = 0;
	private static final int ALL_FAR = 1;
	private static final int MIXED = 2;

	private static long lastUpdateNanos;
	private static long currentIntervalNanos = MIN_INTERVAL_NANOS;
	private static long lastPruneNanos;
	private static long lastSolveNanos;
	private static int lastSolvedVersion = -1;
	private static double lastSolvedRadius = Double.NaN;
	private static ClientLevel lastLevel;
	private static int lastMarkedSections;
	private static int updateCount;
	private static double lastZoomFactor = 1.0;
	private static long lastZoomedNanos;
	private static int zoomTicks;
	private static int stableTicks;
	/** Start the game with {@code -Dleaflod.debug=true} to log every reference update. */
	private static final boolean debugLogging = Boolean.getBoolean("leaflod.debug");

	private LodController() {
	}

	static double updateStep(double radius) {
		return Mth.clamp(radius / 8.0, MIN_UPDATE_STEP, MAX_UPDATE_STEP);
	}

	/** Current leaf full-model radius in blocks (effective radius in BUDGET mode). */
	public static double effectiveRadius() {
		return LodReference.current().leaves().radius();
	}

	/** Current grass full-model radius in blocks. */
	public static double grassRadius() {
		return LodReference.current().grass().radius();
	}

	/** Diagnostics: number of sections marked for re-meshing by the last reference update. */
	public static int lastMarkedSections() {
		return lastMarkedSections;
	}

	/** Diagnostics: number of reference updates since start. */
	public static int updateCount() {
		return updateCount;
	}

	/** Diagnostics: number of chunk sections currently known to contain LOD-wrapped leaves. */
	public static int trackedLeafSections() {
		return SectionTracker.LEAVES.size();
	}

	/** Diagnostics: number of chunk sections currently known to contain LOD-wrapped grass. */
	public static int trackedGrassSections() {
		return SectionTracker.GRASS.size();
	}

	/** Diagnostics: magnification measured in the last client tick (1 = not zoomed). */
	public static double zoomFactor() {
		return lastZoomFactor;
	}

	/** Diagnostics: the current zoom cone ({@link LodReference.Cone#NONE} if not zoomed). */
	public static LodReference.Cone zoomCone() {
		return LodReference.current().cone();
	}

	/** Diagnostics: unsnapped radius from the last budget solve (NaN if none yet). */
	public static double lastSolvedRadius() {
		return lastSolvedRadius;
	}

	/**
	 * Applies the current config. Keeps the reference positions if there are any.
	 *
	 * @param reloadChunks trigger a full chunk re-mesh (like F3+A) so the change becomes visible
	 */
	public static void applyConfig(boolean reloadChunks) {
		LeafLodConfig config = LeafLodConfig.get();
		LodReference.Snapshot old = LodReference.current();
		Minecraft mc = Minecraft.getInstance();
		Camera camera = mc != null && mc.level != null ? mc.gameRenderer.mainCamera() : null;

		if (!config.enabled) {
			LodReference.set(LodReference.Snapshot.inactive(config.lodDistance, config.grassDistance));
		} else if (old.leaves().has() || (camera != null && camera.isInitialized())) {
			// In a world: (re)establish the references right away, so the following full re-mesh is already final.
			Vec3 cam = camera != null && camera.isInitialized() ? camera.position() : new Vec3(old.leaves().x(), old.leaves().y(), old.leaves().z());
			double lx = old.leaves().has() ? old.leaves().x() : cam.x;
			double ly = old.leaves().has() ? old.leaves().y() : cam.y;
			double lz = old.leaves().has() ? old.leaves().z() : cam.z;
			double leafRadius = config.mode == LeafLodConfig.Mode.FIXED
					? config.lodDistance
					: budgetRadius(mc, config, lx, ly, lz, -1);
			LodReference.Ref grass = old.grass().has()
					? LodReference.Ref.at(old.grass().x(), old.grass().y(), old.grass().z(), config.grassDistance)
					: LodReference.Ref.at(cam.x, cam.y, cam.z, config.grassDistance);
			LodReference.set(new LodReference.Snapshot(true, LodReference.Ref.at(lx, ly, lz, leafRadius),
					config.outerLeavesOnly, config.clipUnderSnow, config.grassEnabled, grass, LodReference.Cone.NONE));
			lastLevel = mc.level;
			lastSolvedVersion = LeafCounts.version();
			lastSolveNanos = System.nanoTime();
		} else {
			double leafRadius = config.mode == LeafLodConfig.Mode.FIXED ? config.lodDistance : config.minRadius;
			LodReference.set(new LodReference.Snapshot(true, LodReference.Ref.none(leafRadius),
					config.outerLeavesOnly, config.clipUnderSnow, config.grassEnabled, LodReference.Ref.none(config.grassDistance), LodReference.Cone.NONE));
		}

		if (reloadChunks && mc != null && mc.level != null && mc.levelExtractor != null) {
			mc.levelExtractor.allChanged();
		}
	}

	public static void onEndClientTick(Minecraft mc) {
		LeafCounts.tick(mc);
		LodReference.Snapshot old = LodReference.current();

		if (!old.active()) {
			return;
		}

		ClientLevel level = mc.level;

		if (level == null) {
			if (old.leaves().has() || old.grass().has()) {
				// Left the world: next world starts without references (= everything far until the first update).
				LodReference.set(new LodReference.Snapshot(true, LodReference.Ref.none(old.leaves().radius()),
						old.outerLeavesOnly(), old.clipUnderSnow(), old.grassActive(), LodReference.Ref.none(old.grass().radius()), LodReference.Cone.NONE));
			}

			return;
		}

		Camera camera = mc.gameRenderer.mainCamera();

		if (camera == null || !camera.isInitialized()) {
			return;
		}

		LeafLodConfig config = LeafLodConfig.get();
		boolean budgetMode = config.mode == LeafLodConfig.Mode.BUDGET;
		Vec3 cam = camera.position();
		long now = System.nanoTime();
		boolean newLevel = level != lastLevel;
		boolean movedLeaves = movedFrom(old.leaves(), cam);
		boolean movedGrass = old.grassActive() && movedFrom(old.grass(), cam);
		// While standing still, the budget radius is re-evaluated when leaf counts changed (chunks loaded, trees cut, ...).
		boolean recheckBudget = budgetMode && LeafCounts.version() != lastSolvedVersion && now - lastSolveNanos >= BUDGET_RECHECK_NANOS;
		boolean baseWantsUpdate = movedLeaves || movedGrass || recheckBudget || newLevel;

		// ---- zoom (Zoomify, spyglass, ...): detected from the FOV actually used for this frame's projection
		double factor = zoomFactor(mc, camera);
		boolean factorStable = updateZoomTracking(factor, newLevel);
		// The zoom must persist for a few ticks: filters the FOV ramp-up right after joining a world (the camera's FOV
		// modifier starts at 0) and other one-off FOV dips.
		boolean zoomed = config.zoomEnabled && zoomTicks >= ZOOM_MIN_TICKS;

		if (zoomed) {
			lastZoomedNanos = now;
		}

		boolean inGrace = old.cone().active() && now - lastZoomedNanos < config.zoomGraceMillis * 1_000_000L;
		LodReference.Cone coneCandidate = zoomed
				? keepWhileZoomingOut(old.cone(), buildCone(mc, camera, cam, factor, old.leaves().radius(), old.grass().radius(), old.grassActive(), config), factorStable)
				: (inGrace ? old.cone() : LodReference.Cone.NONE);
		boolean coneWantsUpdate = !sameCone(old.cone(), coneCandidate);

		if (!baseWantsUpdate && !coneWantsUpdate) {
			return;
		}

		if (old.leaves().has() || old.grass().has()) {
			// Cone-only updates (panning / zooming) may come a bit faster than reference moves.
			long required = baseWantsUpdate ? currentIntervalNanos
					: Mth.clamp(lastMarkedSections * NANOS_PER_MARKED_SECTION, CONE_MIN_INTERVAL_NANOS, MAX_INTERVAL_NANOS);

			if (now - lastUpdateNanos < required) {
				return;
			}
		}

		lastLevel = level;

		// ---- leaves
		LodReference.Ref leaves = old.leaves();

		if (baseWantsUpdate && (movedLeaves || recheckBudget || newLevel)) {
			double x = movedLeaves ? cam.x : old.leaves().x();
			double y = movedLeaves ? cam.y : old.leaves().y();
			double z = movedLeaves ? cam.z : old.leaves().z();
			double radius;

			if (budgetMode && newLevel) {
				// Fresh level: most chunks (and their leaf counts) are not loaded yet, so a solve would return the full
				// render distance and then shrink again. Start at the minimum radius; the 1 s recheck grows it as
				// chunks arrive (nearest chunks arrive first, so the solve converges quickly).
				radius = Math.min(config.minRadius, maxRadius(mc));
				lastSolvedVersion = -1;
				lastSolveNanos = now;
			} else if (budgetMode) {
				radius = budgetRadius(mc, config, x, y, z, old.leaves().has() ? (int) old.leaves().radius() : -1);
				lastSolvedVersion = LeafCounts.version();
				lastSolveNanos = now;
			} else {
				radius = config.lodDistance;
			}

			if (movedLeaves || !old.leaves().has() || radius != old.leaves().radius()) {
				leaves = LodReference.Ref.at(x, y, z, radius);
			}
		}

		// ---- grass
		LodReference.Ref grass = movedGrass ? LodReference.Ref.at(cam.x, cam.y, cam.z, old.grass().radius()) : old.grass();

		// ---- cone (rebuilt with the possibly updated base radii)
		LodReference.Cone cone = coneCandidate;

		if (zoomed) {
			LodReference.Cone built = keepWhileZoomingOut(old.cone(),
					buildCone(mc, camera, cam, factor, leaves.radius(), grass.radius(), old.grassActive(), config), factorStable);
			cone = sameCone(old.cone(), built) ? old.cone() : built;
		}

		if (leaves == old.leaves() && grass == old.grass() && cone == old.cone()) {
			return;
		}

		LodReference.Snapshot updated = new LodReference.Snapshot(true, leaves, old.outerLeavesOnly(), old.clipUnderSnow(), old.grassActive(), grass, cone);
		// Publish the new snapshot BEFORE marking sections dirty, so every rebuild sees it.
		LodReference.set(updated);

		LongOpenHashSet dirty = new LongOpenHashSet();
		collectChanged(SectionTracker.LEAVES, old.leaves(), leaves, old.cone(), cone, false, 0, dirty);
		collectChanged(SectionTracker.GRASS, old.grass(), grass, old.cone(), cone, true, 1, dirty);
		markDirty(mc.levelExtractor, dirty);
		int marked = dirty.size();

		lastUpdateNanos = now;
		lastMarkedSections = marked;
		updateCount++;
		currentIntervalNanos = Mth.clamp(marked * NANOS_PER_MARKED_SECTION, MIN_INTERVAL_NANOS, MAX_INTERVAL_NANOS);

		if (debugLogging) {
			LeafLodClient.LOGGER.info("[Leaf LOD] Update: leaves {} ({} -> {}{}), grass {} ({}), zoom cone {}, {} sections re-meshed (tracked leaf/grass sections: {}/{}, loaded leaves: {})",
					leaves == old.leaves() ? "unchanged" : String.format("at (%.1f, %.1f, %.1f)", leaves.x(), leaves.y(), leaves.z()),
					(int) old.leaves().radius(), (int) leaves.radius(),
					budgetMode ? String.format(", budget %d, solved %.1f", config.leafBudget, lastSolvedRadius) : ", fixed",
					grass == old.grass() ? "unchanged" : String.format("at (%.1f, %.1f, %.1f)", grass.x(), grass.y(), grass.z()),
					(int) grass.radius(),
					!cone.active() ? (old.cone().active() ? "removed" : "off")
							: String.format("x%.2f half-angle %.1f deg, leaves %d / grass %d blocks%s", cone.zoomFactor(), cone.halfAngleDegrees(),
							(int) cone.leafRadius(), (int) cone.grassRadius(), cone == old.cone() ? " (unchanged)" : ""),
					marked, SectionTracker.LEAVES.size(), SectionTracker.GRASS.size(), LeafCounts.total());
		}

		if (now - lastPruneNanos > PRUNE_INTERVAL_NANOS) {
			lastPruneNanos = now;
			prune(mc, cam, Math.max(leaves.radius(), grass.radius()));
		}
	}

	private static boolean movedFrom(LodReference.Ref ref, Vec3 cam) {
		if (!ref.has()) {
			return true;
		}

		double dx = cam.x - ref.x();
		double dy = cam.y - ref.y();
		double dz = cam.z - ref.z();
		double step = updateStep(ref.radius());
		return dx * dx + dy * dy + dz * dz >= step * step;
	}

	private static int maxRadius(Minecraft mc) {
		return Math.max(RADIUS_SNAP, mc.options.getEffectiveRenderDistance() * 16);
	}

	/**
	 * BUDGET mode radius: the distance containing the nearest {@code leafBudget} leaf blocks, snapped down to
	 * {@link #RADIUS_SNAP}, clamped to [minRadius, render distance] and with hysteresis against {@code current}.
	 *
	 * @param current current effective radius, or -1 to skip the hysteresis
	 */
	private static double budgetRadius(Minecraft mc, LeafLodConfig config, double x, double y, double z, int current) {
		int maxRadius = maxRadius(mc);
		int minRadius = Math.min(config.minRadius, maxRadius);
		double solved = LeafCounts.solveRadius(x, y, z, config.leafBudget, maxRadius);
		lastSolvedRadius = solved;

		int candidate = Mth.clamp(Mth.floor(solved / RADIUS_SNAP) * RADIUS_SNAP, minRadius, maxRadius);

		if (current <= 0 || candidate == current) {
			return candidate;
		}

		if (Math.abs(candidate - current) >= RADIUS_HYSTERESIS || candidate == minRadius || candidate == maxRadius) {
			return candidate;
		}

		// Small change: keep the current radius, but never outside the (possibly changed) bounds.
		return Mth.clamp(current, minRadius, maxRadius);
	}

	/**
	 * Adds every tracked section whose near/far result can differ between the old and the new state of one block kind
	 * (sphere reference + zoom cone). Sections whose sphere and cone classification is unchanged keep their mesh.
	 */
	private static void collectChanged(SectionTracker tracker, LodReference.Ref oldRef, LodReference.Ref newRef,
									   LodReference.Cone oldCone, LodReference.Cone newCone, boolean grass, int extendDown, LongOpenHashSet out) {
		boolean refsDiffer = !oldRef.equals(newRef);
		boolean conesDiffer = !sameConePart(oldCone, newCone, grass);

		if (!refsDiffer && !conesDiffer) {
			return;
		}

		ChangeTest test = new ChangeTest(oldRef, newRef, oldCone, newCone, grass, extendDown, refsDiffer, conesDiffer);
		int[] newBox = newRef.has() ? box(newRef) : null;
		int[] oldBox = oldRef.has() ? box(oldRef) : null;
		long boxVolume = (newBox != null ? volume(newBox) : 0) + (oldBox != null ? volume(oldBox) : 0);

		if (conesDiffer || tracker.size() < boxVolume) {
			// Cone involved (can reach far) or few tracked sections: check each tracked section directly.
			tracker.forEach(key -> {
				if (test.needsRebuild(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key))) {
					out.add(key);
				}
			});
			return;
		}

		if (newBox != null) {
			collectBox(tracker, newBox, null, test, out);
		}

		if (oldBox != null) {
			collectBox(tracker, oldBox, newBox, test, out);
		}
	}

	private static void collectBox(SectionTracker tracker, int[] box, int[] skipBox, ChangeTest test, LongOpenHashSet out) {
		for (int sx = box[0]; sx <= box[3]; sx++) {
			for (int sz = box[2]; sz <= box[5]; sz++) {
				for (int sy = box[1]; sy <= box[4]; sy++) {
					if (skipBox != null && inBox(skipBox, sx, sy, sz)) {
						continue;
					}

					if (tracker.contains(sx, sy, sz) && test.needsRebuild(sx, sy, sz)) {
						out.add(SectionPos.asLong(sx, sy, sz));
					}
				}
			}
		}
	}

	private static void markDirty(LevelExtractor extractor, LongOpenHashSet sections) {
		if (extractor == null) {
			return;
		}

		var it = sections.iterator();

		while (it.hasNext()) {
			long key = it.nextLong();
			extractor.setSectionDirty(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
		}
	}

	/**
	 * A block's result changes only if its sphere membership or its cone membership changes, so a section needs a
	 * rebuild only if one of those classifications is mixed or differs - and not at all if it is entirely near under
	 * both states via the sphere or via the cone.
	 */
	private record ChangeTest(LodReference.Ref oldRef, LodReference.Ref newRef, LodReference.Cone oldCone, LodReference.Cone newCone,
							  boolean grass, int extendDown, boolean refsDiffer, boolean conesDiffer) {
		boolean needsRebuild(int sx, int sy, int sz) {
			int sphereBefore = classify(this.oldRef, sx, sy, sz, this.extendDown);
			int sphereAfter = this.refsDiffer ? classify(this.newRef, sx, sy, sz, this.extendDown) : sphereBefore;

			if (sphereBefore == ALL_NEAR && sphereAfter == ALL_NEAR) {
				return false;
			}

			int coneBefore = classifyCone(this.oldCone, this.grass, sx, sy, sz, this.extendDown);
			int coneAfter = this.conesDiffer ? classifyCone(this.newCone, this.grass, sx, sy, sz, this.extendDown) : coneBefore;

			if (coneBefore == ALL_NEAR && coneAfter == ALL_NEAR) {
				return false;
			}

			boolean sphereChange = this.refsDiffer && (sphereBefore == MIXED || sphereAfter == MIXED || sphereBefore != sphereAfter);
			boolean coneChange = this.conesDiffer && (coneBefore == MIXED || coneAfter == MIXED || coneBefore != coneAfter);
			return sphereChange || coneChange;
		}
	}

	// ---------------------------------------------------------------- zoom

	/**
	 * Magnification of the current frame: tan(baseFov/2) / tan(fov/2), where fov is the FOV the camera actually uses
	 * for the projection ({@code Camera.getFov()} = result of {@code Camera.calculateFov}, which the spyglass changes via
	 * the FOV modifier and Zoomify via a mixin on its return value) and baseFov is the user's FOV option.
	 */
	static double zoomFactor(Minecraft mc, Camera camera) {
		double baseFov = mc.options.fov().get();
		double fov = camera.getFov();

		if (!(fov > 0.5) || fov >= baseFov) {
			return 1.0;
		}

		return Math.tan(Math.toRadians(baseFov) * 0.5) / Math.tan(Math.toRadians(fov) * 0.5);
	}

	/**
	 * Cone for the current zoom: apex at the camera, axis = view direction, half-angle = half of the field of view along
	 * the screen diagonal plus a margin (covers the whole visible frame incl. corners). Radii = base radius x zoom
	 * factor, snapped down to {@link #ZOOM_RADIUS_SNAP}, capped by the config and the render distance (grass
	 * additionally by {@link #GRASS_ZOOM_CAP}). Returns {@link LodReference.Cone#NONE} if the zoom would not extend
	 * anything.
	 */
	private static LodReference.Cone buildCone(Minecraft mc, Camera camera, Vec3 cam, double factor, double leafRadius,
											   double grassRadius, boolean grassActive, LeafLodConfig config) {
		double cap = Math.min(config.zoomMaxDistance, maxRadius(mc));
		double leafZoom = snapDown(Math.min(leafRadius * factor, cap));
		double grassZoom = grassActive ? snapDown(Math.min(Math.min(grassRadius * factor, GRASS_ZOOM_CAP), cap)) : 0.0;

		if (leafZoom <= leafRadius) {
			leafZoom = 0.0;
		}

		if (grassZoom <= grassRadius) {
			grassZoom = 0.0;
		}

		if (leafZoom == 0.0 && grassZoom == 0.0) {
			return LodReference.Cone.NONE;
		}

		double width = Math.max(1, mc.getWindow().getWidth());
		double height = Math.max(1, mc.getWindow().getHeight());
		double aspect = width / height;
		double halfVertical = Math.toRadians(camera.getFov()) * 0.5;
		double halfDiagonal = Math.atan(Math.tan(halfVertical) * Math.sqrt(1.0 + aspect * aspect));
		// Whole degrees (rounded up) so small FOV jitter does not cause updates.
		double halfAngle = Math.toRadians(Math.min(MAX_CONE_HALF_ANGLE_DEGREES, Math.ceil(Math.toDegrees(halfDiagonal) + CONE_MARGIN_DEGREES)));

		var forward = camera.forwardVector();
		double ax = forward.x();
		double ay = forward.y();
		double az = forward.z();
		double length = Math.sqrt(ax * ax + ay * ay + az * az);

		if (!(length > 1.0e-6)) {
			return LodReference.Cone.NONE;
		}

		return LodReference.Cone.of(cam.x, cam.y, cam.z, ax / length, ay / length, az / length, halfAngle, leafZoom, grassZoom, factor);
	}

	/**
	 * Updates the per-tick zoom tracking ({@link #zoomTicks}, stability) and returns whether the zoom factor has been
	 * stable (within {@link #ZOOM_STABLE_CHANGE}) for {@link #ZOOM_MIN_TICKS} ticks.
	 */
	private static boolean updateZoomTracking(double factor, boolean newLevel) {
		if (newLevel) {
			zoomTicks = 0;
			stableTicks = 0;
		}

		zoomTicks = factor >= ZOOM_THRESHOLD ? zoomTicks + 1 : 0;
		stableTicks = Math.abs(factor - lastZoomFactor) <= ZOOM_STABLE_CHANGE * factor ? stableTicks + 1 : 0;
		lastZoomFactor = factor;
		return stableTicks >= ZOOM_MIN_TICKS;
	}

	/**
	 * Avoids re-meshing for zoom changes that don't need it (the current cone is kept when it points the same way):
	 * <ul>
	 *     <li>the candidate is contained in the current cone (smaller or equal radii and half-angle, e.g. zooming in further
	 *     once the radius is capped): the current cone still covers the view; the surplus lies outside the view, where
	 *     sections are frustum-culled anyway;</li>
	 *     <li>the zoom level is still changing and the candidate has smaller radii (zooming out): zooming out passes through
	 *     all intermediate levels; the candidate is used once the level is stable again (or the zoom ends -> grace).</li>
	 * </ul>
	 */
	private static LodReference.Cone keepWhileZoomingOut(LodReference.Cone current, LodReference.Cone candidate, boolean factorStable) {
		if (!current.active() || !candidate.active()) {
			return candidate;
		}

		double dot = current.ax() * candidate.ax() + current.ay() * candidate.ay() + current.az() * candidate.az();
		double dx = current.x() - candidate.x();
		double dy = current.y() - candidate.y();
		double dz = current.z() - candidate.z();
		boolean sameAim = dot >= COS_REAIM && dx * dx + dy * dy + dz * dz < CONE_MOVE_BLOCKS * CONE_MOVE_BLOCKS;

		if (!sameAim) {
			return candidate;
		}

		boolean smallerRadii = candidate.leafRadius() <= current.leafRadius() && candidate.grassRadius() <= current.grassRadius();
		boolean narrower = candidate.cosHalf() >= current.cosHalf();

		if (smallerRadii && narrower) {
			return current;
		}

		return smallerRadii && !factorStable ? current : candidate;
	}

	private static double snapDown(double radius) {
		return Math.floor(radius / ZOOM_RADIUS_SNAP) * ZOOM_RADIUS_SNAP;
	}

	/** Whether two cones are close enough that no update is needed (re-aim only after half of the margin is used up). */
	private static boolean sameCone(LodReference.Cone a, LodReference.Cone b) {
		if (!a.active() || !b.active()) {
			return a.active() == b.active();
		}

		if (a.leafRadius() != b.leafRadius() || a.grassRadius() != b.grassRadius() || a.cosHalf() != b.cosHalf()) {
			return false;
		}

		double dot = a.ax() * b.ax() + a.ay() * b.ay() + a.az() * b.az();

		if (dot < COS_REAIM) {
			return false;
		}

		double dx = a.x() - b.x();
		double dy = a.y() - b.y();
		double dz = a.z() - b.z();
		return dx * dx + dy * dy + dz * dz < CONE_MOVE_BLOCKS * CONE_MOVE_BLOCKS;
	}

	/** Whether the part of two cones that affects one block kind is identical (same geometry and radius, or both off). */
	private static boolean sameConePart(LodReference.Cone a, LodReference.Cone b, boolean grass) {
		double ra = a.active() ? (grass ? a.grassRadius() : a.leafRadius()) : 0.0;
		double rb = b.active() ? (grass ? b.grassRadius() : b.leafRadius()) : 0.0;

		if (ra == 0.0 && rb == 0.0) {
			return true;
		}

		return ra == rb && a.x() == b.x() && a.y() == b.y() && a.z() == b.z()
				&& a.ax() == b.ax() && a.ay() == b.ay() && a.az() == b.az() && a.cosHalf() == b.cosHalf();
	}

	/**
	 * Classifies a section against a cone (block centres approximated by their bounding sphere): ALL_NEAR if the sphere is
	 * entirely inside the cone and within the radius, ALL_FAR if it is entirely outside either, otherwise MIXED.
	 * Uses the signed distance of the sphere centre to the cone surface: e = |perpendicular| * cos - along * sin
	 * (a lower bound of the true distance outside, the exact depth inside).
	 */
	private static int classifyCone(LodReference.Cone cone, boolean grass, int sx, int sy, int sz, int extendDown) {
		double radius = cone.active() ? (grass ? cone.grassRadius() : cone.leafRadius()) : 0.0;

		if (radius <= 0.0) {
			return ALL_FAR;
		}

		double radiusSq = radius * radius;

		if (SectionDistance.minSq(cone.x(), cone.y(), cone.z(), sx, sy, sz, extendDown) > radiusSq) {
			return ALL_FAR;
		}

		double vx = sx * 16 + 8.0 - cone.x();
		double vy = sy * 16 + 8.0 - 0.5 * extendDown - cone.y();
		double vz = sz * 16 + 8.0 - cone.z();
		double along = vx * cone.ax() + vy * cone.ay() + vz * cone.az();
		double perp = Math.sqrt(Math.max(0.0, vx * vx + vy * vy + vz * vz - along * along));
		double signedDistance = perp * cone.cosHalf() - along * cone.sinHalf();

		if (signedDistance >= SECTION_SPHERE_RADIUS) {
			return ALL_FAR;
		}

		if (signedDistance <= -SECTION_SPHERE_RADIUS
				&& SectionDistance.maxSq(cone.x(), cone.y(), cone.z(), sx, sy, sz, extendDown) <= radiusSq) {
			return ALL_NEAR;
		}

		return MIXED;
	}

	/** Section-coordinate box {minX, minY, minZ, maxX, maxY, maxZ} containing every section that is not entirely far. */
	private static int[] box(LodReference.Ref ref) {
		double r = ref.radius() + 1.0;
		return new int[] {
				SectionPos.blockToSectionCoord(Mth.floor(ref.x() - r)),
				SectionPos.blockToSectionCoord(Mth.floor(ref.y() - r)),
				SectionPos.blockToSectionCoord(Mth.floor(ref.z() - r)),
				SectionPos.blockToSectionCoord(Mth.floor(ref.x() + r)),
				SectionPos.blockToSectionCoord(Mth.floor(ref.y() + r)),
				SectionPos.blockToSectionCoord(Mth.floor(ref.z() + r))
		};
	}

	private static long volume(int[] box) {
		return (long) (box[3] - box[0] + 1) * (box[4] - box[1] + 1) * (box[5] - box[2] + 1);
	}

	private static boolean inBox(int[] box, int sx, int sy, int sz) {
		return sx >= box[0] && sx <= box[3] && sy >= box[1] && sy <= box[4] && sz >= box[2] && sz <= box[5];
	}

	/** Classifies a section by the distances of its block centres to the reference (same metric as {@link LodReference.Ref#isNear}). */
	private static int classify(LodReference.Ref ref, int sx, int sy, int sz, int extendDown) {
		if (!ref.has()) {
			return ALL_FAR;
		}

		if (SectionDistance.maxSq(ref.x(), ref.y(), ref.z(), sx, sy, sz, extendDown) <= ref.radiusSq()) {
			return ALL_NEAR;
		}

		if (SectionDistance.minSq(ref.x(), ref.y(), ref.z(), sx, sy, sz, extendDown) > ref.radiusSq()) {
			return ALL_FAR;
		}

		return MIXED;
	}

	/** Drops tracked sections that are far outside the render distance. */
	private static void prune(Minecraft mc, Vec3 cameraPos, double radius) {
		int keepSections = mc.options.getEffectiveRenderDistance() + Mth.ceil(radius / 16.0) + 4;
		int camX = SectionPos.blockToSectionCoord(Mth.floor(cameraPos.x));
		int camZ = SectionPos.blockToSectionCoord(Mth.floor(cameraPos.z));
		int removed = 0;

		for (SectionTracker tracker : new SectionTracker[] {SectionTracker.LEAVES, SectionTracker.GRASS}) {
			removed += tracker.removeIf(key ->
					Math.abs(SectionPos.x(key) - camX) > keepSections || Math.abs(SectionPos.z(key) - camZ) > keepSections);
		}

		if (debugLogging && removed > 0) {
			LeafLodClient.LOGGER.info("[Leaf LOD] Pruned {} far tracked sections", removed);
		}
	}
}
