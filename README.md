# Smart Rails

Self-driving minecarts for Minecraft Fabric: stations are vanilla signs with a
destination selector, junctions are derived from the actual track geometry, and
trains steer themselves onto the branch that matches the chosen destination.

## Features

* **Station signs** — a vanilla sign flanked by two buttons. Sneak + right-click
  cycles the destination, a normal right-click boards / departs.
* **Junction routing** — turnouts are detected from the physical rail layout
  (plus optional manual markers) and each train is steered onto the correct arm.
  Crossing enforcement keeps traffic right-handed, so a train never turns into
  the oncoming lane.
* **Cargo stations** — freight platforms that can be dispatched by a redstone
  pulse instead of a player boarding.
* **High-speed rail** — configurable powered-rail boosting with a speed cap.
* **Network publication** (optional) — the topology is pushed to a MariaDB
  instance, from where a small host-side script generates BlueMap markers and a
  `network.json` for a web overview (see `feed/`).

## Requirements

| | |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.19.5+ |
| Fabric API | required |
| Java | 25+ |
| Environment | server |

## Build

```bash
gradle build          # produces build/libs/smartrail-<version>.jar
```

The build expects the Fabric API artifact to resolve from your configured
repositories; for an offline/local mirror add a `maven { url = uri(...) }`
repository to `build.gradle`.

## Install

Drop `smartrail-<version>.jar` into the server's `mods/` folder and restart.
The mod is server-side only — clients do not need it.

## Commands

Root command: **`/srails`** (admin commands require permission level 2).

| Command | Description |
|---|---|
| `/srails station add <name>` | Register the sign you are looking at as a passenger station |
| `/srails station addcargo <name>` | Register it as a cargo station |
| `/srails station remove <name>` | Deregister a station |
| `/srails station list` | List stations and their reachable destinations |
| `/srails junction add` / `remove` / `list` | Add or drop a manual junction marker on the rail you are looking at |
| `/srails stick` | Get the junction stick (places junction markers while sneaking) |
| `/srails depart <station>` | Force a departure (admin/test) |
| `/srails dbg graph` | Dump the parsed topology: nodes, edges, crossings |
| `/srails dbg shape <x> <y> <z>` | Show the rail shape/arms at a block |
| `/srails dbg kind <x> <y> <z>` | Show the rail kind at a block |
| `/srails dbg register <name> <x> <y> <z>` | Headless station registration (testing) |
| `/srails dbg select <station> <dest>` | Headless destination selection (testing) |

## Configuration

`config/smartrails.json` is created on first start. Visible chat output is German
by default (`"lang": "en"` switches it); command names are English in both.

```json
{
  "lang": "de",
  "reboardSeconds": 30,
  "highSpeed":  { "…": "speed cap / booster tuning" },
  "cargo":      { "cruiseSpeedMs": 8.0, "windowChunks": 2, "redstoneTrigger": true },
  "crossings":  { "enforce": true },
  "publish":    { "enabled": false, "host": "127.0.0.1", "port": 3306,
                  "user": "", "password": "", "minIntervalSec": 10 }
}
```

The `publish` block feeds an external MariaDB database with the rail topology
(`nodes`, `edges`, `publog` tables). Journal writes happen off the server thread;
a payload that carries edges is authoritative for the whole dimension, a
registry-only payload (server start, station changes) leaves the published
geometry untouched. Enable it on exactly **one** instance when several servers
share the database.

## Network feed (`feed/`)

`feed/smart_rail_feed.py` runs on the host that owns the database and turns it
into:

* BlueMap marker files (`<webapp>/maps/<mapid>/live/markers.json`), one set per
  traffic class — passenger, cargo, shared, no through traffic — plus stations,
* a slim `network.json` (`generatedUtc`, `dims.<map>.stations|tracks`) for the
  welcome page's SVG overview.

`feed/rcon_generic.py` is a tiny RCON client used to trigger a topology rebuild
from the host (`srails dbg graph`) after track work.

## License

MIT — see [LICENSE](LICENSE).
