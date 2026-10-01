package de.niclas.leaflod.gametest;

import de.niclas.leaflod.LeafLodClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

/**
 * Registers the self-made test models ({@code resourcepacks/synthetic} in this source set) as a built-in resource pack,
 * enabled by default. The real-pack test switches it off, so only the real pack's models are tested there.
 */
public final class SyntheticPackRegistration implements ClientModInitializer {
	public static final Identifier PACK = Identifier.fromNamespaceAndPath("leaflod-gametest", "synthetic");

	@Override
	public void onInitializeClient() {
		boolean registered = ResourceLoader.registerBuiltinPack(PACK, FabricLoader.getInstance().getModContainer("leaflod-gametest").orElseThrow(),
				PackActivationType.DEFAULT_ENABLED);
		LeafLodClient.LOGGER.info("[Leaf LOD gametest] Built-in test pack {} registered: {}", PACK, registered);
	}
}
