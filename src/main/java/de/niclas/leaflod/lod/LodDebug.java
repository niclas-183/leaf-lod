package de.niclas.leaflod.lod;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/**
 * Debug-only instrumentation (start the game with {@code -Dleaflod.debug=true}): counts how often wrapped leaves were
 * emitted per chunk section, so tests can verify that a section really was re-meshed. With the flag off the calls are
 * guarded by a {@code static final} constant and cost nothing.
 */
public final class LodDebug {
	public static final boolean ENABLED = Boolean.getBoolean("leaflod.debug");

	private static final ConcurrentHashMap<Long, AtomicInteger> LEAF_MESHES = new ConcurrentHashMap<>();

	private LodDebug() {
	}

	public static void countLeafMesh(BlockPos pos) {
		long key = SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
		LEAF_MESHES.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
	}

	/** Number of wrapped-leaf emissions recorded for the section containing {@code pos}. */
	public static int leafMeshCount(BlockPos pos) {
		AtomicInteger count = LEAF_MESHES.get(SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
		return count == null ? 0 : count.get();
	}
}
