#!/usr/bin/env python3
"""Overlay the compiled add-on onto the original Cinematic Interactions jar and make it client-only."""
import json, sys, zipfile

orig, addon, out = sys.argv[1:4]
KEEP = ("dev/dyslexic/cinematic/emu/",)
KEEP_FILES = {"cinematic_client.mixins.json", "cinematic_client-refmap.json"}

with zipfile.ZipFile(orig) as zo, zipfile.ZipFile(addon) as za, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
    for info in zo.infolist():
        if info.filename == "fabric.mod.json" or info.filename.endswith("/"):
            continue
        zout.writestr(info.filename, zo.read(info.filename))
    added = 0
    for info in za.infolist():
        n = info.filename
        if n.endswith("/"):
            continue
        if n.startswith(KEEP) or n in KEEP_FILES:
            zout.writestr(n, za.read(n))
            added += 1
    mod = json.loads(zo.read("fabric.mod.json"))
    mod["environment"] = "client"
    mod["version"] = mod["version"] + "+client"
    mod["name"] = mod["name"] + " (client-side)"
    mod["description"] = mod.get("description", "") + " Patched to also work on servers without the mod."
    mod["mixins"] = list(mod.get("mixins", [])) + ["cinematic_client.mixins.json"]
    mod["entrypoints"]["client"] = list(mod["entrypoints"]["client"]) + ["dev.dyslexic.cinematic.emu.EmuInit"]
    zout.writestr("fabric.mod.json", json.dumps(mod, indent=2, ensure_ascii=False))
    print("added", added, "files ->", out)
