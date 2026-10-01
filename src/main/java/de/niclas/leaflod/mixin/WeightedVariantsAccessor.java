package de.niclas.leaflod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.util.random.WeightedList;

/**
 * Read access to the random variants of a block-state model (e.g. a resource pack's
 * {@code "variants": {"": [ {...}, {...} ]}}), so the model analysis can look at every variant exactly instead of
 * sampling random seeds. Only used while models are (re)loaded.
 */
@Mixin(WeightedVariants.class)
public interface WeightedVariantsAccessor {
	@Accessor("list")
	WeightedList<BlockStateModel> leaflod$getList();
}
