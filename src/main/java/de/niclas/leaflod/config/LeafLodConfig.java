package de.niclas.leaflod.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import de.niclas.leaflod.LeafLodClient;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Persistent settings, stored as {@code config/leaflod.json}.
 */
public final class LeafLodConfig {
	public static final int MIN_DISTANCE = 8;
	public static final int MAX_DISTANCE = 128;
	public static final int DISTANCE_STEP = 4;
	public static final int DEFAULT_DISTANCE = 32;

	public static final int MIN_BUDGET = 500;
	public static final int MAX_BUDGET = 20000;
	public static final int BUDGET_STEP = 500;
	/**
	 * Default leaf budget. Rough reasoning: a dense jungle has several leaf blocks per column (giant jungle trees
	 * have several hundred leaves each), so ~8000 leaf blocks correspond to a radius of roughly 25-35 blocks there
	 * (about the fixed-mode default), while in open terrain with scattered small trees (~60 leaves each) the
	 * radius grows up to the render distance. GPU cost scales roughly with the number of bushy leaf blocks.
	 */
	public static final int DEFAULT_BUDGET = 8000;

	public static final int MIN_MIN_RADIUS = 8;
	public static final int MAX_MIN_RADIUS = 64;
	public static final int DEFAULT_MIN_RADIUS = 24;

	public static final int MIN_GRASS_DISTANCE = 8;
	public static final int MAX_GRASS_DISTANCE = 64;
	public static final int DEFAULT_GRASS_DISTANCE = 24;

	public static final int MIN_ZOOM_DISTANCE = 32;
	public static final int MAX_ZOOM_DISTANCE = 512;
	public static final int ZOOM_DISTANCE_STEP = 16;
	/** Effectively "up to the render distance" for typical settings (the radius is always clamped to it). */
	public static final int DEFAULT_ZOOM_DISTANCE = 256;

	public static final int MAX_ZOOM_GRACE_MILLIS = 5000;
	public static final int ZOOM_GRACE_STEP_MILLIS = 250;
	public static final int DEFAULT_ZOOM_GRACE_MILLIS = 1500;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "leaflod.json";

	private static LeafLodConfig instance = new LeafLodConfig();

	public enum Mode {
		/** Full model for the nearest {@link #leafBudget} leaf blocks (radius adapts to the leaf density). */
		BUDGET,
		/** Full model within a fixed distance ({@link #lodDistance}). */
		FIXED
	}

	/** Master switch. When off, every leaf block uses its full model. */
	public boolean enabled = true;
	/** How the full-model radius is chosen. */
	public Mode mode = Mode.BUDGET;
	/** FIXED mode: leaf blocks within this many blocks of the camera keep the full (bushy) model. */
	public int lodDistance = DEFAULT_DISTANCE;
	/** BUDGET mode: number of nearest leaf blocks that keep the full model. */
	public int leafBudget = DEFAULT_BUDGET;
	/** BUDGET mode: the radius never shrinks below this many blocks. */
	public int minRadius = DEFAULT_MIN_RADIUS;
	/** Near leaves keep their bush geometry only if they are exposed (not completely surrounded by leaves/solid blocks). */
	public boolean outerLeavesOnly = true;
	/** Leaves with snow (or another thin cover like a carpet) on top: cut the bush geometry at the block top. */
	public boolean clipUnderSnow = true;
	/** Distance-limit heavy grass / fern / bush models as well. */
	public boolean grassEnabled = true;
	/** Grass, ferns and bushes within this many blocks keep the full model; farther away a simple X-cross is used. */
	public int grassDistance = DEFAULT_GRASS_DISTANCE;
	/** While zoomed (Zoomify, spyglass, ...): extend the full-model range inside the view cone, proportional to the zoom. */
	public boolean zoomEnabled = true;
	/** Upper limit for the zoom-extended leaf radius in blocks (also clamped to the render distance). */
	public int zoomMaxDistance = DEFAULT_ZOOM_DISTANCE;
	/** How long the extended range is kept after zooming out, so quick re-zooms don't trigger re-meshing. */
	public int zoomGraceMillis = DEFAULT_ZOOM_GRACE_MILLIS;

	public static LeafLodConfig get() {
		return instance;
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	public static void load() {
		Path path = path();
		LeafLodConfig loaded = null;

		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				loaded = GSON.fromJson(reader, LeafLodConfig.class);
			} catch (IOException | JsonParseException e) {
				LeafLodClient.LOGGER.warn("[Leaf LOD] Could not read {}, using defaults", path, e);
			}
		}

		if (loaded == null) {
			loaded = new LeafLodConfig();
		}

		loaded.sanitize();
		instance = loaded;
		save();
	}

	public static void save() {
		Path path = path();

		try {
			Files.createDirectories(path.getParent());
			Path tmp = path.resolveSibling(FILE_NAME + ".tmp");

			try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, writer);
			}

			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			LeafLodClient.LOGGER.warn("[Leaf LOD] Could not write {}", path, e);
		}
	}

	public static int sanitizeDistance(int distance) {
		int clamped = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, distance));
		return Math.round(clamped / (float) DISTANCE_STEP) * DISTANCE_STEP;
	}

	public static int sanitizeBudget(int budget) {
		int clamped = Math.max(MIN_BUDGET, Math.min(MAX_BUDGET, budget));
		return Math.round(clamped / (float) BUDGET_STEP) * BUDGET_STEP;
	}

	public static int sanitizeMinRadius(int radius) {
		int clamped = Math.max(MIN_MIN_RADIUS, Math.min(MAX_MIN_RADIUS, radius));
		return Math.round(clamped / (float) DISTANCE_STEP) * DISTANCE_STEP;
	}

	private void sanitize() {
		if (this.mode == null) {
			this.mode = Mode.BUDGET;
		}

		this.lodDistance = sanitizeDistance(this.lodDistance);
		this.leafBudget = sanitizeBudget(this.leafBudget);
		this.minRadius = sanitizeMinRadius(this.minRadius);
		this.grassDistance = sanitizeGrassDistance(this.grassDistance);
		this.zoomMaxDistance = sanitizeZoomDistance(this.zoomMaxDistance);
		this.zoomGraceMillis = sanitizeZoomGrace(this.zoomGraceMillis);
	}

	public static int sanitizeZoomDistance(int distance) {
		int clamped = Math.max(MIN_ZOOM_DISTANCE, Math.min(MAX_ZOOM_DISTANCE, distance));
		return Math.round(clamped / (float) ZOOM_DISTANCE_STEP) * ZOOM_DISTANCE_STEP;
	}

	public static int sanitizeZoomGrace(int millis) {
		int clamped = Math.max(0, Math.min(MAX_ZOOM_GRACE_MILLIS, millis));
		return Math.round(clamped / (float) ZOOM_GRACE_STEP_MILLIS) * ZOOM_GRACE_STEP_MILLIS;
	}

	public static int sanitizeGrassDistance(int distance) {
		int clamped = Math.max(MIN_GRASS_DISTANCE, Math.min(MAX_GRASS_DISTANCE, distance));
		return Math.round(clamped / (float) DISTANCE_STEP) * DISTANCE_STEP;
	}
}
