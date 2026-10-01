package de.niclas.leaflod.lod;

import java.util.function.Predicate;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntMaps;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Number of leaf blocks per loaded chunk section of the current client level (BUDGET mode).
 *
 * <p>Client thread only. Filled when a chunk arrives ({@code ClientChunkEvents.CHUNK_LOAD}, palette-based count),
 * cleared on unload, and kept exact afterwards via +/-1 deltas from {@code ClientLevel.setBlocksDirty}
 * (see {@code ClientLevelMixin}).
 */
public final class LeafCounts {
	private static final Predicate<BlockState> IS_LEAVES = LeafCounts::isLeaves;

	private static final Long2IntOpenHashMap COUNTS = new Long2IntOpenHashMap();
	private static ClientLevel trackedLevel;
	private static long total;
	private static int version;

	// Scratch arrays for solveRadius (client thread only, reused to avoid garbage).
	private static double[] minDist = new double[256];
	private static double[] maxDist = new double[256];
	private static int[] counts = new int[256];

	private LeafCounts() {
	}

	public static boolean isLeaves(BlockState state) {
		return state.getBlock() instanceof LeavesBlock;
	}

	public static void onChunkLoad(ClientLevel level, LevelChunk chunk) {
		if (!accept(level)) {
			return;
		}

		ChunkPos pos = chunk.getPos();
		LevelChunkSection[] sections = chunk.getSections();

		for (int i = 0; i < sections.length; i++) {
			set(SectionPos.asLong(pos.x(), chunk.getSectionYFromSectionIndex(i), pos.z()), countLeaves(sections[i]));
		}
	}

	public static void onChunkUnload(ClientLevel level, LevelChunk chunk) {
		if (level != trackedLevel || !isClientThread()) {
			return;
		}

		ChunkPos pos = chunk.getPos();
		int sectionCount = chunk.getSections().length;

		for (int i = 0; i < sectionCount; i++) {
			set(SectionPos.asLong(pos.x(), chunk.getSectionYFromSectionIndex(i), pos.z()), 0);
		}
	}

	/** Called for every client-side block state change (from the ClientLevel mixin). */
	public static void onBlockChanged(ClientLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
		boolean was = isLeaves(oldState);
		boolean is = isLeaves(newState);

		if (was == is || !accept(level)) {
			return;
		}

		long key = SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
		set(key, Math.max(0, COUNTS.get(key) + (is ? 1 : -1)));
	}

	/** Called every client tick: forget everything when the level goes away. */
	static void tick(Minecraft mc) {
		if (mc.level != trackedLevel && trackedLevel != null) {
			reset(null);
		}
	}

	public static long total() {
		return total;
	}

	public static int sections() {
		return COUNTS.size();
	}

	/** Incremented on every change; lets the controller skip re-solving when nothing changed. */
	static int version() {
		return version;
	}

	/**
	 * Smallest radius around (px, py, pz) that contains about {@code budget} leaf blocks, or {@code maxRadius}
	 * if fewer leaves are loaded within it. Within a section, leaves are assumed to be spread evenly over the
	 * section's distance range; precision is about 1 block (good enough, the result is snapped to 4 blocks).
	 */
	static double solveRadius(double px, double py, double pz, int budget, double maxRadius) {
		int n = COUNTS.size();
		ensureCapacity(n);
		int m = 0;
		long reachable = 0;
		double maxRadiusSq = maxRadius * maxRadius;

		for (Long2IntMap.Entry entry : Long2IntMaps.fastIterable(COUNTS)) {
			long key = entry.getLongKey();
			int sx = SectionPos.x(key);
			int sy = SectionPos.y(key);
			int sz = SectionPos.z(key);
			double minSq = SectionDistance.minSq(px, py, pz, sx, sy, sz);

			if (minSq > maxRadiusSq) {
				continue;
			}

			minDist[m] = Math.sqrt(minSq);
			maxDist[m] = Math.sqrt(SectionDistance.maxSq(px, py, pz, sx, sy, sz));
			counts[m] = entry.getIntValue();
			reachable += counts[m];
			m++;
		}

		if (reachable < budget || leavesWithin(maxRadius, m) < budget) {
			return maxRadius;
		}

		double lo = 0.0;
		double hi = maxRadius;

		while (hi - lo > 0.5) {
			double mid = (lo + hi) * 0.5;

			if (leavesWithin(mid, m) >= budget) {
				hi = mid;
			} else {
				lo = mid;
			}
		}

		return hi;
	}

	private static double leavesWithin(double r, int m) {
		double sum = 0.0;

		for (int i = 0; i < m; i++) {
			double min = minDist[i];

			if (r <= min) {
				continue;
			}

			double max = maxDist[i];
			sum += r >= max ? counts[i] : counts[i] * (r - min) / (max - min);
		}

		return sum;
	}

	private static void ensureCapacity(int n) {
		if (minDist.length < n) {
			int size = Math.max(n, minDist.length * 2);
			minDist = new double[size];
			maxDist = new double[size];
			counts = new int[size];
		}
	}

	private static int countLeaves(LevelChunkSection section) {
		if (section == null || section.hasOnlyAir() || !section.maybeHas(IS_LEAVES)) {
			return 0;
		}

		int[] n = {0};
		section.getStates().count((state, count) -> {
			if (isLeaves(state)) {
				n[0] += count;
			}
		});
		return n[0];
	}

	private static boolean accept(ClientLevel level) {
		if (!isClientThread()) {
			return false;
		}

		if (level != trackedLevel) {
			if (level != Minecraft.getInstance().level) {
				return false;
			}

			reset(level);
		}

		return true;
	}

	private static void reset(ClientLevel level) {
		COUNTS.clear();
		COUNTS.trim();
		total = 0;
		version++;
		trackedLevel = level;
	}

	private static boolean isClientThread() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.isSameThread();
	}

	private static void set(long key, int count) {
		int old = count > 0 ? COUNTS.put(key, count) : COUNTS.remove(key);

		if (old != count) {
			total += count - old;
			version++;
		}
	}
}
