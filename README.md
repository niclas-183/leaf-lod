# Leaf LOD

A small client-side Fabric mod for Minecraft **26.3** that makes "bushy leaves" resource packs cheap to render.

Bushy-leaves packs (for example *Vervada's enhanced plants*) give every leaf block extra planes or boxes around its cube.
That looks great up close, but a jungle has tens of thousands of leaf blocks, and most of that extra geometry is far away or hidden inside the canopy.

Leaf LOD keeps the pack's full model **only near you**:

- **Near:** the resource pack's model is rendered exactly as it would be without the mod.
- **Farther away:** leaves are rendered as the pack's own plain cube, and heavy grass, fern and bush models as a simple X-cross with the same texture.

The resource pack itself is never modified. Leaf LOD just decides per block which part of the pack's model gets drawn.

> **Status: beta (0.1.0-beta).** Tested with Sodium 0.9.2, Iris 1.11.7 and the vanilla renderer on Minecraft 26.3 / Fabric Loader 0.19.5.

## Example

In a jungle with *Vervada's enhanced plants*, at 3440×1440 with Complementary Reimagined r5.9.3 + Euphoria Patches 1.10.5 (Iris), the author measured **about 55 FPS without the mod and about 90 FPS with it**.
That is one measurement on one PC; your gain depends on the pack, the shaders, your GPU and your settings.

## Features

- **Leaf budget mode (default):** the nearest *N* leaf blocks keep the full model, so the full-model distance adapts to how dense the trees are: shorter in a jungle, longer in open land with a few trees.
- **Fixed distance mode:** full model within a fixed distance.
- **Inner leaves:** leaves completely surrounded by other leaves or solid blocks get only the cube, even near you (their bush planes would be hidden inside the canopy anyway).
- **Grass & ferns:** short grass, fern, tall grass, large fern and bush keep the pack's model near you and become a simple cross farther away. This only applies if the pack's model is heavier than the vanilla cross.
- **Zoom-aware:** while you zoom (Zoomify, the spyglass, or any zoom that narrows the field of view), the full-model range is extended inside the zoomed view.
- **Snow on leaves:** bush planes are cut off at the top of a leaf block that has snow, a carpet, leaf litter or petals on it, so the bush no longer pokes through the snow.
- **Generic:** works on the models the pack provides (any `LeavesBlock` whose model has a full cube plus extra geometry, including random variants). There is no per-pack configuration.
- **Zero overhead without a bushy pack:** plain cube leaves are never wrapped.

## Settings

Open them with **Mod Menu** (needs **Cloth Config**). Without them, edit `config/leaflod.json`.

| Setting | Default | Range | What it does |
|---|---|---|---|
| Enabled | on | | Master switch. |
| Mode | Leaf budget | Leaf budget / Fixed distance | How the full-model distance is chosen. |
| Leaf budget | 8000 leaf blocks | 500 to 20000 | Budget mode: how many of the nearest leaf blocks keep the full model. GPU cost grows with this number. |
| Minimum distance | 24 blocks | 8 to 64 | Budget mode: leaves at least this close always keep the full model. |
| Fixed distance | 32 blocks | 8 to 128 | Fixed mode: full model within this distance. |
| Only outer leaves get bush planes | on | | Surrounded leaves get only the cube. |
| Clip bush planes under snow | on | | Nothing sticks out above a leaf block that has snow, a carpet, leaf litter or petals on it. |
| Grass & ferns: limit distance | on | | Distance-limit heavy grass/fern/bush models too. |
| Grass & ferns distance | 24 blocks | 8 to 64 | Full grass model within this distance. |
| Zoom: extend bushy range in view | on | | Extend the full-model range inside the zoomed view. |
| Zoom: maximum distance | 256 blocks | 32 to 512 | Upper limit for the zoom-extended range. It is also capped by your render distance, and for grass at 96 blocks. |
| Zoom: keep after zooming out | 1.5 s | 0 to 5 s | Keeps the extended range briefly, so zooming in again quickly does not rebuild chunks twice. |

## Compatibility

- **Required:** Fabric Loader ≥ 0.19.5, Fabric API, Minecraft 26.3, Java 25.
- **Optional:** Mod Menu and Cloth Config, for the settings screen.
- **Tested with:** Sodium 0.9.2, Iris 1.11.7 (also with a shader pack), the vanilla renderer without Sodium, Continuity 3.0.1, More Culling 1.9.0-beta.1 and Distant Horizons 3.3.4.
- **Client-only.** It works on any server, and the server does not need it.

### Tested resource packs

| Pack | Result |
|---|---|
| Vervada's enhanced plants | Works: leaves 14 → 6 quads far away, grass/ferns 12 → 4. |
| Optimized Bushy Leaves 2.1 | Works: leaves 30 → 6 quads far away. |
| Universal Bushy Leaves 1.2.1 | Works: leaves 102 → 6 quads far away. |
| xali's Bushy Leaves 3.5.0 (made for 1.20.1) | Works: leaves 14 → 6 quads far away. Minecraft marks the pack as made for an older version. |
| dronko's alternative Bushy Leaves 5.0 | The pack itself does not load in 26.3 (syntax error in its `pack.mcmeta`). With that fixed locally it works: 12 → 6 quads far away. Its random variants without a complete cube use the cube of another variant. |
| Bushy Leaves (Awoolanche) 1.6.5 | Not applicable: its leaves have no cube, so Leaf LOD leaves them unchanged. |

## Known limitations

- At the edge of the full-model distance, a tree switches from bushy to the plain cube (level-of-detail "pop"). A larger budget or distance moves that edge farther away.
- When you move or zoom, the chunk sections that cross the edge are rebuilt. This costs some CPU time, which is spread out and rate-limited.
- Packs whose leaves have no full cube at all get no reduction, because there is no cube to fall back to.
- Only leaf blocks (`LeavesBlock`, including modded leaves that extend it) and the five vanilla grass-like plants are handled.

## Building

Requires JDK 25.

```
./gradlew build
```

The mod jar is `build/libs/leaflod-<version>.jar`.

Automated in-game tests (Fabric client gametest; opens a game window briefly and creates a test world under `build/run/clientGameTest`):

```
./gradlew runClientGameTest
./gradlew runClientGameTest -Pdev_sodium=false          # vanilla renderer
./gradlew runClientGameTest -Pdev_compat=true           # + Continuity, More Culling
./gradlew runClientGameTest -Pdev_modmenu=false         # without Mod Menu / Cloth Config
./gradlew runClientGameTest -Pdev_real_pack=<pack.zip> -Pdev_skip_synthetic=true [-Pdev_shader_pack=<shader pack folder>]
```

The repository contains no resource-pack content. The gametest uses a few self-made test models, and real packs are only referenced by a path on your disk.

## Links

- Modrinth: https://modrinth.com/mod/leaf-lod
- Source code: https://github.com/niclas-183/leaf-lod
- Bug reports and suggestions: https://github.com/niclas-183/leaf-lod/issues. Please attach your `latest.log` and name the resource packs you use.

## License

MIT. See [LICENSE](LICENSE).
