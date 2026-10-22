#!/usr/bin/env python3
"""Independently verifies rotation-aware overlay placement by RENDERING the
output PDFs and checking pixel colours with PyMuPDF."""
import os, sys, tempfile
try:
    import pymupdf
except ImportError:
    import fitz as pymupdf

base = os.path.join(tempfile.gettempdir(), "pdfsmith-engine-test")
ok = True
for deg in (90, 180, 270):
    path = os.path.join(base, f"stamped{deg}.pdf")
    if not os.path.exists(path):
        print(f"  FAIL  missing {path}")
        ok = False
        continue
    doc = pymupdf.open(path)
    page = doc[0]
    pix = page.get_pixmap(dpi=72)
    w, h = pix.width, pix.height
    tl = pix.pixel(int(w * 0.10), int(h * 0.10))
    tr = pix.pixel(int(w * 0.90), int(h * 0.10))
    bl = pix.pixel(int(w * 0.10), int(h * 0.90))
    br = pix.pixel(int(w * 0.90), int(h * 0.90))
    good = tl == (0, 0, 255) and tr == (255, 255, 255) and bl == (255, 255, 255) and br == (255, 255, 255)
    print(f"  {'PASS' if good else 'FAIL'}  overlay lands correctly on a {deg}-rotated page "
          f"(rot={page.rotation}, {w}x{h})")
    ok = ok and good
sys.exit(0 if ok else 1)
