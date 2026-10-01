package de.niclas.leaflod.gametest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.niclas.leaflod.config.LeafLodConfig;
import de.niclas.leaflod.lod.LodController;
import de.niclas.leaflod.lod.LodReference;
import de.niclas.leaflod.model.GrassLodModel;
import de.niclas.leaflod.model.LeafLodModel;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Optional dev test with a REAL resource pack (e.g. Vervada's enhanced plants or a bushy-leaves pack). Runs only if the game
 * is started with {@code -Dleaflod.realPack=<path to pack zip>} (Gradle: {@code -Pdev_real_pack=<path>}). The zip is
 * copied into the gametest run directory (the source file is only read). The built-in synthetic test pack is switched off,
 * so only the real pack's models are seen.
 *
 * <p>Checks compare the mod's output with the pack's own output (the wrapped model) over several random seeds, so no
 * pack-specific numbers are hard-coded: near = exactly the pack, far = fewer quads but never nothing (no holes).
 *
 * <p>Optional: {@code -Dleaflod.shaderPack=<shader pack folder or zip>} (Gradle: {@code -Pdev_shader_pack}) enables that
 * Iris shader pack before the scenes are rendered; {@code -Dleaflod.screenshotSize=WxH} renders the screenshots larger.
 */
public class RealPackGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Leaf LOD gametest");
	private static final int SEEDS = 16;

	@Override
	public void runTest(ClientGameTestContext context) {
		String packPath = System.getProperty("leaflod.realPack");

		if (packPath == null || packPath.isBlank()) {
			LOGGER.info("[realpack] skipped (start with -Dleaflod.realPack=<zip> to test with a real resource pack)");
			return;
		}

		Path source = Path.of(packPath);
		String fileName = source.getFileName().toString();
		context.runOnClient(mc -> {
			try {
				Files.copy(source, mc.getResourcePackDirectory().resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		});
		CompletableFuture<Void> reload = context.computeOnClient(mc -> {
			PackRepository repository = mc.getResourcePackRepository();
			repository.reload();
			String id = "file/" + fileName;
			check(repository.getAvailableIds().contains(id), "pack " + id + " not available: " + repository.getAvailableIds());
			repository.removePack(SyntheticPackRegistration.PACK.toString());
			repository.addPack(id);
			Pack pack = repository.getPack(id);
			LOGGER.info("[realpack] Pack {}: compatibility {} (26.3 resource format 97.1), description '{}'", id,
					pack.getCompatibility(), pack.getDescription().getString().replace('\n', ' '));
			return mc.reloadResourcePacks();
		});
		context.waitFor(mc -> reload.isDone(), 6000);
		context.waitFor(mc -> mc.gui.overlay() == null, 6000);
		LOGGER.info("[realpack] Resource pack {} enabled; selected packs: {}", fileName,
				context.computeOnClient(mc -> mc.getResourcePackRepository().getSelectedIds()));

		String shaderPack = System.getProperty("leaflod.shaderPack");

		if (shaderPack != null && !shaderPack.isBlank()) {
			IrisShaderSupport.enable(context, Path.of(shaderPack));
		}

		context.runOnClient(mc -> setConfig(true, LeafLodConfig.Mode.FIXED, 24, false));

		try (TestSingleplayerContext singleplayer = context.worldBuilder()
				.adjustSettings(creator -> creator.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE))
				.create()) {
			singleplayer.getConnection().waitForChunksRender();
			context.waitFor(mc -> LodReference.current().leaves().has());
			BlockPos origin = context.computeOnClient(mc -> mc.player.blockPosition());
			// Midday light for readable screenshots (the camera looks east along the tree avenue).
			singleplayer.getServer().runCommand("time set noon");

			// ---- Model checks against the pack's own output, for every leaf block and the grass-like plants.
			List<String> wrappedLeaves = context.computeOnClient(RealPackGameTest::checkLeafModels);
			context.runOnClient(RealPackGameTest::checkGrassModels);
			Block snowLeaf = context.computeOnClient(mc -> pickSnowLeaf());

			// ---- Scene: rows of real trees and a grass field.
			singleplayer.getServer().runCommand("fill %d %d %d %d %d %d minecraft:short_grass replace minecraft:air".formatted(
					origin.getX() + 2, origin.getY(), origin.getZ() - 20, origin.getX() + 100, origin.getY(), origin.getZ() + 20));

			for (int d = 14, i = 0; d <= 110; d += 12, i++) {
				String feature = i % 2 == 0 ? "minecraft:mega_jungle_tree" : "minecraft:fancy_oak";
				singleplayer.getServer().runCommand("place feature %s %d %d %d".formatted(feature, origin.getX() + d, origin.getY(), origin.getZ() - 9));
				singleplayer.getServer().runCommand("place feature %s %d %d %d".formatted(feature, origin.getX() + d + 6, origin.getY(), origin.getZ() + 9));
			}

			hideHud(context);
			placeCamera(singleplayer, origin.getX(), origin.getY() + 12, origin.getZ(), -90, 18);
			settle(context, singleplayer);

			context.runOnClient(mc -> setConfig(false, LeafLodConfig.Mode.FIXED, 24, true));
			settle(context, singleplayer);
			screenshot(context, "realpack_01_mod_disabled");

			context.runOnClient(mc -> setConfig(true, LeafLodConfig.Mode.FIXED, 24, true));
			settle(context, singleplayer);
			screenshot(context, "realpack_02_fixed24_grass16");

			context.runOnClient(mc -> setConfig(true, LeafLodConfig.Mode.BUDGET, 24, true));
			settle(context, singleplayer);
			context.waitTicks(40);
			settle(context, singleplayer);
			double budgetRadius = context.computeOnClient(mc -> LodController.effectiveRadius());
			LOGGER.info("[realpack] Budget mode (default budget {}): effective leaf radius {}", LeafLodConfig.DEFAULT_BUDGET, budgetRadius);
			screenshot(context, "realpack_03_budget_default");

			// Snow on real bushy leaves: nothing above the leaf top with snow, full model without.
			if (snowLeaf != null) {
				testSnow(context, singleplayer, origin, snowLeaf);
			} else {
				LOGGER.info("[realpack] Snow: skipped (no wrapped leaf of this pack reaches above the block)");
			}

			// Zoom comparison (fixed 24): spyglass zoom down the tree avenue (trees 60-110 blocks away), extension off vs on.
			context.runOnClient(mc -> setConfig(true, LeafLodConfig.Mode.FIXED, 24, true));
			singleplayer.getServer().runCommand("item replace entity @p weapon.mainhand with minecraft:spyglass");
			placeCamera(singleplayer, origin.getX(), origin.getY() + 12, origin.getZ(), -90, 3);
			settle(context, singleplayer);

			for (boolean extension : new boolean[] {false, true}) {
				context.runOnClient(mc -> LeafLodConfig.get().zoomEnabled = extension);
				context.getInput().holdKey(options -> options.keyUse);
				context.waitFor(mc -> LodController.zoomFactor() >= 4.0 && (!extension || LodController.zoomCone().active()), 200);
				context.waitTicks(10);
				settle(context, singleplayer);
				LOGGER.info("[realpack] Zoom factor {}, extension {}, cone {}", String.format(Locale.ROOT, "%.2f", LodController.zoomFactor()), extension,
						context.computeOnClient(mc -> LodController.zoomCone()));
				screenshot(context, extension ? "realpack_07_zoomed_extension_on" : "realpack_06_zoomed_extension_off");
				context.getInput().releaseKey(options -> options.keyUse);
				context.waitFor(mc -> LodController.zoomFactor() < 1.5, 200);
			}

			singleplayer.getServer().runCommand("item replace entity @p weapon.mainhand with minecraft:air");
			context.runOnClient(mc -> setConfig(true, LeafLodConfig.Mode.BUDGET, 24, true));
			settle(context, singleplayer);

			// Same scene from the side and from the opposite direction (catches view-dependent culling problems).
			placeCamera(singleplayer, origin.getX() + 60, origin.getY() + 8, origin.getZ() - 30, 0, 15);
			settle(context, singleplayer);
			screenshot(context, "realpack_04_budget_view_south");
			placeCamera(singleplayer, origin.getX() + 110, origin.getY() + 10, origin.getZ(), 90, 15);
			settle(context, singleplayer);
			screenshot(context, "realpack_05_budget_view_west");

			if (shaderPack != null && !shaderPack.isBlank()) {
				String status = context.computeOnClient(mc -> IrisShaderSupport.status());
				LOGGER.info("[realpack] Shader status at the end: {}", status);
			}

			LOGGER.info("[realpack] Summary: {} leaf blocks wrapped: {}", wrappedLeaves.size(), wrappedLeaves);
		}

		LOGGER.info("[realpack] All real-pack checks passed");
	}

	/**
	 * Every {@link LeavesBlock}: if wrapped, compares near/far output with the pack's own output for {@link #SEEDS} random
	 * seeds (random variants). Returns the ids of the wrapped blocks.
	 */
	private static List<String> checkLeafModels(Minecraft mc) {
		List<String> wrapped = new ArrayList<>();
		List<String> unchanged = new ArrayList<>();
		BlockPos near = mc.player.blockPosition().above(3);
		BlockPos far = near.offset(200, 0, 0);

		for (Block block : BuiltInRegistries.BLOCK) {
			if (!(block instanceof LeavesBlock)) {
				continue;
			}

			String id = BuiltInRegistries.BLOCK.getKey(block).toString();
			BlockState state = block.defaultBlockState();
			BlockStateModel outer = model(mc, state);
			LeafLodModel lod = find(outer, LeafLodModel.class);

			if (lod == null) {
				unchanged.add(id + " (" + range(mc, outer, state, near) + " quads)");
				continue;
			}

			int packMin = Integer.MAX_VALUE, packMax = 0, nearMin = Integer.MAX_VALUE, nearMax = 0, farMin = Integer.MAX_VALUE, farMax = 0;
			int packSum = 0, farSum = 0;

			for (long seed = 0; seed < SEEDS; seed++) {
				int pack = count(mc, lod.wrapped(), state, near, seed);
				int nearCount = count(mc, outer, state, near, seed);
				int farCount = count(mc, outer, state, far, seed);
				check(nearCount == pack, id + ": near output must equal the pack's output (seed " + seed + ": " + nearCount + " vs " + pack + ")");
				check(farCount > 0, id + ": far output must never be empty (seed " + seed + ")");
				// (With a shared far cube a light variant without cube faces may get a cube with more quads - still no hole.)
				check(farCount <= pack || lod.usesSharedFarCube(), id + ": far output must not have more quads than the pack (seed " + seed + ")");
				packMin = Math.min(packMin, pack);
				packMax = Math.max(packMax, pack);
				nearMin = Math.min(nearMin, nearCount);
				nearMax = Math.max(nearMax, nearCount);
				farMin = Math.min(farMin, farCount);
				farMax = Math.max(farMax, farCount);
				packSum += pack;
				farSum += farCount;
			}

			check(farSum < packSum, id + ": far output must be reduced");
			LOGGER.info("[realpack] {}: pack {} quads; Leaf LOD near {} / far {}; shared far cube {}; reaches above {}; quads far/near {}%",
					id, span(packMin, packMax), span(nearMin, nearMax), span(farMin, farMax), lod.usesSharedFarCube(), lod.reachesAbove(),
					Math.round(100.0 * farSum / packSum));
			wrapped.add(id);
		}

		LOGGER.info("[realpack] Leaf blocks NOT wrapped (model unchanged): {}", unchanged);
		return wrapped;
	}

	private static void checkGrassModels(Minecraft mc) {
		BlockPos near = mc.player.blockPosition();
		BlockPos far = near.offset(200, 0, 0);

		for (Block block : new Block[] {Blocks.SHORT_GRASS, Blocks.FERN, Blocks.TALL_GRASS, Blocks.LARGE_FERN, Blocks.BUSH}) {
			BlockState state = block.defaultBlockState();
			BlockStateModel outer = model(mc, state);
			GrassLodModel lod = find(outer, GrassLodModel.class);

			if (lod == null) {
				LOGGER.info("[realpack] {}: not wrapped ({} quads, like the vanilla cross or lighter)", block, range(mc, outer, state, near));
				continue;
			}

			int pack = count(mc, lod.wrapped(), state, near, 42L);
			int nearCount = count(mc, outer, state, near, 42L);
			int farCount = count(mc, outer, state, far, 42L);
			LOGGER.info("[realpack] {}: pack model {} quads; Leaf LOD near {} / far {}", block, pack, nearCount, farCount);
			check(nearCount == pack, block + ": near output must equal the pack's output");
			check(farCount == 4, block + ": far output must be the 4-quad cross");
		}
	}

	/** A wrapped leaf block whose bush geometry reaches above the block (spruce preferred), or null. */
	private static Block pickSnowLeaf() {
		Minecraft mc = Minecraft.getInstance();
		Block fallback = null;

		for (Block block : BuiltInRegistries.BLOCK) {
			if (block instanceof LeavesBlock) {
				LeafLodModel lod = find(model(mc, block.defaultBlockState()), LeafLodModel.class);

				if (lod != null && lod.reachesAbove()) {
					if (block == Blocks.SPRUCE_LEAVES) {
						return block;
					}

					if (fallback == null) {
						fallback = block;
					}
				}
			}
		}

		return fallback;
	}

	private static void testSnow(ClientGameTestContext context, TestSingleplayerContext singleplayer, BlockPos origin, Block leaf) {
		String leafId = BuiltInRegistries.BLOCK.getKey(leaf).toString();
		BlockPos snowLeaf = origin.offset(6, 4, -4);
		singleplayer.getServer().runCommand("fill %d %d %d %d %d %d %s[persistent=true]".formatted(
				origin.getX() + 4, origin.getY() + 4, origin.getZ() - 6, origin.getX() + 10, origin.getY() + 4, origin.getZ() - 2, leafId));
		context.runOnClient(mc -> setConfig(true, LeafLodConfig.Mode.FIXED, 24, true));
		settle(context, singleplayer);
		int[] noSnow = context.computeOnClient(mc -> quadStats(mc, mc.level.getBlockState(snowLeaf), snowLeaf));
		singleplayer.getServer().runCommand("fill %d %d %d %d %d %d minecraft:snow[layers=1]".formatted(
				origin.getX() + 4, origin.getY() + 5, origin.getZ() - 6, origin.getX() + 10, origin.getY() + 5, origin.getZ() - 2));
		settle(context, singleplayer);
		int[] withSnow = context.computeOnClient(mc -> quadStats(mc, mc.level.getBlockState(snowLeaf), snowLeaf));
		LOGGER.info("[realpack] Snow ({}): leaf without snow {} quads, y {}..{}; with snow {} quads, y {}..{} (x1000)",
				leafId, noSnow[0], noSnow[2], noSnow[1], withSnow[0], withSnow[2], withSnow[1]);
		check(noSnow[1] > 1010, "real pack: leaf without snow must reach above the block");
		check(withSnow[1] <= 1001, "real pack: nothing above y = 1 with snow on top, got " + withSnow[1]);
		check(withSnow[2] == noSnow[2], "real pack: the bottom of the bush must stay with snow");
		placeCamera(singleplayer, origin.getX(), origin.getY() + 8, origin.getZ() + 2, -120, 35);

		for (boolean clip : new boolean[] {false, true}) {
			context.runOnClient(mc -> {
				LeafLodConfig.get().clipUnderSnow = clip;
				LodController.applyConfig(true);
			});
			settle(context, singleplayer);
			screenshot(context, clip ? "realpack_05b_snow_clipped" : "realpack_05a_snow_not_clipped");
		}

		singleplayer.getServer().runCommand("fill %d %d %d %d %d %d minecraft:air".formatted(
				origin.getX() + 4, origin.getY() + 4, origin.getZ() - 6, origin.getX() + 10, origin.getY() + 5, origin.getZ() - 2));
	}

	private static void setConfig(boolean enabled, LeafLodConfig.Mode mode, int distance, boolean reload) {
		LeafLodConfig config = LeafLodConfig.get();
		LeafLodConfig defaults = new LeafLodConfig();
		config.enabled = enabled;
		config.mode = mode;
		config.lodDistance = distance;
		config.leafBudget = defaults.leafBudget;
		config.minRadius = defaults.minRadius;
		config.outerLeavesOnly = true;
		config.grassEnabled = true;
		config.grassDistance = 16;
		LeafLodConfig.save();
		LodController.applyConfig(reload);
	}

	@SuppressWarnings("unchecked")
	private static <T> T find(BlockStateModel model, Class<T> type) {
		try {
			java.lang.reflect.Field field = WrapperBlockStateModel.class.getDeclaredField("wrapped");
			field.setAccessible(true);

			while (true) {
				if (type.isInstance(model)) {
					return (T) model;
				}

				if (!(model instanceof WrapperBlockStateModel)) {
					return null;
				}

				model = (BlockStateModel) field.get(model);
			}
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static BlockStateModel model(Minecraft mc, BlockState state) {
		return mc.getModelManager().getBlockStateModelSet().get(state);
	}

	private static int count(Minecraft mc, BlockStateModel model, BlockState state, BlockPos pos, long seed) {
		int[] count = {0};
		QuadEmitter emitter = Renderer.get().quadEmitter(quad -> count[0]++);
		// Mixed seed: small sequential seeds give correlated first values (same random variant every time).
		model.emitQuads(emitter, mc.level, pos, state, RandomSource.create(seed * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L), direction -> false);
		return count[0];
	}

	private static String range(Minecraft mc, BlockStateModel model, BlockState state, BlockPos pos) {
		int min = Integer.MAX_VALUE, max = 0;

		for (long seed = 0; seed < SEEDS; seed++) {
			int n = count(mc, model, state, pos, seed);
			min = Math.min(min, n);
			max = Math.max(max, n);
		}

		return span(min, max);
	}

	private static String span(int min, int max) {
		return min == max ? Integer.toString(min) : min + ".." + max;
	}

	/** {quad count, max y x1000, min y x1000} of what the (outermost) model of {@code state} emits at {@code pos}. */
	private static int[] quadStats(Minecraft mc, BlockState state, BlockPos pos) {
		int[] count = {0};
		float[] extremes = {-Float.MAX_VALUE, Float.MAX_VALUE};
		QuadEmitter emitter = Renderer.get().quadEmitter(quad -> {
			count[0]++;

			for (int i = 0; i < 4; i++) {
				extremes[0] = Math.max(extremes[0], quad.y(i));
				extremes[1] = Math.min(extremes[1], quad.y(i));
			}
		});
		model(mc, state).emitQuads(emitter, mc.level, pos, state, RandomSource.create(42L), direction -> false);
		return new int[] {count[0], Math.round(extremes[0] * 1000), Math.round(extremes[1] * 1000)};
	}

	private static void settle(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		singleplayer.getConnection().waitForClientboundPackets();
		context.waitTicks(5);
		singleplayer.getConnection().waitForChunksRender(false);
		context.waitTicks(5);
	}

	private static void hideHud(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			if (!mc.gui.hud.isHidden()) {
				mc.gui.hud.toggle();
			}
		});
	}

	private static void placeCamera(TestSingleplayerContext singleplayer, int x, int feetY, int z, int yaw, int pitch) {
		singleplayer.getServer().runCommand("setblock %d %d %d minecraft:barrier".formatted(x, feetY - 1, z));
		singleplayer.getServer().runCommand("tp @p %d.5 %d %d.5 %d %d".formatted(x, feetY, z, yaw, pitch));
	}

	private static void screenshot(ClientGameTestContext context, String name) {
		String size = System.getProperty("leaflod.screenshotSize");
		TestScreenshotOptions options = TestScreenshotOptions.of(name);

		if (size != null && size.matches("\\d+x\\d+")) {
			String[] wh = size.split("x");
			options = options.withSize(Integer.parseInt(wh[0]), Integer.parseInt(wh[1]));
		}

		Path path = context.takeScreenshot(options);
		LOGGER.info("[realpack] Screenshot: {}", path.toAbsolutePath());
	}

	static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	/** Iris is only touched when a shader pack is requested (and therefore present). */
	private static final class IrisShaderSupport {
		static void enable(ClientGameTestContext context, Path shaderPack) {
			check(FabricLoader.getInstance().isModLoaded("iris"), "a shader pack was requested but Iris is not loaded");
			String name = shaderPack.getFileName().toString();
			context.runOnClient(mc -> {
				try {
					install(shaderPack, name);
				} catch (java.io.IOException e) {
					throw new java.io.UncheckedIOException(e);
				}
			});
			context.waitFor(mc -> net.irisshaders.iris.Iris.getCurrentPack().isPresent() || net.irisshaders.iris.Iris.getStoredError().isPresent(), 2400);
			String status = context.computeOnClient(mc -> status());
			LOGGER.info("[realpack] Shader pack enabled: {}", status);
			check(net.irisshaders.iris.Iris.getStoredError().isEmpty(), "shader pack failed to load: " + status);
		}

		private static void install(Path shaderPack, String name) throws java.io.IOException {
			Path target = net.irisshaders.iris.Iris.getShaderpacksDirectory();
			Files.createDirectories(target);
			copyRecursively(shaderPack, target.resolve(name));
			Path options = shaderPack.resolveSibling(name + ".txt");

			if (Files.exists(options)) {
				Files.copy(options, target.resolve(name + ".txt"), StandardCopyOption.REPLACE_EXISTING);
			}

			// Same Iris settings as the user's instance (shader pack, shadow distance); then reload like the shader menu does.
			Path properties = FabricLoader.getInstance().getConfigDir().resolve("iris.properties");
			Files.writeString(properties, String.join("\n",
					"allowUnknownShaders=false",
					"colorSpace=SRGB",
					"disableUpdateMessage=true",
					"enableDebugOptions=false",
					"enableShaders=true",
					"maxShadowRenderDistance=32",
					"shaderPack=" + name, ""));
			net.irisshaders.iris.Iris.getIrisConfig().load();
			net.irisshaders.iris.Iris.reload();
		}

		static String status() {
			return "pack=" + net.irisshaders.iris.Iris.getCurrentPackName()
					+ ", loaded=" + net.irisshaders.iris.Iris.getCurrentPack().isPresent()
					+ ", inUse=" + net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse()
					+ ", error=" + net.irisshaders.iris.Iris.getStoredError().map(Throwable::toString).orElse("none");
		}

		private static void copyRecursively(Path from, Path to) throws java.io.IOException {
			if (Files.isRegularFile(from)) {
				Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
				return;
			}

			try (var paths = Files.walk(from)) {
				for (Path path : (Iterable<Path>) paths::iterator) {
					Path destination = to.resolve(from.relativize(path).toString());

					if (Files.isDirectory(path)) {
						Files.createDirectories(destination);
					} else {
						Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
		}
	}
}
