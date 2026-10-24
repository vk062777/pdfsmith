# KavoPDF

**A serious, offline-first PDF editor for Android.** Everything runs on the device —
no accounts, no uploads, no network permission. Open any PDF and read it with smooth
pinch-zoom, then actually edit it: merge, split, reorganise pages by dragging them,
annotate, sign, fill forms, watermark, protect, compress and more.

Package: `com.vk062777.pdfsmith` · Display name: **KavoPDF** · Licence: Apache-2.0

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


## What's new in 1.7.0 — search, hyperlinks, image OCR, clearer signatures

- **Search inside a PDF.** The reader now has a search bar: type a word or phrase,
  get a count, and step through every hit with previous / next. The current match
  is highlighted right on the page. Matches come from the real glyph positions, so
  the highlight lands exactly on the text.
- **Hyperlinks.** A new tool finds every web and e-mail address written in a PDF
  and turns it into a real, clickable link — or lets you drag an area and attach
  your own address.
- **Image to searchable PDF.** Point it at photos or screenshots; it reads the text
  on-device and saves a PDF that can be searched and copied. The recognised text is
  also shown on screen.
- **Sharper OCR.** The scanned-PDF OCR now renders pages at 2600 px wide instead of
  1600, which is meaningfully more accurate on small print.
- **Professional signature result.** The verification popup is now a proper panel:
  a large green "Signature valid" or red "Signature invalid" heading, followed by
  aligned Signer / Date / Reason / Location / Certificate / Status rows.

### Verification added in this release

- **Search** — every hit is checked against the rendered page: it must sit on real
  ink (33-39% coverage) while the same box moved into a blank area has 0%. Checked
  on rotated pages too.
- **Hyperlinks** — each added annotation must carry the right URI *and* cover the
  text it came from, confirmed with an independent reader.
- **Text layer, two resolutions** — the invisible text layer is now verified at two
  different raster sizes (100 and 200 dpi) to prove placement does not depend on
  the resolution the recogniser ran at. All four page rotations pass at both.

All of this runs in CI on every build, alongside the 80 engine checks, the
hostile-input suite, the rendered rotation/crop checks, the AES-256 check and the
permission guard.


## What's new in 1.8.0 — speed and link-icon fixes

- **Search is now fast.** Search (and hyperlink scanning) re-parsed the whole
  document once per page, so a 200-page file meant 200 parses. It is now a single
  pass: searching a 200-page document takes about 1.8 seconds.
- **OCR is faster, with no loss of accuracy.** Pages are rendered one ahead, so the
  next page is drawn while the recogniser is still reading the current one. The
  render resolution is unchanged, so recognition quality is unchanged.
- **Hyperlink icons.** You can now place a *visible* link button anywhere on the
  page and choose its colour. The link sits exactly on the button — tapping
  elsewhere on the page does nothing. A link can never cover the page: any
  oversized rectangle is rejected.
- **Image OCR no longer shows the recognised text on screen**, as requested. There
  is a new **Save recognised text (.txt)** action instead, and a short summary
  (images, characters).
- **Fixed:** Hyperlinks and Image-to-searchable-PDF did not open a file picker when
  you tapped them.

### Verification added

- The link icon is verified by rendering: the icon must be drawn where the link is
  (71.6% non-white against 0% on a blank part of the page), the link must be small
  (0.32% of the page) and must carry the right address.
- Search timing is measured on a 200-page document.


## What's new in 1.9.0 — custom link text, copyable OCR, audit

- **Custom link text.** On the Hyperlinks screen you can now write your own text on
  any page — choose the font (Helvetica, Helvetica Bold, Times, Times Bold,
  Courier), the size, and one of five colours — and attach a web address to it.
  Tapping the text opens the link; tapping anywhere else does nothing. Verified by
  rendering: the text is visible, drawn in the chosen colour, and the link covers
  only that text, on all four page rotations.
- **Removed** the "Save recognised text (.txt)" action, as asked.
- **Copy recognised text.** Image OCR now has a "Copy recognised text" action, so
  text that was trapped inside a photo or screenshot can be pasted anywhere.
- **Audit fixes.** Removed a `!!` that could crash the page organiser, guarded the
  tool launcher and the share sheet so a missing target cannot take the app down.

### Technology review

Checked every dependency and format against what is current:

- PDF encryption: **AES-256** (already upgraded from RC4-128).
- Digital signatures: **SHA-256 + RSA-2048 + CMS/PKCS#7** — current practice.
- On-device OCR: **ML Kit text-recognition 16.0.1** — the latest release.
- PDF engine: **PDFBox 2.0.27.0** — the latest Android release.
- Upgraded: constraintlayout 2.1.4 -> **2.2.2**, documentfile 1.0.1 -> **1.1.0**.
  Coroutines 1.11.0 was tried and **reverted**: it is built against Kotlin 2.2 while
  this project compiles with Kotlin 1.9, so it does not load. Staying on 1.8.1 is
  the correct choice until the Kotlin plugin itself is upgraded.

  The remaining AndroidX libraries do have newer releases, but each one now declares
  a minimum `compileSdk` of 35-37 (read from the libraries' own AAR metadata:
  core-ktx 1.19.1 wants 37, activity 1.13.0 wants 36, recyclerview 1.4.0 wants 35).
  Taking those means bumping the Android Gradle Plugin and the compile SDK — a
  toolchain change that alters build behaviour and cannot be verified without a
  device, for no functional gain. `targetSdk` stays at 34 for the same reason: the
  app already installs and runs on Android 17, and raising it would opt into
  behaviour changes that need on-device testing.

### Audit

Every one of the 20 tools was checked to be reachable, dispatched and backed by a
real screen. Swept the whole codebase again for null dereferences, index errors,
unguarded activity launches, resource leaks and permission changes. Two real
robustness issues were found and fixed (above). The app still requests no
permissions at all, enforced by the CI guard.


## What's new in 1.10.0 — placement fix and opacity

- **Fixed: link text did not land where you placed it.** The page selection only
  accepted a drag that started *inside* the small default box, so a drag anywhere
  else on the page was ignored and the link always went to the top-left corner. You
  can now **tap or drag anywhere on the page** and the selection jumps to that spot
  and follows your finger. This applies to both link icons and link text.
- **Opacity.** Both the link icon and the custom link text now have an opacity
  control (0-100%). Verified by rendering: at 40% both are measurably lighter than
  at 100% (text saturation 192 -> 77, icon 136 -> 54).

### Verification added

- A rendered opacity check: for both the icon and the text, 40% must come out
  visibly lighter than 100%.


## What's new in 1.11.0 — placement, copy, and speed

- **Placement is now full screen.** On the Hyperlinks screen, "Full screen placement"
  hides the controls so the page fills the screen, which is what you need to place
  something precisely. Drag anywhere to move the selection, drag a corner handle to
  resize it (the handles are visible and the touch target is bigger). The position
  you pick is kept while you page through the document.
- **Same link on every page.** A "Put the same link on every page" switch places the
  icon or the text at the same spot on all pages in one go. Verified by rendering:
  the mark lands at exactly the same place on all five test pages, including a
  rotated one.
- **Copy text from a PDF.** Long press a page in the reader to copy the word under
  your finger, or the whole page. This is what was missing before: a searchable PDF
  opened in this app could not be copied from.
- **Speed.**
  - Searching no longer re-reads the document. The text is measured once and reused,
    so the first search pays for the extraction and every search after it is
    essentially instant: measured 476 ms to index, then **3 ms for five searches**.
  - OCR skips pages that are completely empty (only truly blank pages, so no text is
    ever lost).

### Verification added

- Same-link-on-every-page is checked by rendering every page, including a rotated
  one: the mark must be at the requested spot (89.8% ink for the icon, 26.3% for the
  text) with 0% elsewhere.
- The tap-to-copy word boxes are checked against the rendered ink, and the page text
  against what is actually written on the page.


## What's new in 1.12.0 — Redact

Mark anything private on a page and remove it for good before sharing.

**A redaction is not a box.** Drawing a black rectangle leaves the text in the file -
anyone can select it, copy it, or pull it out. So a redacted page is **replaced by a
flat image** with the marked areas painted black: the page's original content stream,
its resources and its annotations are all discarded. Only the pages you mark are
flattened; every other page keeps its real text.

**How to use it:** Redact -> choose the PDF -> drag over anything private (as many
boxes as you like, tap a box to remove it) -> Redact & save.

### Verification

The redaction is checked the only way that matters - by looking for the content
afterwards:

- nothing secret is extractable as text
- **nothing secret survives in any decompressed stream in the file** (this is the
  real test: it catches content that is merely hidden from the text extractor)
- pages that were not redacted keep their text
- page count and page sizes are unchanged
- the redacted areas render 100% solid black

The fixture has a secret on a normal page and a secret on a **rotated** page, so the
rotation handling is covered too.

### Home screen

Adding Redact left the tool grid with a half-empty row, so the sections were
rebalanced: **Watermark** moved to Security (where it belongs - watermarking is an
ownership mark) and **Redact** sits beside it. Every section now has an even number
of tiles (2, 4, 4, 6, 6 = 22 tools), so there are no gaps anywhere.


## What's new in 1.13.0 — Redact fixes

Three real bugs, all found from the report and fixed:

- **"Can't compress a recycled bitmap".** The save helper hands the work back
  *after* the user picks a destination, but the page images were being recycled
  immediately, before the engine could use them. The work now happens entirely
  inside that callback, one page at a time, so only one large bitmap is ever alive
  and nothing is freed early. The renderer's own bitmap is no longer recycled
  either - that was corrupting its cache.
- **Boxes did not land where you drew them.** Every screen that maps a touch to a
  page position assumed the image view kept its transform in `imageMatrix`. That is
  only true for `scaleType="matrix"`. The Crop and Links screens use
  `scaleType="fitCenter"`, where `imageMatrix` stays identity - so those boxes were
  mapped against the wrong rectangle. There is now one shared helper that works for
  both kinds of view, and it is used by the crop, link and redact screens.
- **No zoom while redacting.** The redact page is now a proper zoomable view with a
  zoom toggle: in zoom mode you pinch and drag; tap the tick and you are back to
  drawing. The zoom is kept when you switch back, so you can zoom into small text
  and then mark it precisely.

### Verification

The redaction check was made much harder, because "hidden" is not the same as
"gone". After redacting a secret on a normal page and a secret on a **rotated** page,
the test now confirms all of:

1. no secret in the text extraction of any page
2. no secret in **any decompressed stream** in the file
3. no secret in **any object in the xref**, object streams included
4. no secret in the **raw bytes** of the file
5. **no earlier revision is left behind** (no incremental update)
6. the redacted page carries **no font at all** - it is pure image, so it cannot
   draw text even in principle
7. the redacted areas render 100% solid black
8. the rest of the document keeps its text, page count and page sizes
9. the redacted page's own content stream contains **no text operators at all**

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

## What's new in 2.0.0 - KavoPDF

The app is now **KavoPDF**. The package id is unchanged, so updates install straight
over the old one.

- **New launcher icon** - a bold amber K, drawn as a vector/adaptive icon for every
  launcher shape and size (circle, squircle, legacy square and round, plus a
  monochrome layer for themed icons).
- **Every dialog is rounded.** The dialog surface is defined once in the theme, so a
  new dialog cannot come out square by accident.
- **No more jittery overscroll.** The stretch effect that broke and stuttered at the
  end of a scroll is gone from every list and scroll view.
- **Protection upgraded.** Real AES-256 (PDF 2.0, handler revision 6) with a choice of
  permissions, an optional separate owner password, and a weak-password warning. The
  password is never stored, never logged and never leaves the device.
- **Bookmarks.** Read and write the PDF outline, so any reader shows your entries in
  its sidebar.
- **Activity log.** Every completed task is recorded locally with a timestamp, and the
  whole log exports to a PDF carrying the app version, package, device and Android
  build. Passwords are never written to it.

Verified on every build: AES-256 parameters and enforcement, the outline read back by
an independent library, the log export, plus everything from before.

## What's new in 2.0.1 - icon and animation fixes

- **The launcher icon is now exactly the mark that was chosen.** The first build
  drew a different K (thinner spine, different arms); every launcher asset is now
  generated from the same geometry - adaptive foreground, legacy square, legacy
  round, and the monochrome layer for themed icons.
- **No more stutter while scrolling the home list.** Cards were running their
  fade-and-slide entry animation again every time they were bound, so newly
  revealed cards popped in mid-scroll. They now animate only on a still screen.
- **No more stutter when the file picker opens.** A tool screen opened the picker
  immediately, while its own slide-in animation was still running, and the two
  transitions fought. The picker now opens once the screen has settled.
## What's new in 2.0.2 - the launcher background is fixed

The adaptive icon takes its background from `values/launcher.xml`. When the working
copy was restored from the 1.13.0 source, that file came back as the old teal
(`#FF02A79F`) and the re-apply step never rewrote it - so 2.0.0 and 2.0.1 shipped a
teal background behind the new K.

Fixed for good: the icon generator now writes `values/launcher.xml` itself, next to
the artwork, so the background colour and the K can never drift apart again. The
shipped background is `#FF15181D`.
## What's new in 2.1.0

- **The file picker opens straight away again.** 2.0.1 delayed it so the screen
  could finish animating; that made every tool feel slow. Now the picker opens
  immediately and the tool screen simply does not animate in, so nothing fights.
- **Image to searchable PDF:** the "copy recognised text" button is gone.
- **Protect PDF:** back to a single password - the owner password and the
  permission list are removed.
- **The activity-log export is a designed report** - a coloured header band, a
  facts card, a striped table with green/red results, and a footer.
- **Input fields are pills everywhere**, set once in the theme.
- **Redact:** the zoom button is gone. Two fingers pinch-zoom, one finger draws a
  redaction box.
- **Reader zoom now follows your fingers** instead of drifting to the middle.
- **Annotate / Create PDF:** the pen-thickness row fits the screen (it was cut
  off), and the image tool opens the picker immediately.
- **Every in-app icon is now the current Material Symbols Rounded set** (49 icons
  fetched from Google's own source), drawn a little larger and consistent
  throughout - the same action always uses the same glyph.
- **Material 3 Expressive:** the Material library moves to 1.14.0 with the
  expressive shape scale and pill buttons.
- **A new "Amber" theme** matching the launcher mark, and toolbars now follow the
  theme's on-primary colour so light accents stay readable.

Verified on every build: the report's structure and colour, that the chosen font
really reaches the PDF, plus everything from before.
## What's new in 2.2.0

- **Copying text out of a PDF now works.** The page view swallowed every touch
  event and never called through to the framework, so the long-press that opens
  the copy dialog never fired. Long-press a page to copy a word or the whole page.
- **Every input field is a pill, and the label no longer sits on the value.**
  The text was being set before the field was attached, so the label never floated.
  Fixed for metadata, watermark, signature, page numbering, split, forms, protect
  and unlock - all of them.
- **Passwords can be shown**: protect and unlock fields have a reveal eye.
- **Compressing never grows a file.** A tiny or already-optimised PDF used to come
  out bigger; now the original is kept whenever that would happen.
- **Redact** zooms and pans with two fingers, so you can reach any part of a page
  before drawing a box.
- **The link screen is all pills**, and the bookmark title/page fields are spaced.
- **The pen-thickness slider is longer** (the swatches gave up the room).
- **The Amber theme is now reachable** - it existed, but the theme picker only
  listed five swatches, so it could never be chosen.
- **QR & barcode scanner**: reads every format with the bundled offline model, with
  copy / open / share and a torch.
- **Scan to PDF**: photograph pages with the camera and assemble them into one PDF.

The camera screens need the CAMERA permission, which is requested at runtime and is
the only real permission the app asks for. The CI guard still fails the build if
anything else (INTERNET included) creeps in.
## What's new in 2.2.1

- **Every tool now shows its own name in the heading.** ToolsActivity calls
  setSupportActionBar, so the ActionBar owns the title and setting it on the raw
  toolbar was being overwritten by the activity label - and ToolsActivity has no
  label, so it fell back to the app name. The ten tools it hosts (extract images,
  compress, watermark, protect, unlock, sign, verify, metadata, page numbers,
  split) were all affected. The reader screen's label is fixed too.
- **The theme swatches are evenly spaced.** The crimson and amber swatches were
  added without the margin the others had, so they sat stuck together while the
  rest were spaced - and all six were slightly too big for the dialog. They are
  now the same size with the same gap.
- A CI guard now fails the build if a tool would ever open with no heading.
