# Diorama 0.10.2 with modifications

[Diorama](https://github.com/SunkenInTime/diorama) 0.10.2 plus the changes below. Everything else is the original project. This branch is the one to build and play; the jar is named 0.10.2.

## What this branch adds

**FTB Chunks claiming from the fullscreen map** (needs FTB Chunks; no compile-time dependency)
- Right-click a chunk: Claim / Unclaim chunk, Enable / Disable force loading.
- Right-drag a rectangle (up to 1024 chunks) to do the same for a whole area.

**Claim styles** (Overlays tab, "Claim style"): Surface (original), Ground outline, Walls. The two flat styles stay aligned with the chunk grid on the tilted 3D map and also show on cave layers. Force-loaded chunks are drawn with a checker pattern.

**Minimap info lines** (Minimap tab)
- Separate size for the coordinates, biome, claim and game time lines (Off hides one; replaces the old Coordinates and Biome checkboxes, existing settings are migrated).
- Reorderable lines, text alignment (Auto / Left / Center / Right), and a claim line with the owner's colour (icon, text, both or none).

**Radar text size** (Radar tab): its own size for the names shown on the radar.

**Portuguese (pt_br) translation**, new.

**Web map fix**: the dimension tabs scroll sideways when there are many (mouse wheel too, full-width row on small screens), and picking a dimension with nothing mapped no longer leaves the tabs unclickable.

Screenshots: [`screenshots` branch](https://github.com/LeonardHM/diorama-ftbchunks/tree/screenshots).

## Building

`./gradlew :1.20.1-forge:build` (Gradle needs JDK 25). The jar is in `versions/1.20.1-forge/build/libs/`. Only 1.20.1 Forge has been built and tested.

## Branches of this fork

| Branch | Contents |
|---|---|
| `release/0.10.2-with-mods` | This branch: 0.10.2 + all changes above |
| `feature/ftb-chunks-claims-0.10.2` | The FTB Chunks / claim styles / minimap lines changes on top of upstream `master`; head of [upstream PR #430](https://github.com/SunkenInTime/diorama/pull/430) |
| `fix/web-map-dimension-tabs` | Only the web map fix, on top of upstream `master` |
| `screenshots` | Screenshots and captions |
| `master` | Overview of this fork |
