#!/usr/bin/env python3
"""Regression test for the rail feed geometry against live DB data.

Reads an edges TSV dump (eid, dim, frm, dst, arm, len, cells) and runs the
feed's build_stretches on it. Asserts the drawn network is jump-free (no
straight chords cutting across the map) and that duplicated track rows collapse
into one stretch.

Usage: python3 feed_geometry_test.py <module.py> <edges.tsv>
"""
import importlib.util
import sys
from collections import Counter

spec = importlib.util.spec_from_file_location("feed", sys.argv[1])
feed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(feed)


def decode(enc):
    cells = []
    x = y = z = 0
    for i, tok in enumerate((enc or "").split(";")):
        if not tok:
            continue
        tx, ty, tz = (int(v) for v in tok.split(","))
        if i == 0:
            x, y, z = tx, ty, tz
        else:
            x += tx
            y += ty
            z += tz
        cells.append([x, y, z])
    return cells


edges = []
for line in open(sys.argv[2]):
    parts = line.rstrip("\n").split("\t")
    if len(parts) < 7:
        continue
    eid, dim, frm, dst, arm, ln, enc = parts[:7]
    edges.append({"eid": int(eid), "frm": int(frm), "dst": int(dst),
                  "arm": arm, "len": int(ln), "cells": decode(enc)})

stretches = feed.build_stretches(edges)
print("edges in: %d  ->  stretches drawn: %d" % (len(edges), len(stretches)))

steps = Counter()
bad = []
for st in stretches:
    p = st["path"]
    for i in range(1, len(p)):
        s = max(abs(p[i][0] - p[i - 1][0]), abs(p[i][1] - p[i - 1][1]),
                abs(p[i][2] - p[i - 1][2]))
        steps[s] += 1
        if s > 5:
            bad.append((st["eid"], i, s, tuple(p[i - 1]), tuple(p[i])))
print("step-size histogram:", dict(sorted(steps.items())))
print("steps > 5 blocks (chords):", len(bad))
for b in bad[:8]:
    print("   ", b)

multi = [(s["eid"], s["lanes"], len(s["path"])) for s in stretches if s["lanes"] > 1]
print("stretches that collapsed >1 row:", len(multi), multi[:8])

# total drawn length vs the longest member of each cluster (physics check)
tot = sum(len(s["path"]) for s in stretches)
print("total drawn cells: %d (from %d raw cells)" % (tot, sum(len(e["cells"]) for e in edges)))

# every drawn line must still start and end at a real endpoint (no teleport):
anchors = feed.infer_node_anchors(edges)
drift = []
for st in stretches:
    for cell, nid in ((st["path"][0], st["frm"]), (st["path"][-1], st["dst"])):
        a = anchors.get(nid)
        if a:
            d = max(abs(cell[0] - a[0]), abs(cell[2] - a[2]))
            if d > 4:
                drift.append((st["eid"], nid, tuple(cell), tuple(a), d))
print("ends further than 4 blocks from their anchor:", drift)
print("OK" if not bad and not drift else "PROBLEM")
