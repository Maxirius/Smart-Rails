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
TRAFFIC = ("person", "cargo", "none")
TRACK_LABEL = {
    "person": "Personenverkehr",
    "cargo": "Warenverkehr",
    "mixed": "gemeinsame Strecke",
    "none": "ohne Bahnhofsanbindung",
}
# Every line sits exactly on the rails (no lateral offset: parallel lines broke
# the junctions and drew the same stretch twice). A shared stretch stacks the
# freight line at the rail and the passenger line just above it.
RAIL_LIFT = 0.6
RAIL_STACK = 0.35
TRACK_COLOR = {
    "person": {"r": 34, "g": 199, "b": 255, "a": 0.95},
    "cargo": {"r": 255, "g": 140, "b": 42, "a": 0.95},
    "mixed": {"r": 179, "g": 136, "b": 255, "a": 0.9},
    "none": {"r": 120, "g": 131, "b": 143, "a": 0.8},
}
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


def classify_stretches(stretches, station_list, on_radius=6, on_dy=3,
                       attach_radius=4, attach_dy=3, hops=40):
    """Label every stretch with the service it carries.

    A station counts as a reference for a stretch when it lies ON the stretch
    (same coordinate-proximity rule the mod itself uses to attach a station to
    the track: the published `nodes` rows and the `edges` endpoints come from
    different publishes, so their ids are NOT comparable -- coordinates are).

    Only when no station lies on the stretch do we fall back to the nearest
    station reachable from either end, so an ordinary stretch between two
    crossings is attributed to the stations it leads to instead of turning into
    an unattributed grey line (the reason the first shortest-path model produced
    "no through traffic" on perfectly normal passenger track).
    """
    def d2(a, b):
        return (a[0] - b[0]) ** 2 + (a[2] - b[2]) ** 2

    def on_it(path, x, y, z):
        return any(abs(c[0] - x) <= on_radius and abs(c[2] - z) <= on_radius
                   and abs(c[1] - y) <= on_dy for c in path)

    # Station -> stretch node (by coordinate proximity, per station)
    attached = {}
    for st in station_list:
        best, best_d = None, None
        for s in stretches:
            for nid, cell in ((s["frm"], s["path"][0]), (s["dst"], s["path"][-1])):
                if (abs(cell[0] - st["x"]) <= attach_radius
                        and abs(cell[2] - st["z"]) <= attach_radius
                        and abs(cell[1] - st["y"]) <= attach_dy):
                    d = abs(cell[0] - st["x"]) + abs(cell[2] - st["z"])
                    if best_d is None or d < best_d:
                        best, best_d = nid, d
        if best is not None:
            attached[best] = (st["name"], "cargo" if st["kind"] == "cargo" else "person")

    adj = {}
    for s in stretches:
        adj.setdefault(s["frm"], set()).add(s["dst"])
        adj.setdefault(s["dst"], set()).add(s["frm"])
    node_cell = {}
    for s in stretches:
        node_cell.setdefault(s["frm"], s["path"][0])
        node_cell.setdefault(s["dst"], s["path"][-1])

    def nearest_station(node):
        seen = {node: 0}
        queue = [node]
        seen_stations = []
        while queue:
            nxt = []
            for u in queue:
                if u in attached:
                    seen_stations.append((u,) + attached[u])
                if seen[u] >= hops:
                    continue
                for v in adj.get(u, ()):
                    if v not in seen:
                        seen[v] = seen[u] + 1
                        nxt.append(v)
            queue = nxt
        if not seen_stations:
            return None
        here = node_cell[node]
        return min(seen_stations,
                   key=lambda t: d2(here, node_cell[t[0]]))

    labels = {}
    served = {}
    for s in stretches:
        refs = [(st["name"], "cargo" if st["kind"] == "cargo" else "person")
                for st in station_list
                if on_it(s["path"], st["x"], st["y"], st["z"])]
        if not refs:
            for node in (s["frm"], s["dst"]):
                near = nearest_station(node)
                if near:
                    refs.append((near[1], near[2]))
        kinds = {kind for _, kind in refs}
        if not kinds:
            labels[s["eid"]] = "none"
        elif len(kinds) > 1:
            labels[s["eid"]] = "mixed"
        else:
            labels[s["eid"]] = kinds.pop()
        served[s["eid"]] = sorted({name for name, _ in refs if name})

    counts = {kind: 0 for kind in ("person", "cargo")}
    for value in attached.values():
        counts[value[1]] += 1
    return labels, counts, served


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
        bucket = per_map.setdefault(mp, {"tracks": {}, "stations": []})
        coords = {n["nid"]: (n["x"], n["y"], n["z"]) for n in nodes.get(dim, [])}
        stretches = build_stretches(edim, coords)
        labels, _, served = classify_stretches(
            stretches, [n for n in nodes.get(dim, [])
                        if n["kind"] in ("station", "cargo")])
        for st in stretches:
            cells = st["path"]
            if len(cells) < 2:
                continue
            cls = labels.get(st["eid"], "none")
            xs = [c[0] for c in cells]
            zs = [c[2] for c in cells]
            ys = [c[1] for c in cells]
            # One line, exactly on the rails. On a shared stretch the freight
            # line is drawn at the rail and the passenger line just above it, so
            # passenger traffic covers freight instead of standing beside it.
            if cls == "mixed":
                parts = [("cargo", RAIL_LIFT), ("person", RAIL_LIFT + RAIL_STACK)]
            else:
                parts = [(cls, RAIL_LIFT)]
            ends = served.get(st["eid"]) or []
            caption = " \u2194 ".join(ends) if ends else ("Weiche %d \u2192 %d" % (st["frm"], st["dst"]))
            for part, lift in parts:
                suffix = "" if cls != "mixed" else ("r" if part == "cargo" else "l")
                # On a shared stretch the freight line is the wider underlay, so
                # the passenger line on top still leaves an orange rim visible.
                width = 5 if (cls == "mixed" and part == "cargo") else 3
                bucket["tracks"].setdefault(part, []).append({
                    "id": "track-%d%s" % (st["eid"], suffix), "type": "line", "map": mp,
                    # BlueMap places a line at the MARKER's height, so stacking
                    # (freight underlay, passenger on top) has to happen here.
                    "position": vec(sum(xs) / len(xs) + 0.5, min(ys) + lift,
                                    sum(zs) / len(zs) + 0.5),
                    "line": [vec(c[0] + 0.5, c[1] + lift, c[2] + 0.5) for c in cells],
                    "label": "%s: %s" % (TRACK_LABEL[cls], caption),
                    "detail": ("L\u00e4nge: %d Bl\u00f6cke%s"
                               % (st["len"], "" if st["lanes"] < 2
                                  else " \u00b7 %d Fahrspuren" % st["lanes"])),
                    "depthTest": False, "lineWidth": width,
                    "lineColor": TRACK_COLOR[part], "listed": False})

    for dim, ndim in nodes.items():
        mp = DIM_TO_MAP.get(dim)
        if not mp:
            continue
        bucket = per_map.setdefault(mp, {"tracks": {}, "stations": []})
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
        for sorting, cls in enumerate(TRAFFIC):
            markers = bucket["tracks"].get(cls) or []
            if not markers:          # keep the menu clean: only non-empty sets
                continue
            sid = "smartrail-track-" + cls
            payload[sid] = {
                "id": sid, "label": "Smart Rails \u00b7 Gleise: " + TRACK_LABEL[cls],
                "toggleable": True, "defaultHidden": False,
                "sorting": 10 + sorting, "markers": by_id(markers)}
        payload["smartrail-stations"] = {
            "id": "smartrail-stations", "label": "Smart Rails \u00b7 Stationen",
            "toggleable": True, "defaultHidden": False, "sorting": 20,
            "markers": by_id(sorted(bucket["stations"], key=lambda m: m["id"]))}
        atomic_write(os.path.join(MAP_APP_DIR, "maps", mp, "live", "markers.json"),
                     json.dumps(payload, separators=(",", ":")))
        written_maps.append("%s(%d/%d)" % (
            mp, sum(len(v) for v in bucket["tracks"].values()),
            len(bucket["stations"])))

    # -------- Slim site feed --------
    feed = {"generatedUtc": stamp, "dims": {}}
    for dim in set(list(nodes.keys()) + list(edges.keys())):
        mp = DIM_TO_MAP.get(dim)
        if not mp:
            continue
        coords = {n["nid"]: (n["x"], n["y"], n["z"]) for n in nodes.get(dim, [])}
        stretches = build_stretches(edges.get(dim, []), coords)
        traffic, _, _ = classify_stretches(
            stretches,
            [n for n in nodes.get(dim, []) if n["kind"] in ("station", "cargo")])
        feed["dims"][mp] = {
            "stations": [
                {"name": n["name"], "type": n["kind"],
                 "x": n["x"], "y": n["y"], "z": n["z"]}
                for n in nodes.get(dim, [])
                if n["kind"] in ("station", "cargo")],
            "tracks": [
                {"len": s["len"], "cells": s["path"],
                 "traffic": traffic.get(s["eid"], "none")}
                for s in stretches]}
    atomic_write(os.path.join(SITE_DIR, "network.json"),
                 json.dumps(feed, separators=(",", ":")))

    total_tracks = sum(len(v) for b in per_map.values()
                       for v in b["tracks"].values())
    total_stations = sum(len(b["stations"]) for b in per_map.values())
    print("feed ok utc=%d maps=%s total_tracks=%d total_stations=%d" % (
        stamp, ",".join(written_maps) or "-", total_tracks, total_stations))


if __name__ == "__main__":
    main()
