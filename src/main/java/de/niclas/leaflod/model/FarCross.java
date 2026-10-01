package de.niclas.leaflod.model;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.ShadeMode;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;

/**
 * Vanilla {@code block/cross} geometry (two diagonal planes, both sides = 4 quads) emitted with the sprite, tint,
 * layer and shading flags of one original resource-pack quad.
 *
 * <p>The quads are emitted directly (no pre-built FRAPI {@code Mesh}): Sodium 0.9.2's {@code Mesh.outputTo} reloads
 * quads without recomputing their cached facing, so diagonal mesh quads inherit the facing of the previously emitted
 * quad and get removed by Sodium's block-face culling depending on the view direction. Setting the positions on the
 * emitter makes every renderer recompute the geometry.
 *
 * <p>Immutable and thread-safe.
 */
final class FarCross {
	/** Vanilla {@code block/cross}: planes from (0.8, 0.8) to (15.2, 15.2) pixels after the 45 degree rescaled rotation. */
	private static final float MIN = 0.05f;
	private static final float MAX = 0.95f;

	/**
	 * Per quad: left x, left z, right x, right z (seen from the visible side; vertices are emitted top-left,
	 * bottom-left, bottom-right, top-right = counter-clockwise from the front, like vanilla faces).
	 */
	private static final float[][] QUADS = {
			{MIN, MIN, MAX, MAX}, // plane A, facing (-x, +z)
			{MAX, MAX, MIN, MIN}, // plane A, facing (+x, -z)
			{MAX, MIN, MIN, MAX}, // plane B, facing (-x, -z)
			{MIN, MAX, MAX, MIN}  // plane B, facing (+x, +z)
	};

	private final BakedQuad source;
	private final TriState ambientOcclusion;
	private final float u0;
	private final float u1;
	private final float v0;
	private final float v1;

	FarCross(BakedQuad source, boolean partAmbientOcclusion) {
		this.source = source;
		// Same as Fabric's VanillaBlockModelPartEncoder: AO flag comes from the model part.
		this.ambientOcclusion = partAmbientOcclusion ? TriState.DEFAULT : TriState.FALSE;
		TextureAtlasSprite sprite = source.materialInfo().sprite();
		this.u0 = sprite.getU0();
		this.u1 = sprite.getU1();
		this.v0 = sprite.getV0();
		this.v1 = sprite.getV1();
	}

	void emit(QuadEmitter emitter) {
		for (float[] quad : QUADS) {
			emitter.cullFace(null);
			emitter.fromBakedQuad(this.source);
			emitter.ambientOcclusion(this.ambientOcclusion);
			emitter.shadeMode(ShadeMode.VANILLA);
			emitter.nominalFace(null);
			emitter.pos(0, quad[0], 1.0f, quad[1]);
			emitter.pos(1, quad[0], 0.0f, quad[1]);
			emitter.pos(2, quad[2], 0.0f, quad[3]);
			emitter.pos(3, quad[2], 1.0f, quad[3]);
			emitter.uv(0, this.u0, this.v0);
			emitter.uv(1, this.u0, this.v1);
			emitter.uv(2, this.u1, this.v1);
			emitter.uv(3, this.u1, this.v0);
			emitter.emit();
		}
	}
}
