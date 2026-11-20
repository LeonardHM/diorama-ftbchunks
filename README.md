# Screenshots: FTB Chunks claiming, claim styles and radar text size

This branch only holds screenshots of the changes in the [`release/0.10.2-with-mods`](https://github.com/LeonardHM/diorama-ftbchunks/tree/release/0.10.2-with-mods) branch.

Taken on Minecraft 1.20.1 Forge, with FTB Chunks installed.

## 1. Claim style: Walls
![Walls style](https://raw.githubusercontent.com/LeonardHM/diorama-ftbchunks/screenshots/screenshots/01-claim-overlay-walls.png)

Claims drawn as chunk-aligned borders at a fixed height instead of following the terrain. On the tilted 3D map the borders stay square and line up with the chunk grid, and they also show on cave layers. The Surface style (the previous behaviour) is still available.

## 2. Right-click a chunk: claim and force-load
![Chunk menu](https://raw.githubusercontent.com/LeonardHM/diorama-ftbchunks/screenshots/screenshots/02-chunk-menu.png)

The map's right-click menu gets a "Chunk x, z" section. It offers Claim or Unclaim chunk, and Enable or Disable force loading. The entries only appear when FTB Chunks is installed. The request is the same packet FTB Chunks' own map sends, so the server decides what is allowed.

## 3. Force-loaded chunks
![Force-loaded chunk](https://raw.githubusercontent.com/LeonardHM/diorama-ftbchunks/screenshots/screenshots/03-forceloaded-chunk.png)

A force-loaded chunk is drawn with a checker pattern in the owner's colour. In the Outline style only the border is drawn.

## 4. Right-drag to select an area
![Area selection](https://raw.githubusercontent.com/LeonardHM/diorama-ftbchunks/screenshots/screenshots/06-area-selection.png)

Dragging with the right mouse button highlights a rectangle of chunks. A selection is limited to 1024 chunks.

![Area menu, claimed chunks](https://raw.githubusercontent.com/LeonardHM/diorama-ftbchunks/screenshots/screenshots/07-area-menu-claimed.png)

Releasing opens a menu for the whole area. On claimed chunks: Unclaim and Enable force loading.

![Area menu, unclaimed chunks](https://raw.githubusercontent.com/LeonardHM/diorama-ftbchunks/screenshots/screenshots/08-area-menu-unclaimed.png)

On unclaimed chunks: Claim chunk.

## 5. Claim style setting
![Claim style setting](https://raw.githubusercontent.com/LeonardHM/diorama-ftbchunks/screenshots/screenshots/05-claim-style-setting.png)

Overlays tab: Claim style cycles Surface, Ground outline and Walls.

## 6. Minimap info lines
![Minimap tab](https://raw.githubusercontent.com/LeonardHM/diorama-ftbchunks/screenshots/screenshots/09-minimap-tab.png)

Minimap tab, new options for the lines under the minimap:
- **Coordinates size, Biome name size, Claim text size** (and Game time size when the clock is on): each line has its own size, and "Off" hides it. This replaces the old Coordinates and Biome checkboxes; existing settings are migrated.
- **Info line order**: opens a screen to reorder the lines (coordinates, biome, claim, time).
- **Info text alignment**: Auto (follows the minimap corner), Left, Center or Right.
- **Clan color on minimap**: how the claim owner's colour is shown on the claim line (square icon, coloured text, both or none).

The claim line shows the owner of the chunk you stand in and whether it is force-loaded.

## 7. Radar text size
![Radar text size](https://raw.githubusercontent.com/LeonardHM/diorama-ftbchunks/screenshots/screenshots/04-radar-text-size.png)

Radar tab: a separate size for the names shown on the radar.

## 8. Web map: dimension tabs
![Web map tabs](https://raw.githubusercontent.com/LeonardHM/diorama-ftbchunks/screenshots/screenshots/10-web-map-dimension-tabs.png)

With many dimensions the tabs scroll sideways (scrollbar under the tabs; mouse wheel works too) instead of being cut off.

![Empty dimension](https://raw.githubusercontent.com/LeonardHM/diorama-ftbchunks/screenshots/screenshots/11-web-map-empty-dimension.png)

A dimension with nothing mapped yet shows its message, and the tabs stay clickable so another dimension can be picked.
