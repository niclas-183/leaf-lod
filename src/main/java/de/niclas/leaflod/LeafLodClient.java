package de.niclas.leaflod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.niclas.leaflod.config.LeafLodConfig;
import de.niclas.leaflod.lod.LeafCounts;
import de.niclas.leaflod.lod.LodController;
import de.niclas.leaflod.model.LodModelPlugin;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;

public final class LeafLodClient implements ClientModInitializer {
	public static final String MOD_ID = "leaflod";
	public static final Logger LOGGER = LoggerFactory.getLogger("Leaf LOD");

	@Override
	public void onInitializeClient() {
		LeafLodConfig.load();
		LodController.applyConfig(false);

		ModelLoadingPlugin.register(new LodModelPlugin());

		// Per-section leaf counts for the BUDGET mode (block changes come from ClientLevelMixin).
		ClientChunkEvents.CHUNK_LOAD.register(LeafCounts::onChunkLoad);
		ClientChunkEvents.CHUNK_UNLOAD.register(LeafCounts::onChunkUnload);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			LodController.onEndClientTick(client);

			if (client.gui.overlay() == null) {
				LodModelPlugin.logSummaryIfPending();
			}
		});

		LeafLodConfig config = LeafLodConfig.get();
		LOGGER.info("[Leaf LOD] Initialised (enabled={}, mode={}, fixed distance={}, leaf budget={}, min radius={}, outer leaves only={}, clip under snow={}, grass={} / {} blocks, zoom={} / max {} blocks / grace {} ms, config: {})",
				config.enabled, config.mode, config.lodDistance, config.leafBudget, config.minRadius, config.outerLeavesOnly, config.clipUnderSnow,
				config.grassEnabled, config.grassDistance, config.zoomEnabled, config.zoomMaxDistance, config.zoomGraceMillis, LeafLodConfig.path());
	}
}
