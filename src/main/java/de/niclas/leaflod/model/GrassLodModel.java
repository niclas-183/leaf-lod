package de.niclas.leaflod.model;

import java.util.function.Predicate;

import de.niclas.leaflod.lod.LodReference;
import de.niclas.leaflod.lod.SectionTracker;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Wraps a heavy grass / fern / bush model. Near the reference position the wrapped (resource pack) model is emitted
 * unchanged; farther away a vanilla-style X-cross ({@link FarCross}: 2 planes, same sprite/tint/material as the original)
 * is emitted instead.
 *
 * <p>For the upper half of a double plant the distance is measured at the block below, so both halves always
 * switch together.
 */
public final class GrassLodModel extends WrapperBlockStateModel {
	private final FarCross farCross;
	private final boolean upperHalf;

	GrassLodModel(BlockStateModel wrapped, FarCross farCross, boolean upperHalf) {
		super(wrapped);
		this.farCross = farCross;
		this.upperHalf = upperHalf;
	}

	public BlockStateModel wrapped() {
		return this.wrapped;
	}

	@Override
	public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, Predicate<Direction> cullTest) {
		LodReference.Snapshot snapshot = LodReference.current();

		if (!snapshot.active() || !snapshot.grassActive()) {
			this.wrapped.emitQuads(emitter, level, pos, state, random, cullTest);
			return;
		}

		SectionTracker.GRASS.record(pos);

		if (this.isNear(snapshot, pos)) {
			this.wrapped.emitQuads(emitter, level, pos, state, random, cullTest);
		} else {
			this.farCross.emit(emitter);
		}
	}

	private boolean isNear(LodReference.Snapshot snapshot, BlockPos pos) {
		return snapshot.isGrassNear(pos.getX(), this.upperHalf ? pos.getY() - 1 : pos.getY(), pos.getZ());
	}

	@Override
	public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
		LodReference.Snapshot snapshot = LodReference.current();

		if (!snapshot.active() || !snapshot.grassActive() || this.isNear(snapshot, pos)) {
			return this.wrapped.createGeometryKey(level, pos, state, random);
		}

		// The far cross depends only on this model instance.
		return new FarCrossKey(this.farCross);
	}

	private record FarCrossKey(FarCross cross) {
	}
}
