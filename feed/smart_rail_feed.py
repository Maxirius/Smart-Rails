#!/usr/bin/env python3
"""Smart Rails network feed: MariaDB -> BlueMap marker files + site network.json.

Runs on the Netcup RS (root cron, every minute). Reads the topology tables the
mod publishes (db smartrail, user smartrail_writer) via the local mariadb
container and regenerates:

  * one BlueMap marker file PER MAP at map_app/maps/<mapid>/live/markers.json
    (the webapp resolves its marker file as "<liveDataRoot>/<mapid>/live/
    markers.json" -- verified against the shipped frontend bundle). Each file
    carries the rail network plus the community waypoints the website stores
    in smartrail.waypoints, and
  * a slim network.json for the welcome page's rail-network view.

Coordinate contract (verified in the frontend bundle): marker "position" is an
OBJECT {x,y,z}, and every entry of a line marker's "line" array is an OBJECT
{x,y,z} as well -- arrays are silently read as {0,0,0} and collapse the
geometry to the origin.

Writes are atomic (tmp + rename). A database read failure exits before any
write, so an outage preserves the previous artifacts. Every known map is
written on every run: dropping a map from the payload (e.g. after the last
waypoint of a dimension was deleted) would leave the previous file -- with
the deleted pin still in it -- in place.
"""

import collections
import html
import json
import math
import os
import re
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


# ---------------------------------------------------------------------------
# Community waypoints (set on the website, stored in smartrail.waypoints)
# ---------------------------------------------------------------------------

WP_DEFAULT_COLOR = "#77cf52"

# One pin for a waypoint. Keep the SVG path and inline styles in sync with the
# client-side preview pin in map_app/js/waypoints.js -- the preview is meant
# to be visually indistinguishable from the server-rendered marker.
PIN_SVG = ('<svg width="18" height="24" viewBox="0 0 18 24" aria-hidden="true">'
           '<path d="M9 0C4.03 0 0 4.03 0 9c0 6.6 9 15 9 15s9-8.4 9-15c0-4.97-4.03-9-9-9z" '
           'fill="%s" stroke="#101418" stroke-width="1" stroke-linejoin="round"/>'
           '<circle cx="9" cy="9" r="3.2" fill="#101418"/></svg>')

WP_ANCHOR = {"x": 9, "y": 24}


def wp_esc(text):
    return html.escape(str(text), quote=True)


def wp_pin_html(name, color):
    col = color if re.fullmatch(r"#[0-9a-fA-F]{6}", color or "") else WP_DEFAULT_COLOR
    return ('<div style="display:flex;flex-direction:column;align-items:center;'
            'cursor:pointer">' + PIN_SVG % col
            + '<span style="font:bold 12px sans-serif;color:%s;white-space:nowrap;'
              'text-shadow:0 0 4px #000,0 0 4px #000">%s</span></div>'
              % (col, wp_esc(name)))


def read_waypoints(env):
    """Rows from smartrail.waypoints; [] until the website created the table.

    A missing table (MariaDB error 1146) is a normal pre-deployment state and
    must never stop the rail feed.
    """
    try:
        out = query("SELECT id,dim,name,x,y,z,color,owner FROM waypoints;", env)
    except RuntimeError as exc:
        if "1146" in str(exc) or "doesn't exist" in str(exc):
            return []
        raise
    wps = []
    for line in out.splitlines():
        if not line.strip():
            continue
        parts = (line.split("\t") + [""] * 8)[:8]
        wid, dim, name, x, y, z, color, owner = parts
        try:
            wps.append({"id": int(wid), "dim": dim, "name": name or "?",
                        "x": int(x), "y": int(y), "z": int(z),
                        "color": color or WP_DEFAULT_COLOR, "owner": owner or ""})
        except ValueError:
            continue
    return wps


def infer_node_anchors(edge_list):
    """Node position inferred from the geometry itself.

    The endpoint cell that most edges referencing a node id agree on. The nodes
    table has proven unreliable for this purpose: it is missing ids that the
    edges reference (18..27) and holds stale coordinates for others, so snapping
    to it dragged stretch ends across the map (up to 52 blocks, seen 10/26).
    The cells are the physical truth; use them.
    """
    counters = {}
    for e in edge_list:
        if not e["cells"]:
            continue
        counters.setdefault(e["frm"], collections.Counter())[tuple(e["cells"][0])] += 1
        counters.setdefault(e["dst"], collections.Counter())[tuple(e["cells"][-1])] += 1
    return {nid: list(c.most_common(1)[0][0]) for nid, c in counters.items()}


def _cluster_lanes(edge_list):
    """Group edges that describe the SAME physical track.

    Original rule was the node pair (frm, dst), which breaks as soon as a graph
    rebuild leaves a second generation of rows behind: the same track then
    appears twice with different ids and was drawn twice, a block apart. Group
    by geometry instead -- cell sets that agree apart from the ends (lanes of
    one track) or where one is contained in the other (partial re-recordings).
    """
    sets = [set(map(tuple, e["cells"])) for e in edge_list]
    parent = list(range(len(edge_list)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    def union(i, j):
        ri, rj = find(i), find(j)
        if ri != rj:
            parent[max(ri, rj)] = min(ri, rj)

    for i in range(len(sets)):
        for j in range(i + 1, len(sets)):
            a, b = (i, j) if len(sets[i]) <= len(sets[j]) else (j, i)
            lo, hi = len(sets[a]), len(sets[b])
            if lo < 2:
                continue
            inter = len(sets[a] & sets[b])
            lanes = inter >= max(2, lo - 1) and lo >= 0.5 * hi
            contained = inter >= max(3, lo) and hi >= lo
            if lanes or contained:
                union(i, j)

    groups = {}
    for i, e in enumerate(edge_list):
        groups.setdefault(find(i), []).append(e)
    return [g for g in groups.values() if any(len(e["cells"]) >= 2 for e in g)]


def build_stretches(edge_list, node_coords=None, snap_cap=4.0):
    """Collapse the per-direction lane edges into one PHYSICAL stretch.

    Right-hand traffic stores one edge per direction for the same piece of track
    and those lanes are not parallel rails: they overlap cell for cell and differ
    by about one cell at the ends (the same applies to rows left over from an
    earlier graph build). The stretch keeps the geometry of its LONGEST member
    verbatim -- the line sits exactly on the rails, no lateral offset -- and
    snaps only its two end cells onto the node anchor, never more than
    snap_cap blocks: a stale node row must not drag an end across the map.
    """
    groups = _cluster_lanes(edge_list)
    if not groups:
        return []

    anchors = infer_node_anchors(edge_list) if node_coords is None else node_coords
    recorded = set()
    for e in edge_list:
        recorded.update(map(tuple, e["cells"]))

    out = []
    for members in sorted(groups, key=lambda g: min(e["eid"] for e in g)):
        base = min(members, key=lambda l: (-len(l["cells"]), l["eid"]))
        path = [list(c) for c in base["cells"]]
        for end, nid in ((0, base["frm"]), (-1, base["dst"])):
            anchor = anchors.get(nid)
            if not anchor or len(path) < 2:
                continue
            if math.dist(path[end], anchor) > snap_cap:
                continue
            neighbour = path[1] if end == 0 else path[-2]
            if (abs(anchor[0] - neighbour[0]) > 0.5
                    and abs(anchor[2] - neighbour[2]) > 0.5):
                # The anchor sits diagonally next to the end cell. A direct snap
                # would paint a 45 degree kink that no rail can produce, so walk
                # the corner instead: the connection stays 90 degrees and the
                # line still reaches the junction. Prefer a corner cell that is
                # real recorded track; otherwise continue the line's own axis.
                ex, ey, ez = path[end]
                prefer = 0 if abs(neighbour[0] - ex) > 0.5 else 1
                corners = [(anchor[0], ey, ez), (ex, ey, anchor[2])]
                corner = None
                for i in (prefer, 1 - prefer):
                    if corners[i] in recorded:
                        corner = corners[i]
                        break
                if corner is None:
                    corner = corners[prefer]
                bridge = [] if tuple(corner) == tuple(anchor) else [list(corner)]
                if end == 0:
                    path[:0] = [list(anchor)] + bridge
                else:
                    path.extend(bridge + [list(anchor)])
            elif math.dist(path[end], anchor) > 0.01:
                path[end] = list(anchor)
        deduped = [path[0]]
        for cell in path[1:]:
            if cell != deduped[-1]:
                deduped.append(cell)
        out.append({"eid": base["eid"], "frm": base["frm"], "dst": base["dst"],
                    "path": deduped, "lanes": len(members),
                    "len": max(l["len"] for l in members), "arm": base["arm"]})
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

    waypoints = read_waypoints(env)

    # -------- BlueMap markers, grouped per map --------
    # Every known map is seeded: files are written unconditionally so that a
    # map emptying out (last waypoint deleted) is rewritten and the stale pin
    # disappears. A failed db read exits before any write, so this never
    # blanks a map during an outage.
    per_map = {mp: {"tracks": [], "stations": [], "waypoints": []}
               for mp in DIM_TO_MAP.values()}

    for w in waypoints:
        mp = DIM_TO_MAP.get(w["dim"])
        if mp:
            per_map[mp]["waypoints"].append(w)

    for dim, edim in edges.items():
        mp = DIM_TO_MAP.get(dim)
        if not mp:
            continue
        bucket = per_map[mp]
        # Anchors come from the CELLS, not from the nodes table: the table has
        # been unreliable (stale rows / ids the edges use but it lacks), and a
        # bad anchor used to drag a stretch end across the map.
        stretches = build_stretches(edim)
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
        bucket = per_map[mp]
        anchors = infer_node_anchors(edges.get(dim, []))
        for n in ndim:
            if n["kind"] not in ("station", "cargo"):
                continue
            typ = "gueter" if n["kind"] == "cargo" else "person"
            col = STATION_COLOR["cargo" if n["kind"] == "cargo" else "station"]
            # Prefer the anchor the track actually ends on, so the pin sits on
            # the line even when the nodes table row is off by a block or two.
            ax, ay, az = anchors.get(n["nid"], (n["x"], n["y"], n["z"]))
            bucket["stations"].append({
                "id": "st-%s-%d" % (typ, n["nid"]), "type": "html", "map": mp,
                "position": vec(ax + 0.5, ay + 2.2, az + 0.5),
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
        if bucket["stations"]:
            payload["smartrail-stations"] = {
                "id": "smartrail-stations", "label": "Smart Rails \u00b7 Stationen",
                "toggleable": True, "defaultHidden": False, "sorting": 20,
                "markers": by_id(sorted(bucket["stations"], key=lambda m: m["id"]))}
        if bucket["waypoints"]:
            payload["waypoints"] = {
                "id": "waypoints", "label": "Waypoints",
                "toggleable": True, "defaultHidden": False, "sorting": 30,
                "markers": by_id(sorted(
                    ({"id": "wp-%d" % w["id"], "type": "html", "map": mp,
                      "position": vec(w["x"] + 0.5, w["y"], w["z"] + 0.5),
                      "label": w["name"],
                      "detail": "von %s \u00b7 %d/%d/%d" % (
                          wp_esc(w["owner"]) or "?", w["x"], w["y"], w["z"]),
                      "html": wp_pin_html(w["name"], w["color"]),
                      "anchor": dict(WP_ANCHOR),
                      "listed": True, "classes": []}
                     for w in bucket["waypoints"]),
                    key=lambda m: m["id"]))}
        atomic_write(os.path.join(MAP_APP_DIR, "maps", mp, "live", "markers.json"),
                     json.dumps(payload, separators=(",", ":")))
        written_maps.append("%s(%d/%d/+%d)" % (
            mp, len(bucket["tracks"]), len(bucket["stations"]),
            len(bucket["waypoints"])))

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
    total_waypoints = sum(len(b["waypoints"]) for b in per_map.values())
    print("feed ok utc=%d maps=%s total_tracks=%d total_stations=%d total_waypoints=%d" % (
        stamp, ",".join(written_maps) or "-", total_tracks, total_stations,
        total_waypoints))


if __name__ == "__main__":
    main()
