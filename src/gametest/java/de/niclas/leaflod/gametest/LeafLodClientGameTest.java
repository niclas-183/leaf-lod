package de.niclas.leaflod.gametest;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.niclas.leaflod.compat.ModMenuIntegration;
import de.niclas.leaflod.config.LeafLodConfig;
import de.niclas.leaflod.lod.LeafCounts;
import de.niclas.leaflod.lod.LodController;
import de.niclas.leaflod.lod.LodReference;
import de.niclas.leaflod.model.GrassLodModel;
import de.niclas.leaflod.model.LeafLodModel;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Automated in-game smoke test (dev only). Creates a flat creative world and checks/screenshots:
 * fixed-distance leaves, camera movement, disabled mode, inner-leaf optimisation, grass/fern LOD and the
 * leaf-budget mode. Uses the self-made "heavy" test models of the built-in pack {@code resourcepacks/synthetic} of this
 * source set (bushy leaves: cube + 2 tilted planes; cherry leaves: random variants, one without cube faces;
 * grass: cross + horizontal cap plane). Skipped with {@code -Dleaflod.skipSynthetic=true}.
 */
public class LeafLodClientGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("Leaf LOD gametest");
	private static final int TEST_DISTANCE = 16;
	/** Test leaf model: 6 cube faces + 2 planes x 2 faces. */
	private static final int LEAF_FULL = 10;
	private static final int LEAF_CUBE = 6;
	/** Test grass model: cross (4) + cap plane (2); far: 4-quad cross. */
	private static final int GRASS_FULL = 6;
	private static final int GRASS_CROSS = 4;

	@Override
	public void runTest(ClientGameTestContext context) {
		LOGGER.info("[gametest] Sodium loaded: {}, Iris loaded: {}, Mod Menu loaded: {}, Cloth Config loaded: {}",
				FabricLoader.getInstance().isModLoaded("sodium"), FabricLoader.getInstance().isModLoaded("iris"),
				FabricLoader.getInstance().isModLoaded("modmenu"), FabricLoader.getInstance().isModLoaded("cloth-config"));

		if (Boolean.getBoolean("leaflod.skipSynthetic")) {
			LOGGER.info("[gametest] synthetic test skipped (-Dleaflod.skipSynthetic=true)");
			return;
		}

		enableSyntheticPack(context);
		context.runOnClient(mc -> setFixedConfig(true, TEST_DISTANCE, false));

		try (TestSingleplayerContext singleplayer = context.worldBuilder()
				.adjustSettings(creator -> creator.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE))
				.create()) {
			singleplayer.getConnection().waitForChunksRender();

			// ---- 1) Wrapping: leaves and grass wrapped (possibly inside other wrappers, e.g. Continuity), stone not.
			String leafModel = context.computeOnClient(mc -> wrapperChain(model(mc, Blocks.OAK_LEAVES.defaultBlockState())));
			String grassModel = context.computeOnClient(mc -> wrapperChain(model(mc, Blocks.SHORT_GRASS.defaultBlockState())));
			String stoneModel = context.computeOnClient(mc -> wrapperChain(model(mc, Blocks.STONE.defaultBlockState())));
			LOGGER.info("[gametest] Model chains: oak_leaves = {}; short_grass = {}; stone = {}", leafModel, grassModel, stoneModel);
			check(leafModel.contains(LeafLodModel.class.getName()), "oak_leaves model is not wrapped: " + leafModel);
			check(grassModel.contains(GrassLodModel.class.getName()), "short_grass model is not wrapped: " + grassModel);
			check(!stoneModel.contains(LeafLodModel.class.getName()) && !stoneModel.contains(GrassLodModel.class.getName()), "stone model must not be wrapped");

			// ---- 2) Reference position established by the client tick.
			context.waitFor(mc -> LodReference.current().leaves().has() && LodReference.current().grass().has());
			LodReference.Snapshot established = context.computeOnClient(mc -> LodReference.current());
			LOGGER.info("[gametest] Reference snapshot: {}", established);

			// ---- 3) Emitted quads near vs. far (direct calls into the wrapped models through the active renderer).
			int[] leafCounts = context.computeOnClient(mc -> {
				BlockPos near = refPos(LodReference.current().leaves());
				return new int[] {
						countQuads(mc, Blocks.OAK_LEAVES.defaultBlockState(), near),
						countQuads(mc, Blocks.OAK_LEAVES.defaultBlockState(), near.offset(100, 0, 0))};
			});
			LOGGER.info("[gametest] Leaves: emitted quads near={}, far={}", leafCounts[0], leafCounts[1]);
			check(leafCounts[0] == LEAF_FULL, "expected " + LEAF_FULL + " leaf quads near, got " + leafCounts[0]);
			check(leafCounts[1] == LEAF_CUBE, "expected " + LEAF_CUBE + " leaf quads far, got " + leafCounts[1]);

			// ---- 3b) Random variants, one of them without cube faces (cherry leaves of the test pack): far away every
			// variant must emit the shared cube (never nothing), near the pack's own output.
			testVariantsWithoutCube(context);

			// ---- 4) Rows of isolated leaves along +X, camera raised 4 blocks looking along them.
			BlockPos origin = context.computeOnClient(mc -> mc.player.blockPosition());
			int y = origin.getY() + 1;

			// Leaves float one block above the ground (some real packs give their planes cullface "down").
			for (int d = 4; d <= 64; d += 4) {
				for (int dz : new int[] {-2, 2}) {
					setBlock(singleplayer, origin.offset(d, 1, dz), "minecraft:oak_leaves[persistent=true]");
				}
			}

			hideHud(context);
			placeCamera(singleplayer, origin.getX(), origin.getY() + 4, origin.getZ(), -90, 12);
			settle(context, singleplayer);
			screenshot(context, "leaflod_01_leaves_distance16");
			int tracked = context.computeOnClient(mc -> LodController.trackedLeafSections());
			LOGGER.info("[gametest] Tracked leaf sections: {}", tracked);
			check(tracked > 0, "leaf sections should be tracked after meshing");

			// ---- 5) Larger distance -> everything bushy (full reload).
			context.runOnClient(mc -> setFixedConfig(true, 64, true));
			settle(context, singleplayer);
			screenshot(context, "leaflod_02_leaves_distance64");

			// ---- 6) Back to 16, then move the camera 30 blocks: references follow and sections get re-meshed.
			context.runOnClient(mc -> setFixedConfig(true, TEST_DISTANCE, true));
			settle(context, singleplayer);
			int updatesBefore = context.computeOnClient(mc -> LodController.updateCount());
			placeCamera(singleplayer, origin.getX() + 30, origin.getY() + 4, origin.getZ(), -90, 12);
			singleplayer.getConnection().waitForClientboundPackets();
			context.waitFor(mc -> LodController.updateCount() > updatesBefore
					&& Math.abs(LodReference.current().leaves().x() - (origin.getX() + 30.5)) < 4.0, 1200);
			int marked = context.computeOnClient(mc -> LodController.lastMarkedSections());
			LodReference.Snapshot moved = context.computeOnClient(mc -> LodReference.current());
			LOGGER.info("[gametest] After move: {}, sections re-meshed by last update: {}", moved, marked);
			check(marked > 0, "moving the camera should mark leaf sections for re-meshing");
			settle(context, singleplayer);
			screenshot(context, "leaflod_03_moved_30_blocks");
			placeCamera(singleplayer, origin.getX() + 30, origin.getY() + 4, origin.getZ(), 90, 12);
			settle(context, singleplayer);
			screenshot(context, "leaflod_04_moved_looking_back");

			// ---- 7) Disabled -> everything full again.
			context.runOnClient(mc -> setFixedConfig(false, TEST_DISTANCE, true));
			settle(context, singleplayer);
			screenshot(context, "leaflod_05_disabled");
			int disabledFar = context.computeOnClient(mc -> countQuads(mc, Blocks.OAK_LEAVES.defaultBlockState(), mc.player.blockPosition().offset(100, 0, 0)));
			check(disabledFar == LEAF_FULL, "disabled: expected " + LEAF_FULL + " quads far away, got " + disabledFar);

			// ---- 8) Inner leaves: 3x3x3 leaf cube near the camera (camera now at origin + 30).
			context.runOnClient(mc -> setFixedConfig(true, TEST_DISTANCE, true));
			settle(context, singleplayer);
			BlockPos center = origin.offset(30 + 6, 4, -6);
			singleplayer.getServer().runCommand("fill %d %d %d %d %d %d minecraft:oak_leaves[persistent=true]".formatted(
					center.getX() - 1, center.getY() - 1, center.getZ() - 1, center.getX() + 1, center.getY() + 1, center.getZ() + 1));
			singleplayer.getConnection().waitForClientboundPackets();
			context.waitTicks(2);
			BlockPos corner = center.offset(-1, -1, -1);
			check(context.computeOnClient(mc -> LodReference.current().isLeafNear(center)), "test cube must be within the leaf distance");
			int innerCenter = context.computeOnClient(mc -> countQuads(mc, mc.level.getBlockState(center), center));
			int innerCorner = context.computeOnClient(mc -> countQuads(mc, mc.level.getBlockState(corner), corner));
			LOGGER.info("[gametest] Inner leaves: centre of 3x3x3 cube {} quads, corner {} quads", innerCenter, innerCorner);
			check(innerCenter == LEAF_CUBE, "surrounded leaf near the camera should emit only the cube, got " + innerCenter);
			check(innerCorner == LEAF_FULL, "exposed corner leaf should keep the bush planes, got " + innerCorner);
			// Horizontal diagonal neighbour removed: the planes do not reach there -> still inner.
			check(quadsAfterRemoving(context, singleplayer, center, center.offset(1, 0, 1)) == LEAF_CUBE, "horizontal diagonal must not expose the centre leaf");
			// Edge neighbour (+x, +y) removed: the planes reach into it -> exposed.
			check(quadsAfterRemoving(context, singleplayer, center, center.offset(1, 1, 0)) == LEAF_FULL, "edge neighbour (+x+y) must expose the centre leaf");
			// Face neighbour (top) removed -> exposed.
			check(quadsAfterRemoving(context, singleplayer, center, center.above()) == LEAF_FULL, "top neighbour must expose the centre leaf");
			context.runOnClient(mc -> {
				LeafLodConfig.get().outerLeavesOnly = false;
				LodController.applyConfig(true);
			});
			int innerOff = context.computeOnClient(mc -> countQuads(mc, mc.level.getBlockState(center), center));
			check(innerOff == LEAF_FULL, "with 'outer leaves only' off the centre leaf keeps its planes, got " + innerOff);
			context.runOnClient(mc -> {
				LeafLodConfig.get().outerLeavesOnly = true;
				LodController.applyConfig(true);
			});
			settle(context, singleplayer);

			// ---- 8b) Snow on leaves: bush planes clipped at the block top.
			testSnowClip(context, singleplayer, origin);

			// ---- 9) Grass / ferns / bush: near = full model, far = 4-quad cross; double plants switch per lower half.
			context.runOnClient(mc -> {
				LeafLodConfig.get().grassEnabled = true;
				LeafLodConfig.get().grassDistance = TEST_DISTANCE;
				LodController.applyConfig(true);
			});
			settle(context, singleplayer);
			for (Block block : new Block[] {Blocks.SHORT_GRASS, Blocks.FERN, Blocks.BUSH}) {
				int[] grassCounts = context.computeOnClient(mc -> {
					BlockPos near = refPos(LodReference.current().grass());
					return new int[] {countQuads(mc, block.defaultBlockState(), near), countQuads(mc, block.defaultBlockState(), near.offset(100, 0, 0))};
				});
				LOGGER.info("[gametest] {}: emitted quads near={}, far={}", block, grassCounts[0], grassCounts[1]);
				check(grassCounts[0] == GRASS_FULL && grassCounts[1] == GRASS_CROSS, block + ": expected " + GRASS_FULL + "/" + GRASS_CROSS + ", got " + grassCounts[0] + "/" + grassCounts[1]);
			}

			for (Block block : new Block[] {Blocks.TALL_GRASS, Blocks.LARGE_FERN}) {
				BlockState lower = block.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
				BlockState upper = block.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
				int[] halfCounts = context.computeOnClient(mc -> {
					LodReference.Ref grass = LodReference.current().grass();
					// Find an upper-half position that is itself beyond the radius while the block below is within it.
					BlockPos lowerPos = refPos(grass);

					while (grass.isNear(lowerPos.getX(), lowerPos.getY() + 1, lowerPos.getZ())) {
						lowerPos = lowerPos.above();
					}

					// Now lowerPos is within the radius and the block above it is not.
					BlockPos upperPos = lowerPos.above();
					check(grass.isNear(lowerPos.getX(), lowerPos.getY(), lowerPos.getZ()) && !grass.isNear(upperPos.getX(), upperPos.getY(), upperPos.getZ()), "test position search failed");
					BlockPos farLower = lowerPos.offset(100, 0, 0);
					return new int[] {
							countQuads(mc, lower, lowerPos), countQuads(mc, upper, upperPos),
							countQuads(mc, lower, farLower), countQuads(mc, upper, farLower.above())};
				});
				LOGGER.info("[gametest] {}: at the radius boundary lower={} upper={}; far lower={} upper={}", block, halfCounts[0], halfCounts[1], halfCounts[2], halfCounts[3]);
				check(halfCounts[0] == GRASS_FULL && halfCounts[1] == GRASS_FULL, block + ": both halves must be full when the lower half is near");
				check(halfCounts[2] == GRASS_CROSS && halfCounts[3] == GRASS_CROSS, block + ": both halves must be crosses far away");
			}

			// Grass field for the screenshot: short grass on the ground, a few tall grass / ferns.
			singleplayer.getServer().runCommand("fill %d %d %d %d %d %d minecraft:short_grass replace minecraft:air".formatted(
					origin.getX() + 5, origin.getY(), origin.getZ() - 12, origin.getX() + 75, origin.getY(), origin.getZ() + 12));
			for (int d = 10; d <= 70; d += 10) {
				setBlock(singleplayer, origin.offset(d, 0, 9), "minecraft:tall_grass[half=lower]");
				setBlock(singleplayer, origin.offset(d, 1, 9), "minecraft:tall_grass[half=upper]");
				setBlock(singleplayer, origin.offset(d, 0, -9), "minecraft:fern");
			}
			placeCamera(singleplayer, origin.getX() + 30, origin.getY() + 4, origin.getZ(), -90, 25);
			settle(context, singleplayer);
			int trackedGrass = context.computeOnClient(mc -> LodController.trackedGrassSections());
			LOGGER.info("[gametest] Tracked grass sections: {}", trackedGrass);
			check(trackedGrass > 0, "grass sections should be tracked after meshing");
			screenshot(context, "leaflod_06_grass_distance16");
			context.runOnClient(mc -> {
				LeafLodConfig.get().grassEnabled = false;
				LodController.applyConfig(true);
			});
			settle(context, singleplayer);
			screenshot(context, "leaflod_07_grass_disabled");
			int grassOffFar = context.computeOnClient(mc -> countQuads(mc, Blocks.SHORT_GRASS.defaultBlockState(), mc.player.blockPosition().offset(100, 0, 0)));
			check(grassOffFar == GRASS_FULL, "grass LOD off: expected full model far away, got " + grassOffFar);
			context.runOnClient(mc -> {
				LeafLodConfig.get().grassEnabled = true;
				LodController.applyConfig(true);
			});
			// Remove the grass again so the budget test below only sees leaves near the camera.
			singleplayer.getServer().runCommand("fill %d %d %d %d %d %d minecraft:air replace minecraft:short_grass".formatted(
					origin.getX() + 5, origin.getY(), origin.getZ() - 12, origin.getX() + 75, origin.getY(), origin.getZ() + 12));
			settle(context, singleplayer);

			// ---- 9b) Zoom: a real spyglass zoom (vanilla FOV modifier 0.1) as stand-in for Zoomify.
			testZoom(context, singleplayer, origin);

			// ---- 10) BUDGET mode.
			BlockPos scanMin = origin.offset(-20, -3, -20);
			BlockPos scanMax = origin.offset(80, 8, 20);
			// 10a) Few leaves loaded (< budget) -> radius = render distance.
			context.runOnClient(mc -> setBudgetConfig(500, 8, true));
			settle(context, singleplayer);
			long counted = context.computeOnClient(mc -> LeafCounts.total());
			int scanned = context.computeOnClient(mc -> scanLeaves(mc, scanMin, scanMax));
			int renderRadius = context.computeOnClient(LeafLodClientGameTest::maxRadius);
			double radiusFew = context.computeOnClient(mc -> LodController.effectiveRadius());
			LOGGER.info("[gametest] Budget mode, few leaves: counted {} (scan {}), effective radius {} (render distance {} blocks)",
					counted, scanned, radiusFew, renderRadius);
			check(counted == scanned, "leaf count " + counted + " != scanned " + scanned);
			check(radiusFew == renderRadius, "with fewer leaves than the budget the radius should be the render distance");

			// 10b) Dense slab of leaves right below the camera (31 x 2 x 31) -> budget reached close by, radius shrinks.
			int camX = origin.getX() + 30;
			singleplayer.getServer().runCommand("fill %d %d %d %d %d %d minecraft:oak_leaves[persistent=true]"
					.formatted(camX - 15, y, origin.getZ() - 15, camX + 15, y + 1, origin.getZ() + 15));
			singleplayer.getConnection().waitForClientboundPackets();
			context.waitTicks(2);
			long countedDense = context.computeOnClient(mc -> LeafCounts.total());
			int scannedDense = context.computeOnClient(mc -> scanLeaves(mc, scanMin, scanMax));
			LOGGER.info("[gametest] Budget mode, after fill: counted {} (scan {})", countedDense, scannedDense);
			check(countedDense == scannedDense, "leaf count after fill " + countedDense + " != scanned " + scannedDense);
			context.waitFor(mc -> LodController.effectiveRadius() < 24, 1200);
			double radiusDense = context.computeOnClient(mc -> LodController.effectiveRadius());
			double solvedDense = context.computeOnClient(mc -> LodController.lastSolvedRadius());
			LOGGER.info("[gametest] Budget mode, dense: effective radius {} (solved {}), sections re-meshed {}",
					radiusDense, solvedDense, context.computeOnClient(mc -> LodController.lastMarkedSections()));
			check(radiusDense >= 8 && radiusDense < 24, "dense leaves: radius should shrink towards the minimum, got " + radiusDense);
			placeCamera(singleplayer, camX, origin.getY() + 6, origin.getZ(), -90, 35);
			settle(context, singleplayer);
			screenshot(context, "leaflod_08_budget_dense_slab");

			// 10c) Remove the slab again -> counts drop, radius grows back to the render distance.
			singleplayer.getServer().runCommand("fill %d %d %d %d %d %d minecraft:air"
					.formatted(camX - 15, y, origin.getZ() - 15, camX + 15, y + 1, origin.getZ() + 15));
			singleplayer.getConnection().waitForClientboundPackets();
			context.waitTicks(2);
			long countedAfter = context.computeOnClient(mc -> LeafCounts.total());
			int scannedAfter = context.computeOnClient(mc -> scanLeaves(mc, scanMin, scanMax));
			check(countedAfter == scannedAfter, "leaf count after removal " + countedAfter + " != scanned " + scannedAfter);
			context.waitFor(mc -> LodController.effectiveRadius() == maxRadius(mc), 1200);
			LOGGER.info("[gametest] Budget mode, slab removed: counted {} (scan {}), effective radius {}",
					countedAfter, scannedAfter, context.computeOnClient(mc -> LodController.effectiveRadius()));

			// ---- 11) Config screen via Mod Menu entrypoint (Cloth Config), back to defaults. Without Mod Menu / Cloth Config:
			// the config file must still be read.
			context.runOnClient(mc -> {
				LeafLodConfig defaults = new LeafLodConfig();
				LeafLodConfig config = LeafLodConfig.get();
				config.enabled = defaults.enabled;
				config.mode = defaults.mode;
				config.lodDistance = defaults.lodDistance;
				config.leafBudget = defaults.leafBudget;
				config.minRadius = defaults.minRadius;
				config.outerLeavesOnly = defaults.outerLeavesOnly;
				config.grassEnabled = defaults.grassEnabled;
				config.grassDistance = defaults.grassDistance;
				LeafLodConfig.save();
				LodController.applyConfig(false);
			});
			if (FabricLoader.getInstance().isModLoaded("modmenu") && FabricLoader.getInstance().isModLoaded("cloth-config")) {
				ConfigScreenAccess.open(context);
				screenshot(context, "leaflod_09_config_screen");
				context.setScreen(() -> null);
			} else {
				testConfigFileWithoutConfigScreen(context);
			}
		}

		LOGGER.info("[gametest] All Leaf LOD checks passed");
	}

	/**
	 * Snow test (camera at origin + 30): a leaf with a snow layer on top emits no geometry above y = 1 but keeps its bush
	 * below; toggling the option or removing the snow restores the full model; a snow change in the section above a leaf
	 * at the top of its section re-meshes the leaf's section.
	 */
	private static void testSnowClip(ClientGameTestContext context, TestSingleplayerContext singleplayer, BlockPos origin) {
		BlockPos leaf = origin.offset(30 + 4, 3, 6);
		setBlock(singleplayer, leaf, "minecraft:oak_leaves[persistent=true]");
		settle(context, singleplayer);
		check(context.computeOnClient(mc -> LodReference.current().isLeafNear(leaf)), "snow test leaf must be near");
		int[] plain = context.computeOnClient(mc -> quadStats(mc, mc.level.getBlockState(leaf), leaf));
		setBlock(singleplayer, leaf.above(), "minecraft:snow[layers=1]");
		singleplayer.getConnection().waitForClientboundPackets();
		context.waitTicks(2);
		int[] snowed = context.computeOnClient(mc -> quadStats(mc, mc.level.getBlockState(leaf), leaf));
		LOGGER.info("[gametest] Snow: leaf without snow {} quads, y {}..{}; with snow layer {} quads, y {}..{} (x1000)",
				plain[0], plain[2], plain[1], snowed[0], snowed[2], snowed[1]);
		check(plain[1] > 1010, "test leaf model must reach above the block");
		check(snowed[1] <= 1001, "with snow on top nothing may be above y = 1, got " + snowed[1]);
		check(snowed[0] == plain[0] && snowed[2] == plain[2], "with snow the sides/bottom of the bush must stay");
		context.runOnClient(mc -> {
			LeafLodConfig.get().clipUnderSnow = false;
			LodController.applyConfig(true);
		});
		int[] off = context.computeOnClient(mc -> quadStats(mc, mc.level.getBlockState(leaf), leaf));
		check(off[1] > 1010, "option off: the planes must reach above the block again");
		context.runOnClient(mc -> {
			LeafLodConfig.get().clipUnderSnow = true;
			LodController.applyConfig(true);
		});
		setBlock(singleplayer, leaf.above(), "minecraft:air");
		singleplayer.getConnection().waitForClientboundPackets();
		context.waitTicks(2);
		int[] removed = context.computeOnClient(mc -> quadStats(mc, mc.level.getBlockState(leaf), leaf));
		check(removed[1] > 1010 && removed[0] == plain[0], "snow removed: full model again");
		setBlock(singleplayer, leaf, "minecraft:air");

		// Leaf at the top of its chunk section, snow placed in the section above.
		int topY = (origin.getY() + 2) | 15;
		BlockPos border = new BlockPos(origin.getX() + 30 + 3, topY, origin.getZ() + 9);
		setBlock(singleplayer, border, "minecraft:oak_leaves[persistent=true]");
		settle(context, singleplayer);
		check(context.computeOnClient(mc -> LodReference.current().isLeafNear(border)), "section-border test leaf must be near");
		int meshesBefore = LodDebugAccess.count(border);
		setBlock(singleplayer, border.above(), "minecraft:snow[layers=2]");
		settle(context, singleplayer);
		int meshesAfter = LodDebugAccess.count(border);
		int[] borderSnow = context.computeOnClient(mc -> quadStats(mc, mc.level.getBlockState(border), border));
		// (quadStats itself also counts as an emission - re-read before the next step)
		int meshesBeforeRemoval = LodDebugAccess.count(border);
		setBlock(singleplayer, border.above(), "minecraft:air");
		settle(context, singleplayer);
		int meshesRemoved = LodDebugAccess.count(border);
		LOGGER.info("[gametest] Snow at a section border (leaf y={}, snow y={}): leaf section emissions {} -> {} (snow placed), {} -> {} (snow removed); clipped max y {}",
				topY, topY + 1, meshesBefore, meshesAfter, meshesBeforeRemoval, meshesRemoved, borderSnow[1]);
		check(meshesAfter > meshesBefore, "placing snow in the section above must re-mesh the leaf's section");
		check(meshesRemoved > meshesBeforeRemoval, "removing the snow must re-mesh the leaf's section again");
		check(borderSnow[1] <= 1001, "section-border leaf must be clipped");
		setBlock(singleplayer, border, "minecraft:air");

		// Screenshot: a snowy 5x5 leaf patch, clipping off vs on.
		singleplayer.getServer().runCommand("fill %d %d %d %d %d %d minecraft:spruce_leaves[persistent=true]".formatted(
				origin.getX() + 34, origin.getY() + 2, origin.getZ() + 3, origin.getX() + 38, origin.getY() + 2, origin.getZ() + 7));
		singleplayer.getServer().runCommand("fill %d %d %d %d %d %d minecraft:snow[layers=1]".formatted(
				origin.getX() + 34, origin.getY() + 3, origin.getZ() + 3, origin.getX() + 38, origin.getY() + 3, origin.getZ() + 7));
		placeCamera(singleplayer, origin.getX() + 30, origin.getY() + 5, origin.getZ(), -45, 25);

		for (boolean clip : new boolean[] {false, true}) {
			context.runOnClient(mc -> {
				LeafLodConfig.get().clipUnderSnow = clip;
				LodController.applyConfig(true);
			});
			settle(context, singleplayer);
			screenshot(context, clip ? "leaflod_08b_snow_clipped" : "leaflod_08a_snow_not_clipped");
		}

		singleplayer.getServer().runCommand("fill %d %d %d %d %d %d minecraft:air".formatted(
				origin.getX() + 34, origin.getY() + 2, origin.getZ() + 3, origin.getX() + 38, origin.getY() + 3, origin.getZ() + 7));
		placeCamera(singleplayer, origin.getX() + 30, origin.getY() + 4, origin.getZ(), -90, 12);
		settle(context, singleplayer);
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

	/** Reads the debug mesh counter (thread-safe map, fine to read from the test thread). */
	private static final class LodDebugAccess {
		static int count(BlockPos pos) {
			check(de.niclas.leaflod.lod.LodDebug.ENABLED, "run with -Dleaflod.debug=true");
			return de.niclas.leaflod.lod.LodDebug.leafMeshCount(pos);
		}
	}

	/**
	 * Zoom test: camera at the origin looking along +X (the leaf rows); zooms with a spyglass.
	 * Checks that leaves/grass inside the view cone beyond the normal radius get the full model, outside the cone not,
	 * and that the extension is kept for the grace period and then removed.
	 */
	private static void testZoom(ClientGameTestContext context, TestSingleplayerContext singleplayer, BlockPos origin) {
		context.runOnClient(mc -> {
			setFixedConfig(true, TEST_DISTANCE, true);
			LeafLodConfig.get().zoomMaxDistance = 256;
			LeafLodConfig.get().zoomGraceMillis = 1000;
		});
		placeCamera(singleplayer, origin.getX(), origin.getY() + 4, origin.getZ(), -90, 6);
		// One leaf beside the view direction (outside the cone) at the same distance as the in-cone test leaf.
		BlockPos inCone = origin.offset(40, 1, 2);
		BlockPos outsideCone = origin.offset(0, 1, 40);
		setBlock(singleplayer, outsideCone, "minecraft:oak_leaves[persistent=true]");
		singleplayer.getServer().runCommand("item replace entity @p weapon.mainhand with minecraft:spyglass");
		settle(context, singleplayer);
		check(context.computeOnClient(mc -> mc.level.getBlockState(inCone).is(Blocks.OAK_LEAVES)), "in-cone test leaf missing");
		check(context.computeOnClient(mc -> !LodReference.current().isLeafNear(inCone)), "test leaf must be beyond the normal radius");
		screenshot(context, "leaflod_10_zoom_before");

		// a) Zoom with the extension switched off: nothing changes.
		context.runOnClient(mc -> LeafLodConfig.get().zoomEnabled = false);
		context.getInput().holdKey(options -> options.keyUse);
		context.waitFor(mc -> LodController.zoomFactor() >= 4.0, 200);
		settle(context, singleplayer);
		double factor = context.computeOnClient(mc -> LodController.zoomFactor());
		int offInCone = context.computeOnClient(mc -> countQuads(mc, mc.level.getBlockState(inCone), inCone));
		LOGGER.info("[gametest] Zoom (extension off): factor {}, in-cone leaf {} quads", String.format("%.2f", factor), offInCone);
		check(!context.computeOnClient(mc -> LodController.zoomCone().active()), "no cone when the zoom extension is disabled");
		check(offInCone == LEAF_CUBE, "zoom extension off: far leaf must stay a cube");
		screenshot(context, "leaflod_11_zoomed_extension_off");
		context.getInput().releaseKey(options -> options.keyUse);
		context.waitFor(mc -> LodController.zoomFactor() < 1.5, 200);

		// b) Zoom with the extension on.
		context.runOnClient(mc -> LeafLodConfig.get().zoomEnabled = true);
		context.getInput().holdKey(options -> options.keyUse);
		context.waitFor(mc -> LodController.zoomCone().active() && LodController.zoomFactor() >= 4.0, 200);
		context.waitFor(mc -> LodController.zoomCone().leafRadius() == Math.min(256, maxRadius(mc)) - Math.min(256, maxRadius(mc)) % 16, 200);
		settle(context, singleplayer);
		LodReference.Cone cone = context.computeOnClient(mc -> LodController.zoomCone());
		int marked = context.computeOnClient(mc -> LodController.lastMarkedSections());
		LOGGER.info("[gametest] Zoom (extension on): factor {}, cone half-angle {} deg, leaf radius {}, grass radius {}, sections re-meshed by last update {}",
				String.format("%.2f", cone.zoomFactor()), String.format("%.1f", cone.halfAngleDegrees()), cone.leafRadius(), cone.grassRadius(), marked);
		int[] zoomed = context.computeOnClient(mc -> new int[] {
				countQuads(mc, mc.level.getBlockState(inCone), inCone),
				countQuads(mc, mc.level.getBlockState(outsideCone), outsideCone),
				countQuads(mc, Blocks.OAK_LEAVES.defaultBlockState(), origin.offset(Math.min(250, maxRadius(mc) + 10), 1, 0)),
				countQuads(mc, Blocks.SHORT_GRASS.defaultBlockState(), origin.offset(40, 0, 0)),
				countQuads(mc, Blocks.SHORT_GRASS.defaultBlockState(), origin.offset(0, 0, 40))});
		LOGGER.info("[gametest] Zoomed: leaf in cone {} quads, leaf beside the view {} quads, leaf in view beyond the zoom radius {} quads, grass in cone {} / beside {}",
				zoomed[0], zoomed[1], zoomed[2], zoomed[3], zoomed[4]);
		check(zoomed[0] == LEAF_FULL, "zoomed: leaf inside the view cone must get the full model, got " + zoomed[0]);
		check(zoomed[1] == LEAF_CUBE, "zoomed: leaf outside the view cone must stay a cube, got " + zoomed[1]);
		check(zoomed[2] == LEAF_CUBE, "zoomed: leaf beyond the zoom radius must stay a cube, got " + zoomed[2]);
		check(zoomed[3] == GRASS_FULL, "zoomed: grass inside the cone must get the full model, got " + zoomed[3]);
		check(zoomed[4] == GRASS_CROSS, "zoomed: grass outside the cone must stay a cross, got " + zoomed[4]);
		screenshot(context, "leaflod_12_zoomed_extension_on");

		// c) Zoom out: kept during the grace period, then removed.
		context.getInput().releaseKey(options -> options.keyUse);
		context.waitFor(mc -> LodController.zoomFactor() < 1.5, 200);
		long released = System.nanoTime();
		check(context.computeOnClient(mc -> LodController.zoomCone().active()), "cone must be kept right after zooming out (grace period)");
		check(context.computeOnClient(mc -> countQuads(mc, mc.level.getBlockState(inCone), inCone)) == LEAF_FULL, "in-cone leaf must stay full during the grace period");
		context.waitFor(mc -> !LodController.zoomCone().active(), 400);
		double graceSeconds = (System.nanoTime() - released) / 1.0e9;
		int after = context.computeOnClient(mc -> countQuads(mc, mc.level.getBlockState(inCone), inCone));
		LOGGER.info("[gametest] Zoom released: cone removed after {} s (grace 1.0 s), in-cone leaf now {} quads", String.format("%.2f", graceSeconds), after);
		check(graceSeconds >= 0.8, "cone removed too early: " + graceSeconds + " s");
		check(after == LEAF_CUBE, "after the grace period the far leaf must be a cube again, got " + after);
		settle(context, singleplayer);
		screenshot(context, "leaflod_13_zoom_released");

		setBlock(singleplayer, outsideCone, "minecraft:air");
		singleplayer.getServer().runCommand("item replace entity @p weapon.mainhand with minecraft:air");
		// Back to the camera position the following budget test expects (above the slab area).
		placeCamera(singleplayer, origin.getX() + 30, origin.getY() + 4, origin.getZ(), -90, 25);
		settle(context, singleplayer);
	}

	/** Removes {@code neighbour}, counts the quads of the leaf at {@code center}, then puts the leaf back. */
	private static int quadsAfterRemoving(ClientGameTestContext context, TestSingleplayerContext singleplayer, BlockPos center, BlockPos neighbour) {
		setBlock(singleplayer, neighbour, "minecraft:air");
		singleplayer.getConnection().waitForClientboundPackets();
		context.waitTicks(1);
		int quads = context.computeOnClient(mc -> countQuads(mc, mc.level.getBlockState(center), center));
		LOGGER.info("[gametest] Inner leaves: neighbour {} removed -> centre leaf emits {} quads", neighbour.subtract(center), quads);
		setBlock(singleplayer, neighbour, "minecraft:oak_leaves[persistent=true]");
		singleplayer.getConnection().waitForClientboundPackets();
		context.waitTicks(1);
		return quads;
	}

	private static void setBlock(TestSingleplayerContext singleplayer, BlockPos pos, String block) {
		singleplayer.getServer().runCommand("setblock %d %d %d %s".formatted(pos.getX(), pos.getY(), pos.getZ(), block));
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

	/** Teleports the player onto an invisible barrier block so the camera stays put (no falling between screenshots). */
	private static void placeCamera(TestSingleplayerContext singleplayer, int x, int feetY, int z, int yaw, int pitch) {
		singleplayer.getServer().runCommand("setblock %d %d %d minecraft:barrier".formatted(x, feetY - 1, z));
		singleplayer.getServer().runCommand("tp @p %d.5 %d %d.5 %d %d".formatted(x, feetY, z, yaw, pitch));
	}

	/** FIXED mode with the given distance. */
	private static void setFixedConfig(boolean enabled, int distance, boolean reload) {
		LeafLodConfig config = LeafLodConfig.get();
		config.enabled = enabled;
		config.mode = LeafLodConfig.Mode.FIXED;
		config.lodDistance = distance;
		config.grassDistance = TEST_DISTANCE;
		LeafLodConfig.save();
		LodController.applyConfig(reload);
	}

	/** BUDGET mode with the given budget / minimum radius. */
	private static void setBudgetConfig(int budget, int minRadius, boolean reload) {
		LeafLodConfig config = LeafLodConfig.get();
		config.enabled = true;
		config.mode = LeafLodConfig.Mode.BUDGET;
		config.leafBudget = budget;
		config.minRadius = minRadius;
		LeafLodConfig.save();
		LodController.applyConfig(reload);
	}

	private static BlockPos refPos(LodReference.Ref ref) {
		return BlockPos.containing(ref.x(), ref.y(), ref.z());
	}

	/** Brute-force count of leaf blocks in a box of the client level (reference value for LeafCounts). */
	private static int scanLeaves(Minecraft mc, BlockPos min, BlockPos max) {
		int n = 0;

		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			if (LeafCounts.isLeaves(mc.level.getBlockState(pos))) {
				n++;
			}
		}

		return n;
	}

	/** "Outer -> ... -> inner" class names of a model wrapped with Fabric's WrapperBlockStateModel (test-only reflection). */
	private static String wrapperChain(BlockStateModel model) {
		StringBuilder chain = new StringBuilder(model.getClass().getName());

		try {
			java.lang.reflect.Field field = WrapperBlockStateModel.class.getDeclaredField("wrapped");
			field.setAccessible(true);

			while (model instanceof WrapperBlockStateModel) {
				model = (BlockStateModel) field.get(model);
				chain.append(" -> ").append(model.getClass().getName());
			}
		} catch (ReflectiveOperationException e) {
			chain.append(" -> ?(").append(e).append(')');
		}

		return chain.toString();
	}

	private static int maxRadius(Minecraft mc) {
		return mc.options.getEffectiveRenderDistance() * 16;
	}

	private static BlockStateModel model(Minecraft mc, BlockState state) {
		return mc.getModelManager().getBlockStateModelSet().get(state);
	}

	/** Number of quads the (outermost) model of {@code state} emits at {@code pos}, with the current client level as context. */
	private static int countQuads(Minecraft mc, BlockState state, BlockPos pos) {
		int[] count = {0};
		QuadEmitter emitter = Renderer.get().quadEmitter(quad -> count[0]++);
		model(mc, state).emitQuads(emitter, mc.level, pos, state, RandomSource.create(42L), direction -> false);
		return count[0];
	}

	private static void screenshot(ClientGameTestContext context, String name) {
		Path path = context.takeScreenshot(name);
		LOGGER.info("[gametest] Screenshot: {}", path.toAbsolutePath());
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	/** Mod Menu / Cloth Config classes are only touched when both are loaded. */
	private static final class ConfigScreenAccess {
		static void open(ClientGameTestContext context) {
			context.setScreen(() -> new ModMenuIntegration().getModConfigScreenFactory().create(null));
			context.waitTicks(5);
		}
	}

	/**
	 * Without Mod Menu / Cloth Config: edits {@code config/leaflod.json} on disk and re-reads it through the same code path
	 * the mod uses at start-up.
	 */
	private static void testConfigFileWithoutConfigScreen(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			java.nio.file.Path file = LeafLodConfig.path();
			check(java.nio.file.Files.exists(file), "config file must exist without Mod Menu: " + file);

			try {
				String json = java.nio.file.Files.readString(file);
				check(json.contains("\"leafBudget\": 8000"), "config file must contain the default budget: " + json);
				java.nio.file.Files.writeString(file, json.replace("\"leafBudget\": 8000", "\"leafBudget\": 6500").replace("\"grassDistance\": 24", "\"grassDistance\": 40"));
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}

			LeafLodConfig.load();
			check(LeafLodConfig.get().leafBudget == 6500 && LeafLodConfig.get().grassDistance == 40,
					"edited config file must be read: budget " + LeafLodConfig.get().leafBudget + ", grass " + LeafLodConfig.get().grassDistance);
			LOGGER.info("[gametest] Without Mod Menu/Cloth Config: config file {} read (budget {}, grass distance {})",
					file, LeafLodConfig.get().leafBudget, LeafLodConfig.get().grassDistance);
			LeafLodConfig.get().leafBudget = LeafLodConfig.DEFAULT_BUDGET;
			LeafLodConfig.get().grassDistance = LeafLodConfig.DEFAULT_GRASS_DISTANCE;
			LeafLodConfig.save();
			LodController.applyConfig(false);
		});
	}

	/** Makes sure the built-in test pack with the synthetic models is active (it is enabled by default). */
	private static void enableSyntheticPack(ClientGameTestContext context) {
		String id = SyntheticPackRegistration.PACK.toString();
		boolean selected = context.computeOnClient(mc -> mc.getResourcePackRepository().getSelectedIds().contains(id));
		LOGGER.info("[gametest] Synthetic test pack {} selected at start: {}", id, selected);

		if (!selected) {
			java.util.concurrent.CompletableFuture<Void> reload = context.computeOnClient(mc -> {
				check(mc.getResourcePackRepository().getAvailableIds().contains(id), "synthetic test pack missing: " + mc.getResourcePackRepository().getAvailableIds());
				mc.getResourcePackRepository().addPack(id);
				return mc.reloadResourcePacks();
			});
			context.waitFor(mc -> reload.isDone(), 6000);
			context.waitFor(mc -> mc.gui.overlay() == null, 6000);
		}
	}

	/**
	 * Cherry leaves of the test pack: variant 1 = cube + planes, variants 2/3 = planes only (no cube faces), variant 4 = a box
	 * one pixel narrower than the block (incomplete cube: 5 boundary faces + 1 inner face).
	 */
	private static void testVariantsWithoutCube(ClientGameTestContext context) {
		int[] result = context.computeOnClient(mc -> {
			BlockState state = Blocks.CHERRY_LEAVES.defaultBlockState();
			BlockStateModel outer = model(mc, state);
			check(wrapperChain(outer).contains(LeafLodModel.class.getName()), "cherry leaves must be wrapped: " + wrapperChain(outer));
			LeafLodModel lod = findLeafLod(outer);
			check(lod.usesSharedFarCube(), "cherry leaves (variants without cube) must use the shared far cube");
			BlockPos near = refPos(LodReference.current().leaves());
			BlockPos far = near.offset(100, 0, 0);
			int minFar = Integer.MAX_VALUE, maxFar = 0, mismatches = 0, planesOnlyVariants = 0, shortBoxVariants = 0;

			for (long seed = 0; seed < 24; seed++) {
				int pack = countQuads(mc, lod.wrapped(), state, near, seed);
				int nearCount = countQuads(mc, outer, state, near, seed);
				int farCount = countQuads(mc, outer, state, far, seed);
				minFar = Math.min(minFar, farCount);
				maxFar = Math.max(maxFar, farCount);

				if (pack != nearCount) {
					mismatches++;
				}

				if (pack == 4) {
					planesOnlyVariants++;
				} else if (pack == 6) {
					shortBoxVariants++;
				}
			}

			return new int[] {minFar, maxFar, mismatches, planesOnlyVariants, shortBoxVariants};
		});
		LOGGER.info("[gametest] Variants without (complete) cube (cherry, 24 seeds): far {}..{} quads, near/pack mismatches {}, planes-only variants picked {}, short-box variants picked {}",
				result[0], result[1], result[2], result[3], result[4]);
		check(result[0] == LEAF_CUBE && result[1] == LEAF_CUBE, "far away every variant must emit the shared 6-quad cube, got " + result[0] + ".." + result[1]);
		check(result[2] == 0, "near output must equal the pack's output for every variant");
		check(result[3] > 0 && result[4] > 0, "test seeds should pick the planes-only and the short-box variant at least once");
	}

	private static LeafLodModel findLeafLod(BlockStateModel model) {
		try {
			java.lang.reflect.Field field = WrapperBlockStateModel.class.getDeclaredField("wrapped");
			field.setAccessible(true);

			while (!(model instanceof LeafLodModel)) {
				model = (BlockStateModel) field.get(model);
			}

			return (LeafLodModel) model;
		} catch (ReflectiveOperationException | ClassCastException e) {
			throw new AssertionError(e);
		}
	}

	private static int countQuads(Minecraft mc, BlockStateModel model, BlockState state, BlockPos pos, long seed) {
		int[] count = {0};
		QuadEmitter emitter = Renderer.get().quadEmitter(quad -> count[0]++);
		// Mixed seed: small sequential seeds give correlated first values (same random variant every time).
		model.emitQuads(emitter, mc.level, pos, state, RandomSource.create(seed * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L), direction -> false);
		return count[0];
	}
}
