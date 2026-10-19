#!/usr/bin/env python3
"""Copy the original jar for compile-only use, lowering the Fabric-Loom-Version stamp so older Loom accepts it."""
import sys, zipfile, os
src, dst = sys.argv[1:3]
os.makedirs(os.path.dirname(dst), exist_ok=True)
with zipfile.ZipFile(src) as zi, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zo:
    for i in zi.infolist():
        data = zi.read(i.filename)
        if i.filename == "META-INF/MANIFEST.MF":
            data = data.decode().replace("Fabric-Loom-Version: 1.16.3", "Fabric-Loom-Version: 1.10.0").encode()
        zo.writestr(i.filename, data)
