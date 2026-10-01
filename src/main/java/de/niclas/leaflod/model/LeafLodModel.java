package de.niclas.leaflod.model;

import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import de.niclas.leaflod.lod.LodDebug;
import de.niclas.leaflod.lod.LodReference;
import de.niclas.leaflod.lod.SectionTracker;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MossyCarpetBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SegmentableBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Wraps a "bushy" leaf model. Near the reference camera position the wrapped model is emitted unchanged
 * (1:1 the resource pack's quads); farther away - and, optionally, for near leaves that are completely surrounded -
 * only the plain cube faces are emitted (see {@link CubeFaceFilter}). If some random variants of the pack have no
 * cube faces of their own, the reduced output of every variant is the cube of one fixed variant.
 *
 * <p>Extends Fabric's {@link WrapperBlockStateModel} on purpose: everything not overridden here
 * (vanilla {@code collectParts}, particle material, material flags, ...) is delegated, and other mods
 * that special-case that class (e.g. More Culling) keep working.
 */
public final class LeafLodModel extends WrapperBlockStateModel {
	private static final ThreadLocal<BlockPos.MutableBlockPos> SCRATCH = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

	/**
	 * Neighbour offsets (packed, see {@link #pack}) into which the extra (bush) geometry reaches. A leaf whose
	 * neighbours at all these offsets are leaves or full opaque blocks is "inner". {@code null} = never inner
	 * (geometry reaches farther than the 3x3x3 neighbourhood that block updates re-mesh).
	 */
	private final int[] exposureOffsets;
	/** Some extra (bush) geometry reaches above the block top (y > 1) and can poke through snow on top. */
	private final boolean reachesAbove;
	/**
	 * {@code null} if every random variant has its own cube faces. Otherwise one variant (with cube faces) whose cube is
	 * the reduced output of ALL variants - filtering a variant without cube faces would leave a hole.
	 */
	private final @Nullable BlockStateModel farCube;

	public LeafLodModel(BlockStateModel wrapped, int[] exposureOffsets, boolean reachesAbove, @Nullable BlockStateModel farCube) {
		super(wrapped);
		this.exposureOffsets = exposureOffsets;
		this.reachesAbove = reachesAbove;
		this.farCube = farCube;
	}

	public BlockStateModel wrapped() {
		return this.wrapped;
	}

	/** Whether some bush geometry reaches above the block top (snow clipping applies). */
	public boolean reachesAbove() {
		return this.reachesAbove;
	}

	/** Whether the reduced output is the cube of one fixed variant (some variants have no cube faces of their own). */
	public boolean usesSharedFarCube() {
		return this.farCube != null;
	}

	@Override
	public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, Predicate<Direction> cullTest) {
		LodReference.Snapshot snapshot = LodReference.current();

		if (!snapshot.active()) {
			this.wrapped.emitQuads(emitter, level, pos, state, random, cullTest);
			return;
		}

		SectionTracker.LEAVES.record(pos);

		if (LodDebug.ENABLED) {
			LodDebug.countLeafMesh(pos);
		}

		if (this.useFullModel(snapshot, level, pos)) {
			if (this.clipsTop(snapshot, level, pos)) {
				// Snow (or another thin cover) on top: keep the bush, but nothing above the block's top face.
				emitter.pushTransform(TopClipFilter.INSTANCE);

				try {
					this.wrapped.emitQuads(emitter, level, pos, state, random, cullTest);
				} finally {
					emitter.popTransform();
				}
			} else {
				this.wrapped.emitQuads(emitter, level, pos, state, random, cullTest);
			}

			return;
		}

		emitter.pushTransform(CubeFaceFilter.INSTANCE);

		try {
			(this.farCube != null ? this.farCube : this.wrapped).emitQuads(emitter, level, pos, state, random, cullTest);
		} finally {
			emitter.popTransform();
		}
	}

	private boolean useFullModel(LodReference.Snapshot snapshot, BlockAndTintGetter level, BlockPos pos) {
		if (!snapshot.isLeafNear(pos)) {
			return false;
		}

		return !snapshot.outerLeavesOnly() || this.exposureOffsets == null || this.isExposed(level, pos);
	}

	private boolean clipsTop(LodReference.Snapshot snapshot, BlockAndTintGetter level, BlockPos pos) {
		if (!snapshot.clipUnderSnow() || !this.reachesAbove) {
			return false;
		}

		BlockPos.MutableBlockPos above = SCRATCH.get();
		above.setWithOffset(pos, 0, 1, 0);
		return isThinCover(level.getBlockState(above));
	}

	/**
	 * Thin, non-full blocks lying on top of a leaf that the bush planes would poke through: snow layers, powder snow,
	 * carpets (wool, moss, pale moss), leaf litter and flower beds (pink petals, wildflowers). A full opaque block on top
	 * hides the planes anyway and is left alone.
	 */
	public static boolean isThinCover(BlockState state) {
		Block block = state.getBlock();
		return block instanceof SnowLayerBlock
				|| block instanceof PowderSnowBlock
				|| block instanceof CarpetBlock
				|| block instanceof MossyCarpetBlock
				|| block instanceof SegmentableBlock;
	}

	/** True if at least one neighbour the bush geometry reaches into is neither a leaf block nor a full opaque block. */
	private boolean isExposed(BlockAndTintGetter level, BlockPos pos) {
		BlockPos.MutableBlockPos neighbour = SCRATCH.get();

		for (int packed : this.exposureOffsets) {
			neighbour.setWithOffset(pos, unpackX(packed), unpackY(packed), unpackZ(packed));
			BlockState neighbourState = level.getBlockState(neighbour);

			if (!(neighbourState.getBlock() instanceof LeavesBlock) && !neighbourState.isSolidRender()) {
				return true;
			}
		}

		return false;
	}

	@Override
	public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
		Object subKey = this.wrapped.createGeometryKey(level, pos, state, random);

		if (subKey == null) {
			return null;
		}

		LodReference.Snapshot snapshot = LodReference.current();

		if (!snapshot.active()) {
			return subKey;
		}

		if (this.useFullModel(snapshot, level, pos)) {
			// Output is identical to the wrapped model, unless clipped under snow.
			return this.clipsTop(snapshot, level, pos) ? new ClippedTopKey(subKey) : subKey;
		}

		return this.farCube != null ? new SharedCubeKey(this.farCube) : new CubeOnlyKey(subKey);
	}

	/** Geometry key for the reduced (cube-only) output. */
	private record CubeOnlyKey(Object subKey) {
	}

	/** Geometry key for the reduced output taken from the shared cube variant (identical for every position). */
	private record SharedCubeKey(BlockStateModel farCube) {
	}

	/** Geometry key for the full output clipped at the block top. */
	private record ClippedTopKey(Object subKey) {
	}

	static int pack(int dx, int dy, int dz) {
		return ((dx + 1) << 4) | ((dy + 1) << 2) | (dz + 1);
	}

	private static int unpackX(int packed) {
		return ((packed >> 4) & 3) - 1;
	}

	private static int unpackY(int packed) {
		return ((packed >> 2) & 3) - 1;
	}

	private static int unpackZ(int packed) {
		return (packed & 3) - 1;
	}
}
