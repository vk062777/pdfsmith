# NovaPDF

**A serious, offline-first PDF editor for Android.** Everything runs on the device —
no accounts, no uploads, no network permission. Open any PDF and read it with smooth
pinch-zoom, then actually edit it: merge, split, reorganise pages by dragging them,
annotate, sign, fill forms, watermark, protect, compress and more.

Package: `com.vk062777.pdfsmith` · Display name: **NovaPDF** · Licence: Apache-2.0

> The package id is kept stable so the app upgrades cleanly in place and the signing
> key stays valid. It can be renamed before the first Play Store upload on request.

---


## What's new in 1.2.0

- **New logo** built from the supplied artwork (corners masked so no white shows).
- **Animations** — light activity transitions, staggered card entry, press feedback,
  fade-in pages, and an animated **success popup** (Open / Share) after every task.
- **Accent themes** — Indigo, Teal, Violet, Green, Crimson, alongside light/dark.
- **Create PDF** — build a document from scratch: page size, orientation, page
  colour, multiple pages, text, images, highlights and freehand drawing.
- **Digital signatures** — real PKCS#7/CMS detached signatures with a generated
  X.509 certificate, plus **verification** that detects any change made after signing.
- **Rotate everywhere** — reader, page organiser, a dedicated Rotate tool, and
  inside Annotate (annotations rotate with the page).
- **Richer metadata** — creator, producer, creation/modification dates, and a
  "clear all metadata" action.
- Smoother scrolling and less memory pressure in the reader and page grid.


## What's new in 1.3.0

- **Faster opening** — encryption is now detected by reading the PDF trailer instead
  of parsing the whole document, so files open immediately.
- **Password-protected PDFs now open** — the app asks for the password and unlocks
  the document for viewing, annotating and editing.
- **Crop page** (Edit category) — drag the corners to trim a page's margins and save
  it in place, on one page or every page.
- **Zoom fixed everywhere** — zooming no longer snaps back to the original position,
  and pages re-render sharper as you zoom instead of going blurry.
- **Annotate rotate is instant** and the toolbar actions (undo, redo, rotate, save)
  are proper icons.
- **Split** can now make one file per page; **Extract images** can be limited to a
  page range; **page numbers** gained an opacity option.
- **Watermark** moved to the Organise category; the redundant Rotate tool was removed.
- **New logo** built from the supplied green artwork.
- Rounder corners, tooltips on every icon button, proper chevron/share icons, and
  security hardening (no backups, cache cleaned automatically, no cleartext traffic).


## What's new in 1.4.0 — stability pass

A full audit of the whole app, with every issue found fixed in one go:

- **Your work no longer disappears when the phone rotates.** Annotate, Organize
  pages, Merge, Images→PDF, Forms and Tools kept their state in memory only, so
  turning the device (or unfolding it, or resizing the window) threw away the
  loaded document, the annotations and the undo history. They now survive.
- **Stronger protection** — password-protected PDFs now use **AES-256** (was
  RC4-128, which is obsolete). Printing and text extraction stay allowed; editing
  and annotating the protected file do not.
- **Fixed a file-descriptor leak** in Crop page (the renderer and file handle were
  never closed, so opening it many times could eventually exhaust handles).
- **Fixed a crash risk** when a progress or success dialog tried to appear while a
  screen was closing.
- **Fixed a crash** on PDFs that report a zero-size page, and capped annotation
  export so a malformed page can never exhaust memory.
- The chosen output folder now keeps working after the app restarts.
- Removed dead code, and a stray namespace in a dialog layout.

Every build still runs 80 engine checks, plus rendered pixel checks for rotation
and cropping, and a new check that confirms the encryption really is AES-256 and
that a wrong password is rejected.


## What's new in 1.5.0 — crash & hostile-input audit

A second, deeper pass aimed specifically at *how could this app crash* and *how
could it be attacked*. Everything found was fixed:

- **A crafted input could freeze or kill the app.** Typing a page range like
  `1-999999999` in Extract images built a list of a billion entries. Ranges are
  now clamped to the real page count.
- **A hostile PDF could exhaust memory.** Images whose declared size is absurd are
  no longer decoded, and a malformed signature byte range is rejected before it
  reaches the parser (previously it was handed over unchecked).
- **Deeply nested PDF structures** (nested form XObjects, nested form fields) could
  overflow the stack. Recursion is now depth-limited.
- **A PDF with thousands of form fields** would build thousands of views. Capped,
  with a clear note.
- **Zero-size pages** no longer cause a divide-by-zero in the renderer.
- **`keepPages` with an unknown page** threw an unhandled exception; it now ignores
  out-of-range pages and refuses clearly if nothing valid is left.
- **Security:** the shared-file provider now exposes *only* the app's own working
  folder instead of the entire cache/files directories, and anything opened — 
  including via the "open with" entry point that other apps can call — must
  actually be a PDF.

### Crash audit — what was checked

Every screen, every engine entry point and every input path was reviewed for null
dereferences, index errors, divide-by-zero, unclosed resources, dialogs shown on a
dead screen, unbounded allocations and unbounded recursion. Findings are listed
above; the rest was already safe.

### Attack surface — the honest summary

- The app requests **no permissions at all** — no internet, no storage, no camera.
  It cannot send data anywhere.
- Only two components are reachable by other apps: the launcher and the "open with"
  PDF viewer. Everything else is `exported="false"`.
- Shared files go out through a non-exported FileProvider as `content://` URIs, and
  only from the app's own working folder.
- The residual risk is the PDF parser itself — the same risk every PDF app carries.
  The new guards make malformed and hostile files fail safely rather than crash.

A new hostile-input test suite runs in CI alongside the 80 engine checks, the
rendered rotation/crop checks and the AES-256 check.


## What's new in 1.6.0 — on-device OCR: make scanned PDFs searchable

Scanned PDFs are just pictures — you cannot search them or copy text out. This
release adds a **Make searchable (OCR)** tool that reads each page on your phone
and writes the text back invisibly.

- **Runs entirely on-device.** The recogniser is ML Kit's bundled Latin model:
  it ships inside the APK, so it works offline, needs no Google Play Services,
  and **adds no permissions** — the app still requests nothing at all.
- **The page does not change.** The recognised text is written in PDF rendering
  mode 3 ("neither fill nor stroke"), so the page renders pixel-for-pixel
  identically. You get search and copy without any visual difference.
- Pick all pages or a range, with live "reading page 3 of 12" progress.

### How it was verified

The text layer is the part that could silently be wrong, so it was checked by
rendering the output and measuring pixels, not by trusting the code:

- **Placement** — the recognised text was compared against the actual ink. The
  placed run matches the ink's width to within ~2%, and the font size comes out
  as 22.03pt for 22pt source text and 14.05pt for 14pt.
- **On the ink** — for every page, the text sits over real ink (11-17% coverage)
  while the same box shifted away from the text has 0%.
- **All four rotations** — 0, 90, 180 and 270 degrees all pass, so a scanned page
  that is stored sideways still gets its text in the right place.
- **Invisible** — rendering mode 3 confirmed in the content stream, and the page
  renders with zero differing bytes against the original.
- **Searchable** — words are extracted correctly by an independent reader
  (PyMuPDF), and the surrounding content is untouched.

This runs automatically in CI on every build, alongside the 80 engine checks, the
hostile-input suite, the rendered rotation/crop checks and the AES-256 check.

## Features

| Tool | What it does |
|---|---|
| **Read PDF** | Fast viewer with pinch-zoom, pan, double-tap, page swipe **and rotate**. Pages are supersampled and re-rendered on zoom, so text stays crisp. Also opens PDFs shared from other apps. |
| **Organize pages** | Thumbnail grid — **long-press a page and drag to reorder**, plus rotate, delete, extract and undo. |
| **Merge PDFs** | Combine any number of PDFs, in any order. |
| **Split PDF** | Break a document into files by page ranges (e.g. `1-3, 4-6, 9`). |
| **Images to PDF** | Turn photos/scans into a PDF, one page per image. Bounded decoding keeps large batches from running out of memory. |
| **Annotate & sign** | Pen, highlighter, text, **cover-and-retype**, signature and images — each with **colour, size and opacity**, plus **undo/redo**, **select / move / pinch-to-resize** and erase. Two-finger pinch still zooms. |
| **Fill forms** | Detects AcroForm fields (text, checkbox, radio, combo, list) and fills them. |
| **Compress** | Three levels (Light / Balanced / Strong). Downsamples and re-encodes embedded images (including inside form XObjects) and strips metadata and thumbnails. |
| **Watermark** | Text watermark with **font**, **colour**, size, opacity and a **random position on every page** (or centred). |
| **Protect** | Adds a password and permissions (128-bit encryption). |
| **Unlock** | Removes a password. Works for owner-only protection (no password needed) and for files whose user password you know. |
| **Edit metadata** | Title, author, subject, keywords. |
| **Extract images** | Pulls embedded raster images out of a PDF. |
| **Page numbers** | Number every page with a custom format (`Page {n} of {total}`). |

**Dark mode** is supported throughout (follows the system, or pick Light / Dark from
the theme button on the home screen). All file access goes through the Storage Access
Framework, so **no storage permission is requested** and the app works fully offline.

## Building

Gradle 8.7 / AGP 8.5.2 / Kotlin 1.9.24, JDK 17, compileSdk 34, minSdk 24.

```bash
gradle :app:assembleDebug          # debug build
gradle :app:assembleRelease        # release (signed if keystore env vars are set)
```

### Signing

Release signing reads four environment variables; if they are absent the release
build is simply unsigned:

| Variable | Meaning |
|---|---|
| `KEYSTORE_PATH` | path to the `.jks` keystore |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

In CI these come from repository secrets (`KEYSTORE_BASE64` is decoded to a file
first). Keep the keystore safe — it is the app's permanent identity.

## Verification

The document engine is verified independently of the UI. `tools/verify-engine.sh`
compiles the **real** engine source against the **real** `pdfbox-android` library on a
plain JVM (with tiny stubs for the handful of `android.*` classes the engine touches)
and runs **80 behavioural assertions**: merge, split, reorder, rotate, delete,
text/image stamping, watermark (centred, random, multiple fonts/colours), page
numbers, encryption, decryption (owner-only and user-password), compression
(asserting a real size reduction), metadata, images-to-PDF (single and multi-image),
image extraction, form filling, PDF creation, extended metadata, and digital signing/verification (including a tamper-detection test), page cropping, and page-range extraction.

```bash
bash tools/verify-engine.sh
```

It then renders the rotated and cropped output PDFs and **pixel-checks** them with
PyMuPDF, so rotation-aware overlay placement and cropping are verified visually
rather than assumed.

This runs automatically on every push in GitHub Actions, before the APK is built.

## CI

`.github/workflows/android.yml` runs on every push to `main`:

1. verifies the engine (80 checks + rendered rotation/crop checks),
2. builds a **signed** release APK,
3. uploads it as a build artifact and attaches it to a GitHub Release.

## License

The PDF engine is built on [Apache PDFBox](https://pdfbox.apache.org/) via the
Android port [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)
(Apache-2.0).
