package de.niclas.leaflod.lod;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/**
 * Remembers which chunk sections contained at least one LOD-wrapped block of one kind (leaves or grass) when they
 * were last meshed, so that a camera move only re-meshes sections where the result can actually change.
 *
 * <p>Entries are only removed by distance-based pruning; a stale entry merely causes one unnecessary re-mesh.
 * Recording is cheap: a thread-local "last section" cache means the concurrent set is touched only once per
 * section and meshing thread, not once per block.
 */
public final class SectionTracker {
	public static final SectionTracker LEAVES = new SectionTracker();
	public static final SectionTracker GRASS = new SectionTracker();

	private final Set<Long> sections = ConcurrentHashMap.newKeySet();

	/** Bumped whenever entries are removed, so thread-local caches re-add entries they think are present. */
	private volatile long generation;

	/** [0] = last recorded section key, [1] = generation it was recorded in. */
	private final ThreadLocal<long[]> last = ThreadLocal.withInitial(() -> new long[] {Long.MIN_VALUE, -1L});

	private SectionTracker() {
	}

	public void record(BlockPos pos) {
		long key = SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
		long gen = this.generation;
		long[] cache = this.last.get();

		if (cache[0] != key || cache[1] != gen) {
			this.sections.add(key);
			cache[0] = key;
			cache[1] = gen;
		}
	}

	boolean contains(int sectionX, int sectionY, int sectionZ) {
		return this.sections.contains(SectionPos.asLong(sectionX, sectionY, sectionZ));
	}

	int size() {
		return this.sections.size();
	}

	void forEach(LongConsumer action) {
		for (Long key : this.sections) {
			action.accept(key);
		}
	}

	/** Removes all entries matching {@code shouldRemove}; returns the number removed. */
	int removeIf(LongPredicate shouldRemove) {
		int before = this.sections.size();
		boolean removed = this.sections.removeIf(key -> shouldRemove.test(key));

		if (removed) {
			this.generation++;
		}

		return before - this.sections.size();
	}
}
