# NovaPDF

**A serious, offline-first PDF editor for Android.** Everything runs on the device —
no accounts, no uploads, no network permission. Open any PDF and read it with smooth
pinch-zoom, then actually edit it: merge, split, reorganise pages by dragging them,
annotate, sign, fill forms, watermark, protect, compress and more.

Package: `com.vk062777.pdfsmith` · Display name: **NovaPDF** · Licence: Apache-2.0

> The package id is kept stable so the app upgrades cleanly in place and the signing
> key stays valid. It can be renamed before the first Play Store upload on request.

---

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
and runs **53 behavioural assertions**: merge, split, reorder, rotate, delete,
text/image stamping, watermark (centred, random, multiple fonts/colours), page
numbers, encryption, decryption (owner-only and user-password), compression
(asserting a real size reduction), metadata, images-to-PDF (single and multi-image),
image extraction and form filling.

```bash
bash tools/verify-engine.sh
```

This runs automatically on every push in GitHub Actions, before the APK is built.

## CI

`.github/workflows/android.yml` runs on every push to `main`:

1. verifies the engine (53 checks),
2. builds a **signed** release APK,
3. uploads it as a build artifact and attaches it to a GitHub Release.

## License

The PDF engine is built on [Apache PDFBox](https://pdfbox.apache.org/) via the
Android port [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)
(Apache-2.0).
