# PDFsmith

**A serious, offline-first PDF editor for Android.** Everything runs on the device —
no accounts, no uploads, no network permission. Open any PDF and read it with smooth
pinch-zoom, then actually edit it: merge, split, reorganise pages, annotate, sign,
fill forms, watermark, protect, compress and more.

Package: `com.vk062777.pdfsmith` · Display name: **PDFsmith** · Licence: Apache-2.0

---

## Features

| Tool | What it does |
|---|---|
| **Read PDF** | Fast, smooth viewer (platform `PdfRenderer`) with pinch-zoom, pan, double-tap zoom and page swipe. Also opens PDFs shared from other apps. |
| **Organize pages** | Live thumbnail grid — rotate, reorder, delete and extract pages, with undo. Saves a new PDF. |
| **Merge PDFs** | Combine any number of PDFs, in any order. |
| **Split PDF** | Break a document into files by page ranges (e.g. `1-3, 4-6, 9`). |
| **Images to PDF** | Turn photos/scans into a PDF, one page per image, reorderable. |
| **Annotate & sign** | Highlight, freehand draw, add text, place a drawn signature or an image. WYSIWYG — preview and export use the same renderer. |
| **Fill forms** | Detects AcroForm fields (text, checkbox, radio, combo, list) and fills them. |
| **Compress** | Genuinely shrinks image-heavy PDFs: downsamples and re-encodes embedded rasters, strips metadata. |
| **Watermark** | Text or image watermark across every page, with size/opacity control. |
| **Protect** | Adds a password and permissions (128-bit encryption). |
| **Unlock** | Removes a password you know. |
| **Edit metadata** | Title, author, subject, keywords. |
| **Extract images** | Pulls embedded raster images out of a PDF. |
| **Page numbers** | Number every page with a custom format (`Page {n} of {total}`). |

All file access goes through the Storage Access Framework, so **no storage permission
is requested** and the app works fully offline.

## Building

The project builds with Gradle 8.7 / AGP 8.5.2 / Kotlin 1.9.24, JDK 17,
compileSdk 34, minSdk 24.

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

In CI these are populated from repository secrets (`KEYSTORE_BASE64` is decoded to a
file first). The same keystore must be kept safe and reused for every future Play
Store upload — losing it means you can never update the listing again.

## Verification

The document engine is verified independently of the UI. `tools/verify-engine.sh`
compiles the **real** engine source against the **real** `pdfbox-android` library on a
plain JVM (with tiny stubs for the handful of `android.*` classes the engine touches)
and runs **39 behavioural assertions** covering merge, split, reorder, rotate, delete,
text/image stamping, watermark, page numbers, encryption/decryption, compression,
metadata, images-to-PDF, image extraction and form filling.

```bash
bash tools/verify-engine.sh
```

This runs automatically on every push in GitHub Actions, before the APK is built.

## CI

`.github/workflows/android.yml` runs on every push to `main`:

1. verifies the engine (39 checks),
2. builds a **signed** release APK,
3. uploads it as a build artifact and attaches it to a GitHub Release.

## License

The PDF engine is built on [Apache PDFBox](https://pdfbox.apache.org/) via the
Android port [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)
(Apache-2.0).
