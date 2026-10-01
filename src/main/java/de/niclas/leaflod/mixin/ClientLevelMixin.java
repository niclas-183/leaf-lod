package de.niclas.leaflod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.niclas.leaflod.lod.LeafCounts;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Keeps the per-section leaf counts of the BUDGET mode exact: {@code Level.setBlock} calls
 * {@code setBlocksDirty(pos, oldState, newState)} for every client-side block state change.
 *
 * <p>{@code require = 0}: if this ever fails to apply, the game still starts; counts are then only refreshed
 * when chunks are (re)loaded.
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
	@Inject(method = "setBlocksDirty", at = @At("HEAD"), require = 0)
	private void leaflod$onBlockStateChanged(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
		LeafCounts.onBlockChanged((ClientLevel) (Object) this, pos, oldState, newState);
	}
}
