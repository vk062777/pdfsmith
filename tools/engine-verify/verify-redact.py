#!/usr/bin/env python3
"""Verifies a redaction really removed the content.

This is the whole point of the feature: marking an area is not enough, the text must
be gone from the file. Checks:
  1. nothing secret is extractable as text
  2. nothing secret survives in ANY decompressed stream in the file
  3. pages that were not redacted keep their text
  4. page count and page sizes are unchanged
  5. the redacted areas render as solid black
"""
import os, sys, zlib
import numpy as np
import pymupdf

W = "/tmp/pdfsmith-engine-test"
SECRETS = ["SECRETCODE-9931", "TOPSECRET-ALPHA"]
K = 150 / 72.0

src = pymupdf.open(os.path.join(W, "redact_src.pdf"))
out = pymupdf.open(os.path.join(W, "redact_out.pdf"))
ok = True

for i in range(len(out)):
    t = out[i].get_text()
    for s in SECRETS:
        if s in t:
            print(f"  FAIL  '{s}' is still extractable on page {i+1}")
            ok = False
print("  PASS  nothing secret is extractable" if ok else "")

found = []
for xref in range(1, out.xref_length()):
    try:
        raw = out.xref_stream_raw(xref)
        if not raw:
            continue
        try:
            data = zlib.decompress(raw)
        except Exception:
            data = raw
        for s in SECRETS:
            if s.encode() in data:
                found.append((xref, s))
    except Exception:
        pass
if found:
    print(f"  FAIL  secrets still present in streams: {found}")
    ok = False
else:
    print("  PASS  no secret survives in any stream of the file")

good = "Public info on page two" in out[1].get_text()
ok = ok and good
print(f"  {'PASS' if good else 'FAIL'}  unredacted page keeps its text")

sizes_src = [(round(src[i].rect.width), round(src[i].rect.height)) for i in range(len(src))]
sizes_out = [(round(out[i].rect.width), round(out[i].rect.height)) for i in range(len(out))]
good = len(src) == len(out) and sizes_src == sizes_out
ok = ok and good
print(f"  {'PASS' if good else 'FAIL'}  page count and sizes unchanged {sizes_src} -> {sizes_out}")

for i, rects in [(0, [(60, 125, 360, 155)]), (2, [(690, 65, 725, 230)])]:
    p = out[i]
    px = p.get_pixmap(dpi=150, colorspace=pymupdf.csGRAY)
    a = np.frombuffer(px.samples, dtype=np.uint8).reshape(px.height, px.width)
    for (x0, y0, x1, y1) in rects:
        reg = a[int(y0 * K):int(y1 * K), int(x0 * K):int(x1 * K)]
        dark = float((reg < 40).mean())
        good = dark > 0.95
        ok = ok and good
        print(f"  {'PASS' if good else 'FAIL'}  page {i+1} redacted area is {dark*100:.1f}% solid black")

print("REDACTION:", "PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
