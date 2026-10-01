package de.niclas.leaflod.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Mod Menu entrypoint. The config screen needs Cloth Config; without it, the config can still be
 * edited in {@code config/leaflod.json}.
 */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		if (FabricLoader.getInstance().isModLoaded("cloth-config")) {
			return ClothConfigScreen::create;
		}

		return ModMenuApi.super.getModConfigScreenFactory();
	}
}
