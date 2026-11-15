# The web map

A server with Diorama can draw its map for a browser: a page anyone can open on a second screen or a phone, with no mod or game. It looks like Diorama's own map, in vanilla colours whatever resource pack players use, and it fills in by itself as players explore and build.

It's off until you turn it on.

This is the server's map, and it only runs on a dedicated server. For your own map in a browser while you play, in singleplayer too, see [Your map on a second screen](second-screen.md).

## Turn it on

1. Put Diorama in the server's `mods` folder (Fabric needs Fabric API there too).
2. Start the server once. It writes `config/diorama-server.properties`.
3. In that file, set `web-map=true` and restart.
4. Open `http://<your server's address>:8100/` in a browser. If the server is behind a firewall, open port 8100 (TCP), or pick another with `web-map-port`.

The first time, the server draws every part of the world players have spent time in, nearest the middle of the world first, on one background thread. On a 4-core machine that took 25 minutes for Hermitcraft Season 10's world (about 2,200 tiles over three dimensions), with no change to the server's tick time. The page shows tiles as they're drawn.

After that, it redraws only what changed. Each region file records when each of its chunks was last saved, and the web map looks at those times every 5 minutes, the same as vanilla's autosave. A build shows within about 5 minutes of being built, with nothing to press. A chunk saved without any change to its blocks isn't drawn again.

## Settings

All in `config/diorama-server.properties`:

| Setting | What it does |
| --- | --- |
| `web-map` | `true` to draw the web map. Off by default. |
| `web-map-port` | The port of Diorama's own small web server. `0` turns it off, for when you serve the files yourself. Default 8100. |
| `web-map-address` | The address it listens on. Empty for every address; `127.0.0.1` to only reach it through a web server of your own on the same machine. |
| `web-map-folder` | Where the map's files go. Empty: `diorama-web` in the world folder. |
| `web-map-players` | `true` to show where online players are, with their names. `false` for a map with no players on it. Default `true`. |
| `web-map-claims` | `true` to show land claimed with FTB Chunks or Open Parties and Claims, as the map in the game does. `false` for a map without them. Default `true`; it does nothing on a server without either mod. |
| `share-land` | Which land the map shows, the same as the shared map: `visited` (land players have spent time near, as the game counts it in every chunk) or `generated` (every finished chunk, for worlds pre-generated with Chunky). |

The web map follows the server's other map settings in the same file, so it shows no more than the map in the game does:

- A dimension in `map-off-dimensions` isn't drawn, and the pictures it had are deleted.
- With `cave-maps-allowed=false` or `cave-fog-of-war=true`, the Nether (any dimension under a roof) is left out the same way. There the map in the game shows only what each player has seen, and a page anyone can open can't know that.

- With `radar-allowed=false`, where no map in the game shows players, the page shows none either.

## Players

The page shows everyone online as an arrow with their name, pointing where they look, and lists them under a button at the top; picking a name moves the map to them. It asks the server once a second.

Not shown: anyone sneaking, invisible or in spectator mode, and anyone in a dimension the map leaves out. Sneaking is how a player takes themselves off the page.

`web-map-players=false` turns it off.

Where players are is never written to the map's folder: Diorama's own web server answers `players.json` from memory, so nothing is left behind when the server stops or crashes, or when you turn players off. If you serve the folder yourself, the page shows players only when your web server passes that one address on to Diorama's (see below); with `web-map-port=0` it shows none.

## Land claims

On a server with FTB Chunks or Open Parties and Claims, the page shows claimed land the way Diorama's map in the game does: tinted in the owner's colour, with a stronger line where one owner's land meets another's or the wild, and force-loaded chunks tinted a little more. Point at a claim to see whose it is ("Claimed by Alex", or Open Parties and Claims' own title such as "Base - Alex's Claim"); on a phone, tap it.

The map looks down at an angle, so a chunk on a hilltop is drawn further up the picture than one at sea level. Each picture comes with a small file saying which chunk each part of it shows, so a claim sits on the ground it covers, hills and cliffs too, as in the game.

It shows only what every player already sees in the game:

- An FTB team whose claim visibility isn't public shows its claims only to its allies, so they aren't on the page.
- Open Parties and Claims sends every player's claims to everyone only while its `claimsSynchronization` is `ALL` (its default). With `OWNED_ONLY` the page shows only server claims, and with `NOT_SYNCED` none.
- Claims in a dimension the web map leaves out aren't on the page.

The server reads the claims every couple of seconds and the page asks for them every 10, so a claim or unclaim shows within about 15 seconds. They're in `claims.json` beside the pictures.

The first time the server starts with claims to show, it draws the whole map again once, so every picture has its file of chunks. The same happens if you turn `web-map-claims` back on later.

`web-map-claims=false` turns them off.

These are read when the server starts. A dimension allowed again is drawn whole again. If you serve the folder with a web server of your own, the old pictures stay reachable until the Minecraft server has started and deleted them.

## Markers

An operator can mark named places on the page for everyone: spawn, a shop, a nether hub.

- `/diorama-web marker add <name>` marks where you stand. With a name that's already there, it moves that marker. From the console, say where: `execute in minecraft:overworld positioned 0 64 0 run diorama-web marker add Spawn`.
- `/diorama-web marker remove <name>` takes one off.
- `/diorama-web marker list` lists them.

These aren't players' waypoints, which stay on each player's own computer; the page shows only what an operator put there. Up to 500 markers, names up to 48 characters. They're kept in `diorama-web-markers.json` in the world folder, and the page reads `markers.json` in the map's folder, which the server writes from it. A marker in a dimension the map leaves out isn't in that file. The server writes it when it starts and when a marker changes: if you serve the folder with a web server of your own, a marker in a dimension you've just turned off stays reachable until the Minecraft server has started, as the old pictures do.

## Serving it yourself

The folder is a plain static site: `index.html`, `map.json`, `markers.json` and the pictures, one JPEG per tile in `<dimension>/<zoom>/<x>_<y>.jpg`. With land claims shown there's also `claims.json`, and a small PNG beside each picture saying which chunk each part of it shows. Point nginx, Caddy or any web server at it and set `web-map-port=0`. For example with nginx:

```nginx
location /map/ {
    alias /srv/minecraft/world/diorama-web/;
}
```

To have players on a page you serve yourself, keep Diorama's web server on, reachable from the same machine only (`web-map-port=8100`, `web-map-address=127.0.0.1`), and pass the one address on:

```nginx
location = /map/players.json {
    proxy_pass http://127.0.0.1:8100/players.json;
}
```

## Commands

For operators:

- `/diorama-web status` says what it's doing.
- `/diorama-web redraw` draws everything again.
- `/diorama-web marker add|remove|list`: see Markers above.

Changing `share-land` redraws the whole map by itself the next time the server starts.

Land taken out of the world stays on the web map until `/diorama-web redraw`: a dimension reset by deleting its folder, region files deleted, or chunks trimmed with a tool such as MCA Selector.

## Disk

About 59 KB a tile of land, plus half as much again for the zoomed-out views. Hermitcraft Season 10's world took 173 MB: 125 MB for the overworld, 30 MB for the Nether, 19 MB for the End.

## Modded blocks

Mods ship their block models and textures in the same jar on the server, and the web map reads them from the jars in the `mods` folder, as BlueMap does. Two things only a game client knows can't be read there:

- **Models made in code** (some machines, connected textures) have no files to read. Those blocks show in their colour on vanilla maps.
- **Tints.** A block whose model asks to be tinted shows in the biome's foliage colour if it's leaves, and its grass colour otherwise. A mod that tints its blocks some other way, or not at all, can come out a different colour from the game.
