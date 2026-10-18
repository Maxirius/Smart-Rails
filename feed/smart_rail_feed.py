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

import heapq
import json
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
TRAFFIC = ("person", "cargo", "mixed", "none")
TRACK_LABEL = {
    "person": "Personenverkehr",
    "cargo": "Warenverkehr",
    "mixed": "gemischt genutzt",
    "none": "ohne Durchgangsverkehr",
}
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


def classify_traffic(edge_list, station_list, radius=4, dy=3):
    """Label every edge with the traffic that routes across it.

    Stations are attached to graph nodes by coordinate proximity (the published
    `nodes` table and the `edges` endpoints come from different publishes, so
    their ids are NOT comparable -- coordinates are). For each group of stations
    (passenger vs. cargo) every shortest station-to-station path is walked and
    its edges collected. Result: eid -> 'person' | 'cargo' | 'mixed' | 'none'.
    """
    def near(cell, st):
        return (abs(cell[0] - st["x"]) <= radius
                and abs(cell[2] - st["z"]) <= radius
                and abs(cell[1] - st["y"]) <= dy)

    endpoints = {}
    for e in edge_list:
        if not e["cells"]:
            continue
        endpoints.setdefault(e["frm"], []).append(e["cells"][0])
        endpoints.setdefault(e["dst"], []).append(e["cells"][-1])

    linked = {"person": [], "cargo": []}
    for st in station_list:
        best, best_d = None, None
        for nid, cells in endpoints.items():
            for c in cells:
                if near(c, st):
                    d = abs(c[0] - st["x"]) + abs(c[2] - st["z"])
                    if best_d is None or d < best_d:
                        best, best_d = nid, d
        if best is not None:
            kind = "cargo" if st["kind"] == "cargo" else "person"
            linked[kind].append(best)

    adj = {}
    for e in edge_list:
        adj.setdefault(e["frm"], []).append((e["dst"], e["len"], e["eid"]))
        adj.setdefault(e["dst"], []).append((e["frm"], e["len"], e["eid"]))

    def path_edges(src, dst):
        dist = {src: 0}
        prev = {}
        queue = [(0, src)]
        while queue:
            d, u = heapq.heappop(queue)
            if d > dist.get(u, 1 << 62):
                continue
            for v, w, eid in adj.get(u, ()):
                nd = d + w
                if nd < dist.get(v, 1 << 62):
                    dist[v] = nd
                    prev[v] = (u, eid)
                    heapq.heappush(queue, (nd, v))
        out = set()
        cur = dst
        while cur in prev:
            up, eid = prev[cur]
            out.add(eid)
            cur = up
        return out

    used = {"person": set(), "cargo": set()}
    for grp, sts in linked.items():
        for i in range(len(sts)):
            for j in range(i + 1, len(sts)):
                used[grp] |= path_edges(sts[i], sts[j])

    labels = {}
    for e in edge_list:
        p = e["eid"] in used["person"]
        c = e["eid"] in used["cargo"]
        labels[e["eid"]] = ("mixed" if p and c else
                            "person" if p else "cargo" if c else "none")
    return labels, {k: len(v) for k, v in linked.items()}


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
        labels, _ = classify_traffic(
            edim, [n for n in nodes.get(dim, [])
                   if n["kind"] in ("station", "cargo")])
        for e in edim:
            cells = e["cells"]
            if len(cells) < 2:
                continue
            xs = [c[0] for c in cells]
            zs = [c[2] for c in cells]
            ys = [c[1] for c in cells]
            cls = labels.get(e["eid"], "none")
            bucket["tracks"].setdefault(cls, []).append({
                "id": "track-%d" % e["eid"], "type": "line", "map": mp,
                "position": vec(sum(xs) / len(xs) + 0.5, min(ys) + 1.6,
                                sum(zs) / len(zs) + 0.5),
                "line": [vec(c[0] + 0.5, c[1] + 0.6, c[2] + 0.5) for c in cells],
                "label": "%s: %s \u2192 #%d" % (TRACK_LABEL[cls], e["arm"].upper(), e["dst"]),
                "detail": "L\u00e4nge: %d Bl\u00f6cke" % e["len"],
                "depthTest": False, "lineWidth": 3,
                "lineColor": TRACK_COLOR[cls], "listed": False})

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
        traffic = classify_traffic(
            edges.get(dim, []),
            [n for n in nodes.get(dim, []) if n["kind"] in ("station", "cargo")])[0]
        feed["dims"][mp] = {
            "stations": [
                {"name": n["name"], "type": n["kind"],
                 "x": n["x"], "y": n["y"], "z": n["z"]}
                for n in nodes.get(dim, [])
                if n["kind"] in ("station", "cargo")],
            "tracks": [
                {"len": e["len"], "cells": e["cells"],
                 "traffic": traffic.get(e["eid"], "none")}
                for e in edges.get(dim, [])]}
    atomic_write(os.path.join(SITE_DIR, "network.json"),
                 json.dumps(feed, separators=(",", ":")))

    total_tracks = sum(len(v) for b in per_map.values()
                       for v in b["tracks"].values())
    total_stations = sum(len(b["stations"]) for b in per_map.values())
    print("feed ok utc=%d maps=%s total_tracks=%d total_stations=%d" % (
        stamp, ",".join(written_maps) or "-", total_tracks, total_stations))


if __name__ == "__main__":
    main()
