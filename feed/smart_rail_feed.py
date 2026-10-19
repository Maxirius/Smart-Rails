#!/usr/bin/env python3
"""Smart Rails network feed: MariaDB -> BlueMap marker files + site network.json.

Runs on the Netcup RS (root cron, every minute). Reads the topology tables the
mod publishes (db smartrail, user smartrail_writer) via the local mariadb
container and regenerates:

  * one BlueMap marker file PER MAP at map_app/maps/<mapid>/live/markers.json
    (the webapp resolves its marker file as "<liveDataRoot>/<mapid>/live/
    markers.json" -- verified against the shipped frontend bundle), and
  * a slim network.json for the welcome page's rail-network view.

Coordinate contract (verified in the frontend bundle): marker "position" is an
OBJECT {x,y,z}, and every entry of a line marker's "line" array is an OBJECT
{x,y,z} as well -- arrays are silently read as {0,0,0} and collapse the
geometry to the origin.

Writes are atomic (tmp + rename) and preserve the previous artifact when the
source tables are empty for a map, so a transient DB outage never blanks the
map.
"""

import json
import math
import collections
import os
import subprocess
import tempfile
from datetime import datetime, timezone

CONTAINER = "mariadb"
ENV_FILE = "/root/smarterail_db.env"

MAP_APP_DIR = "/opt/hostings/minecraft_site/map_app"
SITE_DIR = "/opt/hostings/minecraft_site/site"

DIM_TO_MAP = {
    "minecraft:overworld": "world",
    "minecraft:the_nether": "world_the_nether",
    "minecraft:the_end": "world_the_end",
}

# The rails are ONE physical network shared by both traffic kinds, so an edge is
# labelled by the stations that route across it (shortest paths within each
# station group): passenger-only, cargo-only, shared, or on no route at all.
# ONE colour for the whole network. The passenger/freight split never settled:
# most track carries both, so colouring it either way hid one of the two services
# (the freight line "disappeared" wherever passenger traffic ran). The service of
# a place is now told by the STATION markers -- yellow = passenger, pink =
# freight -- and the rails themselves are just the network.
TRACK_COLOR = {"r": 34, "g": 199, "b": 255, "a": 1}
TRACK_LABEL = "Gleisnetz"
# How far above the track block the line is drawn.
RAIL_LIFT = 0.6
STATION_COLOR = {"station": "#ffd54a", "cargo": "#ff5c8a"}


def load_env():
    env = {}
    with open(ENV_FILE) as fh:
        for line in fh:
            line = line.strip()
            if "=" in line and not line.startswith("#"):
                k, v = line.split("=", 1)
                env[k] = v
    return env


def query(sql, env):
    proc = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "mariadb",
         "-u", env["SMARTRAIL_DB_USER"],
         "-p" + env["SMARTRAIL_DB_PASS"],
         "--batch", "--skip-column-names", "smartrail"],
        input=sql, capture_output=True, text=True, timeout=60)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip()[:200])
    return proc.stdout


def decode_cells(enc):
    """Decode "x,y,z;dx,dy,dz;..." back to [[x,y,z], ...]."""
    if not enc:
        return []
    pts = []
    x = y = z = 0
    for i, tok in enumerate(enc.split(";")):
        tx, ty, tz = (int(v) for v in tok.split(","))
        if i == 0:
            x, y, z = tx, ty, tz
        else:
            x += tx
            y += ty
            z += tz
        pts.append([x, y, z])
    return pts


def vec(x, y, z):
    """BlueMap wants {x,y,z} objects, not arrays."""
    return {"x": round(x, 2), "y": round(y, 2), "z": round(z, 2)}


def atomic_write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), prefix=".feed-", suffix=".tmp")
    with os.fdopen(fd, "w") as fh:
        fh.write(text)
    os.chmod(tmp, 0o644)
    try:
        import pwd
        hs = pwd.getpwnam("hosting")
        os.chown(tmp, hs.pw_uid, hs.pw_gid)
    except (ImportError, KeyError, OSError):
        pass
    os.rename(tmp, path)


def build_stretches(edge_list, node_coords=None):
    """Collapse the per-direction lane edges into one PHYSICAL stretch.

    Right-hand traffic stores one edge per direction for the same piece of track
    (14 of them in the reference network) and those lanes are not parallel rails:
    they overlap cell for cell and differ by about one cell at the ends. The
    stretch therefore keeps the geometry of its LONGEST lane verbatim -- the line
    sits exactly on the rails, no lateral offset -- and snaps only its two end
    cells onto the junction anchor (the anchor cell most edges agree on), so
    neighbouring stretches meet exactly at the crossing.
    """
    groups = {}
    for e in edge_list:
        if len(e["cells"]) >= 2:
            groups.setdefault((min(e["frm"], e["dst"]), max(e["frm"], e["dst"])), []).append(e)
    if not groups:
        return []

    if node_coords is None:
        counters = {}
        for e in edge_list:
            if not e["cells"]:
                continue
            counters.setdefault(e["frm"], collections.Counter())[tuple(e["cells"][0])] += 1
            counters.setdefault(e["dst"], collections.Counter())[tuple(e["cells"][-1])] += 1
        node_coords = {nid: list(c.most_common(1)[0][0]) for nid, c in counters.items()}
    else:
        node_coords = {nid: list(c) for nid, c in node_coords.items()}

    out = []
    for _, lanes in sorted(groups.items()):
        base = min(lanes, key=lambda l: (-len(l["cells"]), l["eid"]))
        path = [list(c) for c in base["cells"]]
        path[0] = list(node_coords.get(base["frm"], path[0]))
        path[-1] = list(node_coords.get(base["dst"], path[-1]))
        out.append({"eid": base["eid"], "frm": base["frm"], "dst": base["dst"],
                    "path": path, "lanes": len(lanes),
                    "len": max(l["len"] for l in lanes), "arm": base["arm"]})
    return out


def stations_on_path(path, station_list, radius=6, dy=4):
    """Names of the stations that sit on (or right by) a stretch.

    Only used for the hover label -- there is no passenger/freight class any
    more. A stretch that leads past no station simply says so; it is still drawn,
    because it IS track in the database (junctions and connection pieces between
    stations belong to the network even when no station is on them).
    """
    out = []
    for st in station_list:
        for cell in path:
            if (abs(cell[0] - st["x"]) <= radius and abs(cell[2] - st["z"]) <= radius
                    and abs(cell[1] - st["y"]) <= dy):
                out.append(st["name"] or "?")
                break
    return sorted(set(out))


def main():
    env = load_env()
    nodes_sql = "SELECT nid,dim,kind,name,x,y,z FROM nodes;"
    edges_sql = "SELECT eid,dim,frm,dst,exit_arm,len,cells FROM edges;"

    nodes = {}
    for line in query(nodes_sql, env).splitlines():
        nid, dim, kind, name, x, y, z = (line.split("\t") + [""])[:7]
        nodes.setdefault(dim, []).append({
            "nid": int(nid), "kind": kind,
            "name": None if name in ("", "\\N") else name,
            "x": int(x), "y": int(y), "z": int(z)})

    edges = {}
    for line in query(edges_sql, env).splitlines():
        eid, dim, frm, dst, arm, ln, enc = (line.split("\t") + [""])[:7]
        edges.setdefault(dim, []).append({
            "eid": int(eid), "frm": int(frm), "dst": int(dst),
            "arm": arm, "len": int(ln),
            "cells": decode_cells("" if enc == "\\N" else enc)})

    stamp = int(datetime.now(timezone.utc).timestamp())

    # -------- BlueMap markers, grouped per map --------
    per_map = {}   # mapid -> {"tracks": {class: [...]}, "stations": [...]}

    for dim, edim in edges.items():
        mp = DIM_TO_MAP.get(dim)
        if not mp:
            continue
        bucket = per_map.setdefault(mp, {"tracks": [], "stations": []})
        coords = {n["nid"]: (n["x"], n["y"], n["z"]) for n in nodes.get(dim, [])}
        stretches = build_stretches(edim, coords)
        stands = [n for n in nodes.get(dim, []) if n["kind"] in ("station", "cargo")]
        for st in stretches:
            cells = st["path"]
            if len(cells) < 2:
                continue
            xs = [c[0] for c in cells]
            ys = [c[1] for c in cells]
            zs = [c[2] for c in cells]
            on = stations_on_path(cells, stands)
            caption = " \u2194 ".join(on) if on else "kein Bahnhof an der Strecke"
            bucket["tracks"].append({
                "id": "track-%d" % st["eid"], "type": "line", "map": mp,
                "position": vec(sum(xs) / len(xs) + 0.5, min(ys) + RAIL_LIFT,
                                sum(zs) / len(zs) + 0.5),
                "line": [vec(c[0] + 0.5, c[1] + RAIL_LIFT, c[2] + 0.5) for c in cells],
                "label": "Gleis: %s" % caption,
                "detail": "L\u00e4nge: %d Bl\u00f6cke%s"
                          % (st["len"], "" if st["lanes"] < 2
                             else " \u00b7 %d Fahrspuren" % st["lanes"]),
                "depthTest": False, "lineWidth": 3,
                "lineColor": TRACK_COLOR, "listed": False})

    for dim, ndim in nodes.items():
        mp = DIM_TO_MAP.get(dim)
        if not mp:
            continue
        bucket = per_map.setdefault(mp, {"tracks": [], "stations": []})
        for n in ndim:
            if n["kind"] not in ("station", "cargo"):
                continue
            typ = "gueter" if n["kind"] == "cargo" else "person"
            col = STATION_COLOR["cargo" if n["kind"] == "cargo" else "station"]
            bucket["stations"].append({
                "id": "st-%s-%d" % (typ, n["nid"]), "type": "html", "map": mp,
                "position": vec(n["x"] + 0.5, n["y"] + 2.2, n["z"] + 0.5),
                "label": n["name"] or "?",
                "html": ("<div style=\"font:bold 13px sans-serif;color:%s;"
                         "white-space:nowrap;text-shadow:0 0 4px #000,"
                         "0 0 4px #000;display:flex;align-items:center;\">"
                         "<span style=\"display:inline-block;width:12px;height:12px;"
                         "border-radius:50%%;background:%s;margin-right:6px;"
                         "box-shadow:0 0 6px %s\"></span>%s</div>"
                         % (col, col, col, n["name"] or "?")),
                "anchor": {"x": 0, "y": -8}, "listed": True,
                # BlueMap's HtmlMarker spreads data.classes into classList.add()
                # -> an absent field throws ("classes is not iterable").
                "classes": []})

    written_maps = []
    for mp, bucket in per_map.items():
        # BlueMap's NormalMarkerManager walks Object.keys() of the file root:
        # the root is a DICT keyed by marker-set-id, and each set holds its
        # markers as a DICT keyed by marker-id. An array under a "markerSets"
        # key makes the app create one empty set literally named "markerSets".
        def by_id(markers):
            return {m["id"]: {k: v for k, v in m.items() if k != "map"}
                    for m in markers}

        payload = {}
        if bucket["tracks"]:
            payload["smartrail-tracks"] = {
                "id": "smartrail-tracks", "label": "Smart Rails \u00b7 Gleisnetz",
                "toggleable": True, "defaultHidden": False, "sorting": 10,
                "markers": by_id(bucket["tracks"])}
        payload["smartrail-stations"] = {
            "id": "smartrail-stations", "label": "Smart Rails \u00b7 Stationen",
            "toggleable": True, "defaultHidden": False, "sorting": 20,
            "markers": by_id(sorted(bucket["stations"], key=lambda m: m["id"]))}
        atomic_write(os.path.join(MAP_APP_DIR, "maps", mp, "live", "markers.json"),
                     json.dumps(payload, separators=(",", ":")))
        written_maps.append("%s(%d/%d)" % (
            mp, len(bucket["tracks"]), len(bucket["stations"])))

    # -------- Slim site feed --------
    feed = {"generatedUtc": stamp, "dims": {}}
    for dim in set(list(nodes.keys()) + list(edges.keys())):
        mp = DIM_TO_MAP.get(dim)
        if not mp:
            continue
        coords = {n["nid"]: (n["x"], n["y"], n["z"]) for n in nodes.get(dim, [])}
        stretches = build_stretches(edges.get(dim, []), coords)
        stands = [n for n in nodes.get(dim, []) if n["kind"] in ("station", "cargo")]
        feed["dims"][mp] = {
            "stations": [
                {"name": n["name"], "type": n["kind"],
                 "x": n["x"], "y": n["y"], "z": n["z"]}
                for n in stands],
            "tracks": [
                {"len": s["len"], "cells": s["path"],
                 "stations": stations_on_path(s["path"], stands)}
                for s in stretches]}
    atomic_write(os.path.join(SITE_DIR, "network.json"),
                 json.dumps(feed, separators=(",", ":")))

    total_tracks = sum(len(b["tracks"]) for b in per_map.values())
    total_stations = sum(len(b["stations"]) for b in per_map.values())
    print("feed ok utc=%d maps=%s total_tracks=%d total_stations=%d" % (
        stamp, ",".join(written_maps) or "-", total_tracks, total_stations))


if __name__ == "__main__":
    main()
