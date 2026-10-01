package de.niclas.leaflod.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.joml.Vector3fc;

import de.niclas.leaflod.LeafLodClient;
import de.niclas.leaflod.mixin.WeightedVariantsAccessor;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Wraps the baked block-state models of
 * <ul>
 *     <li>all {@link LeavesBlock} states whose model contains both plain cube faces and additional geometry
 *     (e.g. the rotated planes of "bushy leaves" packs) - see {@link LeafLodModel}. Every random variant is analysed;
 *     if some variants have no complete cube of their own, all variants get the cube of one complete variant as their
 *     reduced form;</li>
 *     <li>short grass, fern, tall grass, large fern and bush, if their model is heavier than the vanilla
 *     X-cross (more than 4 quads) - see {@link GrassLodModel}.</li>
 * </ul>
 * Models without such extra geometry (e.g. without the resource pack) are left untouched (zero overhead).
 */
public final class LodModelPlugin implements ModelLoadingPlugin {
	/** Random seeds sampled when analysing a model that is neither a single variant nor a list of random variants. */
	private static final int ANALYSIS_SEEDS = 64;
	private static final Direction[] DIRECTIONS_AND_NULL = {
			Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null
	};
	private static final float CELL_EPS = 0.01f;
	private static final int VANILLA_CROSS_QUADS = 4;

	private static final Set<Block> GRASS_BLOCKS = Set.of(Blocks.SHORT_GRASS, Blocks.FERN, Blocks.TALL_GRASS, Blocks.LARGE_FERN, Blocks.BUSH);

	private static final AtomicInteger LEAVES_WRAPPED = new AtomicInteger();
	private static final AtomicInteger LEAVES_SKIPPED = new AtomicInteger();
	private static final AtomicInteger GRASS_WRAPPED = new AtomicInteger();
	private static final AtomicInteger GRASS_SKIPPED = new AtomicInteger();
	private static volatile boolean summaryPending;

	@Override
	public void initialize(Context pluginContext) {
		// Called at the start of every model (re)load.
		LEAVES_WRAPPED.set(0);
		LEAVES_SKIPPED.set(0);
		GRASS_WRAPPED.set(0);
		GRASS_SKIPPED.set(0);
		summaryPending = true;
		pluginContext.modifyBlockModelAfterBake().register(ModelModifier.WRAP_PHASE, LodModelPlugin::modify);
	}

	private static BlockStateModel modify(BlockStateModel model, ModelModifier.AfterBakeBlock.Context context) {
		BlockState state = context.state();
		Block block = state.getBlock();

		if (model instanceof LeafLodModel || model instanceof GrassLodModel) {
			return model;
		}

		try {
			if (block instanceof LeavesBlock) {
				return wrapLeaves(model, state);
			}

			if (GRASS_BLOCKS.contains(block)) {
				return wrapGrass(model, state);
			}
		} catch (RuntimeException e) {
			LeafLodClient.LOGGER.warn("[Leaf LOD] Could not analyse model of {}, leaving it unchanged", state, e);
		}

		return model;
	}

	// ---------------------------------------------------------------- leaves

	private static BlockStateModel wrapLeaves(BlockStateModel model, BlockState state) {
		List<List<BlockStateModelPart>> variants = new ArrayList<>();
		collectVariants(model, variants, 0);
		int cubeQuads = 0;
		int incompleteVariants = 0;
		int variantsWithoutCube = 0;
		List<BakedQuad> extra = new ArrayList<>();
		List<BlockStateModelPart> donorParts = null;
		int donorCubeQuads = 0;
		boolean donorComplete = false;

		for (List<BlockStateModelPart> parts : variants) {
			int cube = 0;
			int extraBefore = extra.size();
			float[] faceArea = new float[6];

			for (BlockStateModelPart part : parts) {
				for (Direction direction : DIRECTIONS_AND_NULL) {
					for (BakedQuad quad : part.getQuads(direction)) {
						if (CubeFaceFilter.isCubeFace(quad)) {
							cube++;
							addFaceArea(quad, faceArea);
						} else {
							extra.add(quad);
						}
					}
				}
			}

			cubeQuads += cube;
			boolean complete = isCompleteCube(faceArea);

			if (!complete && extra.size() > extraBefore) {
				incompleteVariants++;

				if (cube == 0) {
					variantsWithoutCube++;
				}
			}

			// Donor for a shared far cube: prefer variants whose cube covers all six faces, then the most cube quads.
			if (cube > 0 && (complete && !donorComplete || complete == donorComplete && cube > donorCubeQuads)) {
				donorCubeQuads = cube;
				donorParts = parts;
				donorComplete = complete;
			}
		}

		if (cubeQuads == 0 || extra.isEmpty()) {
			// Plain cube (nothing to reduce) or no cube at all (reducing would leave holes): leave untouched.
			LEAVES_SKIPPED.incrementAndGet();
			return model;
		}

		// Some random variants have no complete cube of their own (only rotated boxes, or a box one pixel short of the
		// block): filtering them would leave holes far away. Then the reduced form of every variant is the cube of the
		// variant with the most complete cube instead.
		BlockStateModel farCube = null;

		if (variantsWithoutCube > 0 || incompleteVariants > 0 && donorComplete) {
			if (donorParts.size() != 1) {
				LEAVES_SKIPPED.incrementAndGet();
				logDefaultState(state, "[Leaf LOD] Not wrapping {}: {} of {} variants have no complete cube and no single-part variant can supply one",
						BuiltInRegistries.BLOCK.getKey(state.getBlock()), incompleteVariants, variants.size());
				return model;
			}

			farCube = new SingleVariant(donorParts.getFirst());
		}

		int[] offsets = exposureOffsets(extra);
		boolean reachesAbove = reachesAbove(extra);
		LEAVES_WRAPPED.incrementAndGet();
		logDefaultState(state, "[Leaf LOD] Wrapping {} ({} variants; per variant: {} cube quads kept far away, {} extra quads near only; {}exposure check: {}; reaches above the block: {})",
				BuiltInRegistries.BLOCK.getKey(state.getBlock()), variants.size(),
				String.format(Locale.ROOT, "%.1f", cubeQuads / (double) variants.size()), String.format(Locale.ROOT, "%.1f", extra.size() / (double) variants.size()),
				farCube == null ? "" : incompleteVariants + " variants without a complete cube (" + variantsWithoutCube
						+ " without any cube face) use the cube of another variant far away; ",
				offsets == null ? "off (geometry reaches beyond direct neighbours)" : offsets.length + " neighbours", reachesAbove);

		return new LeafLodModel(model, offsets, reachesAbove, farCube);
	}

	/** Adds the area of a cube-face quad to the face it lies on (0/1 = x = 0/1, 2/3 = y, 4/5 = z). */
	private static void addFaceArea(BakedQuad quad, float[] faceArea) {
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

		for (int i = 0; i < 4; i++) {
			Vector3fc p = quad.position(i);
			minX = Math.min(minX, p.x());
			minY = Math.min(minY, p.y());
			minZ = Math.min(minZ, p.z());
			maxX = Math.max(maxX, p.x());
			maxY = Math.max(maxY, p.y());
			maxZ = Math.max(maxZ, p.z());
		}

		float dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;

		if (dx <= CELL_EPS) {
			faceArea[minX < 0.5f ? 0 : 1] += dy * dz;
		} else if (dy <= CELL_EPS) {
			faceArea[minY < 0.5f ? 2 : 3] += dx * dz;
		} else if (dz <= CELL_EPS) {
			faceArea[minZ < 0.5f ? 4 : 5] += dx * dy;
		}
	}

	/** All six faces of the unit cube covered (a 1-pixel gap = 1/16 of a face is not). */
	private static boolean isCompleteCube(float[] faceArea) {
		for (float area : faceArea) {
			if (area < 0.99f) {
				return false;
			}
		}

		return true;
	}

	private static void logDefaultState(BlockState state, String message, Object... args) {
		if (state == state.getBlock().defaultBlockState()) {
			LeafLodClient.LOGGER.info(message, args);
		}
	}

	/**
	 * Adds the part lists of every variant of {@code model}: random variants ({@link WeightedVariants}) are enumerated
	 * exactly; any other model type is sampled with {@link #ANALYSIS_SEEDS} random seeds (duplicates removed).
	 */
	private static void collectVariants(BlockStateModel model, List<List<BlockStateModelPart>> output, int depth) {
		if (model instanceof WeightedVariants && depth < 8) {
			for (Weighted<BlockStateModel> entry : ((WeightedVariantsAccessor) model).leaflod$getList().unwrap()) {
				collectVariants(entry.value(), output, depth + 1);
			}

			return;
		}

		RandomSource random = RandomSource.create(0L);
		int seeds = model instanceof SingleVariant ? 1 : ANALYSIS_SEEDS;

		for (int seed = 0; seed < seeds; seed++) {
			List<BlockStateModelPart> parts = new ArrayList<>();
			random.setSeed(seed);
			model.collectParts(random, parts);

			if (!containsSameParts(output, parts)) {
				output.add(parts);
			}
		}
	}

	private static boolean containsSameParts(List<List<BlockStateModelPart>> lists, List<BlockStateModelPart> parts) {
		outer:
		for (List<BlockStateModelPart> list : lists) {
			if (list.size() != parts.size()) {
				continue;
			}

			for (int i = 0; i < list.size(); i++) {
				if (list.get(i) != parts.get(i)) {
					continue outer;
				}
			}

			return true;
		}

		return false;
	}

	/**
	 * The 6 face neighbours plus every neighbour cell the extra quads' bounding boxes reach into, face neighbours
	 * first. {@code null} if any reaches beyond the 3x3x3 neighbourhood.
	 */
	private static int[] exposureOffsets(List<BakedQuad> extra) {
		Set<Integer> cells = new TreeSet<>((a, b) -> {
			int byKind = Integer.compare(manhattan(a), manhattan(b));
			return byKind != 0 ? byKind : Integer.compare(a, b);
		});

		// The 6 face neighbours always count: a leaf with an open face is exposed even if its extra geometry
		// stays inside the block.
		for (Direction direction : Direction.values()) {
			cells.add(LeafLodModel.pack(direction.getStepX(), direction.getStepY(), direction.getStepZ()));
		}

		for (BakedQuad quad : extra) {
			float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
			float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

			for (int i = 0; i < 4; i++) {
				Vector3fc p = quad.position(i);
				minX = Math.min(minX, p.x());
				minY = Math.min(minY, p.y());
				minZ = Math.min(minZ, p.z());
				maxX = Math.max(maxX, p.x());
				maxY = Math.max(maxY, p.y());
				maxZ = Math.max(maxZ, p.z());
			}

			int x0 = cell(minX + CELL_EPS), x1 = cell(maxX - CELL_EPS);
			int y0 = cell(minY + CELL_EPS), y1 = cell(maxY - CELL_EPS);
			int z0 = cell(minZ + CELL_EPS), z1 = cell(maxZ - CELL_EPS);

			if (x0 < -1 || y0 < -1 || z0 < -1 || x1 > 1 || y1 > 1 || z1 > 1) {
				return null;
			}

			for (int x = x0; x <= x1; x++) {
				for (int y = y0; y <= y1; y++) {
					for (int z = z0; z <= z1; z++) {
						if (x != 0 || y != 0 || z != 0) {
							cells.add(LeafLodModel.pack(x, y, z));
						}
					}
				}
			}
		}

		return cells.stream().mapToInt(Integer::intValue).toArray();
	}

	/** Whether any extra quad reaches above the block top (y > 1), i.e. could poke through snow lying on the leaf. */
	private static boolean reachesAbove(List<BakedQuad> extra) {
		for (BakedQuad quad : extra) {
			for (int i = 0; i < 4; i++) {
				if (quad.position(i).y() > 1.0f + CELL_EPS) {
					return true;
				}
			}
		}

		return false;
	}

	private static int cell(float v) {
		return (int) Math.floor(v);
	}

	private static int manhattan(int packed) {
		return Math.abs(((packed >> 4) & 3) - 1) + Math.abs(((packed >> 2) & 3) - 1) + Math.abs((packed & 3) - 1);
	}

	// ---------------------------------------------------------------- grass

	private static BlockStateModel wrapGrass(BlockStateModel model, BlockState state) {
		boolean[] ambientOcclusion = {true};
		List<BakedQuad> quads = sampleQuads(model, 1, ambientOcclusion);

		if (quads.size() <= VANILLA_CROSS_QUADS) {
			GRASS_SKIPPED.incrementAndGet();
			return model;
		}

		BakedQuad source = null;

		for (BakedQuad quad : quads) {
			if (area(quad) > 1.0e-4f) {
				source = quad;
				break;
			}
		}

		if (source == null) {
			GRASS_SKIPPED.incrementAndGet();
			return model;
		}

		FarCross farCross = new FarCross(source, ambientOcclusion[0]);
		boolean upperHalf = state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
				&& state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
		GRASS_WRAPPED.incrementAndGet();

		if (state == state.getBlock().defaultBlockState() || upperHalf && state == state.getBlock().defaultBlockState()
				.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)) {
			LeafLodClient.LOGGER.info("[Leaf LOD] Wrapping {}{} ({} quads near, 4-quad cross far away, sprite {})",
					BuiltInRegistries.BLOCK.getKey(state.getBlock()), upperHalf ? " (upper half)" : "",
					quads.size(), source.materialInfo().sprite().contents().name());
		}

		return new GrassLodModel(model, farCross, upperHalf);
	}

	private static float area(BakedQuad quad) {
		Vector3fc a = quad.position0();
		Vector3fc b = quad.position1();
		Vector3fc c = quad.position2();
		// |(b - a) x (c - a)| is twice the area of triangle abc; enough to detect degenerate (zero-area) faces.
		float ux = b.x() - a.x(), uy = b.y() - a.y(), uz = b.z() - a.z();
		float vx = c.x() - a.x(), vy = c.y() - a.y(), vz = c.z() - a.z();
		float cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
		return (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
	}

	// ---------------------------------------------------------------- common

	/**
	 * All baked quads of the model's parts for a few random seeds.
	 *
	 * @param ambientOcclusion if not null, [0] is set to false if any part disables ambient occlusion
	 */
	private static List<BakedQuad> sampleQuads(BlockStateModel model, int seeds, boolean[] ambientOcclusion) {
		List<BakedQuad> quads = new ArrayList<>();
		List<BlockStateModelPart> parts = new ArrayList<>();
		RandomSource random = RandomSource.create(0L);

		for (int seed = 0; seed < seeds; seed++) {
			parts.clear();
			random.setSeed(seed);
			model.collectParts(random, parts);

			for (BlockStateModelPart part : parts) {
				if (ambientOcclusion != null && !part.useAmbientOcclusion()) {
					ambientOcclusion[0] = false;
				}

				for (Direction direction : DIRECTIONS_AND_NULL) {
					quads.addAll(part.getQuads(direction));
				}
			}
		}

		return quads;
	}

	/** Logs a one-line summary once after a model reload has finished (called from the client tick). */
	public static void logSummaryIfPending() {
		if (summaryPending) {
			summaryPending = false;
			LeafLodClient.LOGGER.info("[Leaf LOD] Model reload: leaves {} states wrapped / {} unchanged; grass & ferns {} states wrapped / {} unchanged",
					LEAVES_WRAPPED.get(), LEAVES_SKIPPED.get(), GRASS_WRAPPED.get(), GRASS_SKIPPED.get());
		}
	}
}
