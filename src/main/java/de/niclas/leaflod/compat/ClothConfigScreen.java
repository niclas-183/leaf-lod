package de.niclas.leaflod.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;

import de.niclas.leaflod.config.LeafLodConfig;
import de.niclas.leaflod.lod.LodController;
import de.niclas.leaflod.lod.LodReference;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Config screen built with Cloth Config. Only loaded when Cloth Config is present.
 */
final class ClothConfigScreen {
	private ClothConfigScreen() {
	}

	static Screen create(Screen parent) {
		LeafLodConfig config = LeafLodConfig.get();
		// Values edited on the screen; applied together in the saving runnable.
		boolean[] enabled = {config.enabled};
		LeafLodConfig.Mode[] mode = {config.mode};
		int[] distance = {config.lodDistance};
		int[] budget = {config.leafBudget};
		int[] minRadius = {config.minRadius};
		boolean[] outerOnly = {config.outerLeavesOnly};
		boolean[] clipUnderSnow = {config.clipUnderSnow};
		boolean[] grassEnabled = {config.grassEnabled};
		int[] grassDistance = {config.grassDistance};
		boolean[] zoomEnabled = {config.zoomEnabled};
		int[] zoomMaxDistance = {config.zoomMaxDistance};
		int[] zoomGrace = {config.zoomGraceMillis};

		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Component.translatable("leaflod.config.title"));
		ConfigEntryBuilder entries = builder.entryBuilder();
		ConfigCategory general = builder.getOrCreateCategory(Component.translatable("leaflod.config.category.general"));

		general.addEntry(entries.startBooleanToggle(Component.translatable("leaflod.config.enabled"), config.enabled)
				.setDefaultValue(true)
				.setTooltip(wrap(Component.translatable("leaflod.config.enabled.tooltip")))
				.setSaveConsumer(value -> enabled[0] = value)
				.build());

		general.addEntry(entries.startEnumSelector(Component.translatable("leaflod.config.mode"), LeafLodConfig.Mode.class, config.mode)
				.setDefaultValue(LeafLodConfig.Mode.BUDGET)
				.setEnumNameProvider(value -> Component.translatable("leaflod.config.mode." + value.name().toLowerCase(java.util.Locale.ROOT)))
				.setTooltipSupplier(() -> Optional.of(wrap(
						Component.translatable("leaflod.config.mode.tooltip"),
						currentRadius())))
				.setSaveConsumer(value -> mode[0] = value)
				.build());

		// Cloth sliders have no step size, so each slider works in units of its step.
		int budgetStep = LeafLodConfig.BUDGET_STEP;
		general.addEntry(entries.startIntSlider(Component.translatable("leaflod.config.budget"),
						config.leafBudget / budgetStep, LeafLodConfig.MIN_BUDGET / budgetStep, LeafLodConfig.MAX_BUDGET / budgetStep)
				.setDefaultValue(LeafLodConfig.DEFAULT_BUDGET / budgetStep)
				.setTextGetter(value -> Component.translatable("leaflod.config.budget.value", value * budgetStep))
				.setTooltipSupplier(value -> Optional.of(wrap(
						Component.translatable("leaflod.config.budget.tooltip"),
						currentRadius())))
				.setSaveConsumer(value -> budget[0] = LeafLodConfig.sanitizeBudget(value * budgetStep))
				.build());

		int step = LeafLodConfig.DISTANCE_STEP;
		general.addEntry(entries.startIntSlider(Component.translatable("leaflod.config.min_radius"),
						config.minRadius / step, LeafLodConfig.MIN_MIN_RADIUS / step, LeafLodConfig.MAX_MIN_RADIUS / step)
				.setDefaultValue(LeafLodConfig.DEFAULT_MIN_RADIUS / step)
				.setTextGetter(value -> Component.translatable("leaflod.config.blocks", value * step))
				.setTooltip(wrap(Component.translatable("leaflod.config.min_radius.tooltip")))
				.setSaveConsumer(value -> minRadius[0] = LeafLodConfig.sanitizeMinRadius(value * step))
				.build());

		general.addEntry(entries.startIntSlider(Component.translatable("leaflod.config.distance"),
						config.lodDistance / step, LeafLodConfig.MIN_DISTANCE / step, LeafLodConfig.MAX_DISTANCE / step)
				.setDefaultValue(LeafLodConfig.DEFAULT_DISTANCE / step)
				.setTextGetter(value -> Component.translatable("leaflod.config.blocks", value * step))
				.setTooltip(wrap(Component.translatable("leaflod.config.distance.tooltip")))
				.setSaveConsumer(value -> distance[0] = LeafLodConfig.sanitizeDistance(value * step))
				.build());

		general.addEntry(entries.startBooleanToggle(Component.translatable("leaflod.config.outer_leaves_only"), config.outerLeavesOnly)
				.setDefaultValue(true)
				.setTooltip(wrap(Component.translatable("leaflod.config.outer_leaves_only.tooltip")))
				.setSaveConsumer(value -> outerOnly[0] = value)
				.build());

		general.addEntry(entries.startBooleanToggle(Component.translatable("leaflod.config.clip_under_snow"), config.clipUnderSnow)
				.setDefaultValue(true)
				.setTooltip(wrap(Component.translatable("leaflod.config.clip_under_snow.tooltip")))
				.setSaveConsumer(value -> clipUnderSnow[0] = value)
				.build());

		general.addEntry(entries.startBooleanToggle(Component.translatable("leaflod.config.grass_enabled"), config.grassEnabled)
				.setDefaultValue(true)
				.setTooltip(wrap(Component.translatable("leaflod.config.grass_enabled.tooltip")))
				.setSaveConsumer(value -> grassEnabled[0] = value)
				.build());

		general.addEntry(entries.startIntSlider(Component.translatable("leaflod.config.grass_distance"),
						config.grassDistance / step, LeafLodConfig.MIN_GRASS_DISTANCE / step, LeafLodConfig.MAX_GRASS_DISTANCE / step)
				.setDefaultValue(LeafLodConfig.DEFAULT_GRASS_DISTANCE / step)
				.setTextGetter(value -> Component.translatable("leaflod.config.blocks", value * step))
				.setTooltip(wrap(Component.translatable("leaflod.config.grass_distance.tooltip")))
				.setSaveConsumer(value -> grassDistance[0] = LeafLodConfig.sanitizeGrassDistance(value * step))
				.build());

		general.addEntry(entries.startBooleanToggle(Component.translatable("leaflod.config.zoom_enabled"), config.zoomEnabled)
				.setDefaultValue(true)
				.setTooltip(wrap(Component.translatable("leaflod.config.zoom_enabled.tooltip")))
				.setSaveConsumer(value -> zoomEnabled[0] = value)
				.build());

		int zoomStep = LeafLodConfig.ZOOM_DISTANCE_STEP;
		general.addEntry(entries.startIntSlider(Component.translatable("leaflod.config.zoom_max_distance"),
						config.zoomMaxDistance / zoomStep, LeafLodConfig.MIN_ZOOM_DISTANCE / zoomStep, LeafLodConfig.MAX_ZOOM_DISTANCE / zoomStep)
				.setDefaultValue(LeafLodConfig.DEFAULT_ZOOM_DISTANCE / zoomStep)
				.setTextGetter(value -> Component.translatable("leaflod.config.blocks", value * zoomStep))
				.setTooltip(wrap(Component.translatable("leaflod.config.zoom_max_distance.tooltip")))
				.setSaveConsumer(value -> zoomMaxDistance[0] = LeafLodConfig.sanitizeZoomDistance(value * zoomStep))
				.build());

		int graceStep = LeafLodConfig.ZOOM_GRACE_STEP_MILLIS;
		general.addEntry(entries.startIntSlider(Component.translatable("leaflod.config.zoom_grace"),
						config.zoomGraceMillis / graceStep, 0, LeafLodConfig.MAX_ZOOM_GRACE_MILLIS / graceStep)
				.setDefaultValue(LeafLodConfig.DEFAULT_ZOOM_GRACE_MILLIS / graceStep)
				.setTextGetter(value -> Component.translatable("leaflod.config.seconds", String.format(java.util.Locale.ROOT, "%.2f", value * graceStep / 1000.0)))
				.setTooltip(wrap(Component.translatable("leaflod.config.zoom_grace.tooltip")))
				.setSaveConsumer(value -> zoomGrace[0] = LeafLodConfig.sanitizeZoomGrace(value * graceStep))
				.build());

		builder.setSavingRunnable(() -> {
			boolean changed = config.enabled != enabled[0]
					|| config.mode != mode[0]
					|| config.lodDistance != distance[0]
					|| config.leafBudget != budget[0]
					|| config.minRadius != minRadius[0]
					|| config.outerLeavesOnly != outerOnly[0]
					|| config.clipUnderSnow != clipUnderSnow[0]
					|| config.grassEnabled != grassEnabled[0]
					|| config.grassDistance != grassDistance[0];
			// Zoom settings need no full re-mesh: the next client tick builds or removes the cone as needed.
			config.zoomEnabled = zoomEnabled[0];
			config.zoomMaxDistance = zoomMaxDistance[0];
			config.zoomGraceMillis = zoomGrace[0];
			config.enabled = enabled[0];
			config.mode = mode[0];
			config.lodDistance = distance[0];
			config.leafBudget = budget[0];
			config.minRadius = minRadius[0];
			config.outerLeavesOnly = outerOnly[0];
			config.clipUnderSnow = clipUnderSnow[0];
			config.grassEnabled = grassEnabled[0];
			config.grassDistance = grassDistance[0];
			LeafLodConfig.save();

			if (changed) {
				LodController.applyConfig(true);
			}
		});

		return builder.build();
	}

	/** Maximum characters per tooltip line, so tooltips don't stretch across the whole screen. */
	private static final int TOOLTIP_LINE_LENGTH = 48;

	/** Splits each translated text into lines of at most {@link #TOOLTIP_LINE_LENGTH} characters at word boundaries. */
	private static Component[] wrap(Component... parts) {
		List<Component> lines = new ArrayList<>();

		for (Component part : parts) {
			StringBuilder line = new StringBuilder();

			for (String word : part.getString().split(" ")) {
				if (!line.isEmpty() && line.length() + 1 + word.length() > TOOLTIP_LINE_LENGTH) {
					lines.add(Component.literal(line.toString()));
					line.setLength(0);
				}

				if (!line.isEmpty()) {
					line.append(' ');
				}

				line.append(word);
			}

			if (!line.isEmpty()) {
				lines.add(Component.literal(line.toString()));
			}
		}

		return lines.toArray(Component[]::new);
	}

	private static Component currentRadius() {
		LodReference.Snapshot snapshot = LodReference.current();

		if (!snapshot.active() || !snapshot.leaves().has()) {
			return Component.translatable("leaflod.config.current_radius.none");
		}

		return Component.translatable("leaflod.config.current_radius", (int) snapshot.radius());
	}
}
