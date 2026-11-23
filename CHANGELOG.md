# Changelog & Project History

All notable changes and milestones for **Kadata (ಕಡತ)** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project follows a pre-1.0 [Semantic Versioning](https://semver.org/spec/v2.0.0.html) scheme (`0.MINOR.PATCH`):
- **`0.x.0` (Minor / Second Digit):** Significant new features, workflow changes, or architectural updates.
- **`0.x.y` (Patch / Third Digit):** Small bug fixes, minor improvements, and cosmetic adjustments.
- **`1.0.0` (Major / First Digit):** Reserved for the first production-ready release.

---

## [0.20.1] - 2026-11-22

### Fixed
- **WhatsApp & Generic Document Title Healing (`DocumentTitleHealer`, `SafStorageManager`, `HomeViewModel`, `KannadaDocApp`):**
  - Fixed root causes that kept WhatsApp documents stuck as `DOC-YYYYMMDD-WAxxxx`:
    1. Existing Room rows with unhealed WhatsApp names were previously preserved verbatim on re-open; custom user renames are still preserved while generic WhatsApp names now heal cleanly.
    2. Added multi-tier healing: checks embedded metadata across **DOCX, ODT, EPUB, HTML, PDF, and DOC** (stripping noisy `"Microsoft Word - "` prefixes and converting legacy Nudi/Baraha metadata), and when metadata is empty or generic (true for most real-world files), extracts the **first meaningful heading or opening line** normalized through `KannadaTextConverter` into clean Modern Universal Kannada.
    3. Expanded detection for WhatsApp copy suffixes (`(1)`, `_1`), `WhatsApp Document ...`, `msf:...`, and generic scanner filenames.
    4. Added a retroactive background pass in `HomeViewModel` so existing `DOC-YYYYMMDD-WAxxxx` files already in Recent Files heal automatically on launch.
- **Cumulative Packaging (`v0.19.1`–`v0.20.1`):** Bundled `RecentFileDao.observePendingMetadataBackfill` and all changes since `v0.19.0-cumulative` into a single drop-in cumulative archive so no intermediate patch files can be missed.

### Added
- **Unit Tests (`DocumentTitleHealerTest`):** Verifies WhatsApp/generic filename detection, exporter prefix stripping, Nudi-to-Unicode title healing, and content-heading fallback.

---

## [0.20.0] - 2026-11-22

### Changed & Added (Strict Fail-Fast Size Rejection & Zero Silent Degradation Architecture)
- **Centralized Size Limits & Explicit Rejection (`DocumentSizePolicy`, `DocumentSizeLimitException`):**
  - Replaced all silent truncation with strict fail-fast rejection across **every** format: `HTML` (`10 MB`), `TXT`/`RTF` (`15 MB`), `DOC` (`30 MB`), `DOCX`/`ODT`/`EPUB` (`25 MB` per entry / `60 MB` total decompressed / `2,000` entries / `80 MB` raw archive), and `PDF` (`100 MB`).
  - Enforced uniformly in `SafStorageManager` (pre-copy & post-copy gates with zero disk waste), `DocumentParserFacade`, `BoundedStreamReader.readStrict`, `SafeZipReader.readMatchingEntries`, and `HtmlParser`.
- **Global File-Rejection Dialog (`MainActivity`, `HomeViewModel`, `HomeScreen`):**
  - Unified unsupported-format, oversized-file, and unreadable-file errors into a bilingual `FileRejectionAlert` dialog rendered globally in `MainActivity` so incoming files from WhatsApp, Gmail, or Drive always explain clearly why a file was rejected regardless of which screen is open.
- **Zero Silent Failures in Editor & Background Tasks (`EditorViewModel`, `EditorScreen`, `editor.js`):**
  - Added a dedicated full-screen Load Error card in `EditorScreen` (with **Back to Home**) when opening fails — preventing users from ever landing on a blank editable canvas.
  - Surfaced explicit warning banners on auto-save failures, local Room draft backup failures, rename sync errors, Google Docs Share-sheet fallbacks, and WebView 10 MB Undo-history memory trims.
- **Explicit Parser Recovery Transparency (`DocxParser`, `DocHwpfParser`, `OdtParser`, `EpubParser`, `PdfViewerScreen`):**
  - Eliminated silent degradation across all parsers: malformed DOCX styles/numbering, corrupt `.doc` tables/cells/runs, damaged ODT styles/tables/XML, missing or fallback-ordered EPUB chapters, and scanned image-only PDFs opened in **Edit** mode now explicitly inform the user via the editor's warning banner.
- **Updated Unit Tests:** Updated `SafeZipReaderTest` and `ParserHardeningTest` to verify strict `DocumentSizeLimitException` rejection instead of silent truncation.

---

## [0.19.4] - 2026-11-22

### Changed & Fixed (Parser Safety, Partial Recovery & Least-Privilege Architecture)
- **Bounded Streaming & Non-Buffering ZIP Peek (`BoundedStreamReader`, `HtmlParser`, `DocumentRouter`):**
  - Added `BoundedStreamReader` (`10 MB` raw text/HTML cap with explicit truncation detection + zero-buffering `peekHeader`).
  - Upgraded `HtmlParser` to peek ZIP headers and stream zipped web pages directly into `SafeZipReader` (skipping large embedded images without buffering them in RAM), cap raw HTML safely with a user-visible `warningMessage`, and replace backtracking regexes with linear `O(N)` comment/script/style strippers.
  - Eliminated the remaining unbounded `inputStream.readBytes()` in `DocumentRouter.detectFromStream()`.
- **Resilient ODT Parsing with Partial Recovery (`OdtParser`):** Wrapped `parseBodyBlocks` and `parseTable` in structured recovery blocks so malformed or truncated `content.xml` streams return all previously parsed sections alongside a clear `warningMessage` banner instead of failing or silently dropping content.
- **Unified EPUB Path Resolution (`EpubParser`):** Removed duplicate `normalizeZipPath()`, standardized on `SafeZipReader.normalizeEntryPath()`, added percent-decoding (`Chapter%201.xhtml`) and `#fragment` stripping for OPF `href` resolution, and isolated per-chapter parse errors.
- **Spec-Compliant ODT Export (`OdtWriter`):** Assigned sequential unique table names (`Table1`, `Table2`, ...) and added distinct `OrderedList` vs `BulletList` ODF list styles.
- **Least-Privilege Google Docs Sharing (`GoogleDocsShareHelper`):** Removed unnecessary `FLAG_GRANT_WRITE_URI_PERMISSION` on read-only internal `FileProvider` URIs and attached `ClipData` for reliable read permission propagation.
- **Unbounded Targeted Metadata Backfill (`RecentFileDao`, `HomeViewModel`):** Replaced the 100-row capped backfill observer with `observePendingMetadataBackfill(batchSize = 50)` so libraries of any size backfill completely with zero idle re-scans, and fixed an early `continue` that deferred size backfill during legacy tag migration.

### Added
- **Hardening Unit Tests (`ParserHardeningTest`):** Added pure-JVM JUnit tests covering bounded streaming, zipped HTML image-skipping, OPF href normalization, and ODT multi-table/list XML compliance.

---

## [0.19.3] - 2026-11-22

### Fixed
- **Universal Zoom Across All File Formats & Views:**
  - **PDF Viewer (`PdfViewerScreen.kt`):** Fixed the Compose `.pointerInput(zoomScale, panOffset)` mid-gesture cancellation bug using `rememberUpdatedState` + stable `pageIndex` key so 2-finger focal-point pinch-to-zoom (`100%–400%`), smooth panning, and double-tap zoom work effortlessly; added always-visible `− | 100% | +` zoom buttons in the PDF bottom bar.
  - **Document Editor (`editor.js`, `editor.css`, `EditorScreen.kt`):** Added reliable reflowing 2-finger pinch-to-zoom (`50%–300%`) across all document formats (DOCX, DOC, ODT, RTF, TXT, HTML, EPUB, Google Docs, and PDF-converted-to-doc) in both **Editable View** and **Read-Only Original View**, keeping the reading position anchored and cursor/selection handles pixel-exact; added `− | 100% | +` zoom controls in the formatting toolbar and a floating zoom pill whenever viewing Read-Only Original mode, with the toolbar hidden, or while zoomed.

---

## [0.19.2] - 2026-11-22

### Fixed
- **True Frosted-Glass Blur on Dark-Mode Document Cards (40% Transparency / 60% Opacity):** Introduced `FrostedDocumentSurface` with real-time pixel-aligned backdrop blur (`3`-pass separable Gaussian approximation) behind every card in Dark Mode overlaid with a `60%`-opaque (`40%` transparent) dark frosted tint and glass specular edge, fixing the overly see-through card appearance from `v0.19.1`.
- **EditorScreen Build Import:** Permanently included the `androidx.compose.foundation.BorderStroke` import in `EditorScreen.kt`.

---

## [0.19.1] - 2026-11-22

### Fixed
- **PDF Viewer Stuck on Loading Spinner:** Restored `Bitmap.Config.ARGB_8888` required by Android's `PdfRenderer.Page.render()` (capped at `1600px` inside our heap-bounded `LruCache`) and added explicit page render error reporting instead of silently spinning.
- **Mobile 3-Dot Menu Bottom Clipping:** Tightened Editor `DropdownMenuItem` vertical padding/height and single-line labels so all items—including **Document Info • ದಾಖಲೆ ಮಾಹಿತಿ** at the bottom—render completely without clipping or requiring manual menu scrolling on phone screens.

### Changed
- **List View Default:** Switched the default Home Library view from Grid View to **List View (`DETAILS_LIST`)** via `home_view_mode_v2`.
- **Frosted Dark-Mode Document Cards:** Upgraded dark-mode Home cards to a 40% translucent frosted glass aesthetic (`Color(0xFF1E1E1E)` at 40% alpha + crisp hairline border) over a softly visible blurred Kadata backdrop.
- **Larger Full-Width File-Type Badge (`FileTypeIcon`):** Enlarged the icon to `34dp × 40dp` with bold, high-legibility format banners (`PDF`, `DOCX`, `RTF`, `TXT`, `ODT`, `HTML`, `EPUB`) spanning the full inner width inside the icon borders.

---

## [0.19.0] - 2026-11-22

### Changed (Architectural Hardening & Long-Term Reliability)
- **Single Kannada Conversion Engine (Retired Duplicate JS Converter):** Consolidated 100% of Nudi/Baraha ↔ Modern Universal Kannada conversion into `KannadaTextConverter.kt` (`normalizeDocumentWithReport` + `prepareDocumentForLegacyExport`), removed `<script src="kannada-converter.js">` from `index.html`, and retired `kannada-converter.js`. Editor load, Home export, Save As, Change Document Type, and the Standalone Converter now share one single source of truth.
- **Shared `SafeZipReader` Across All ZIP Consumers:** Introduced `SafeZipReader` normalizing backslash (`word\document.xml`), leading-slash (`/word/document.xml`), and mixed-case entry paths while enforcing strict per-entry (25 MB), total (60 MB), and entry-count (2,000) ceilings across `DocxParser`, `OdtParser`, `EpubParser`, `HtmlParser`, `DocumentRouter`, and `SafStorageManager`.
- **Unified SQLite Metadata via Non-Destructive Room `Migration(2, 3)`:** Added `kannadaFormat` directly to `RecentFileEntity` with `AppDatabase.MIGRATION_2_3` (preserving all existing recent files and drafts), automatically migrating legacy preference tags and optimizing PDF format sniffing to Page 1 only.
- **Storage Lifecycle & Garbage Collection:** Added pre-copy format sniffing in `SafStorageManager` (rejecting unsupported files with zero disk writes), reference-counted physical file deletion when removing items from Recent Files, and a deferred 5-second startup orphan sweep in `KannadaDocApp` for stale export cache and unreferenced files.
- **PDF Viewer Memory Safety & Naming Consistency:** Switched `PdfViewerScreen` rendering to `Bitmap.Config.RGB_565` (50% lower RAM per page) backed by a heap-bounded `LruCache<Int, Bitmap>` (16–64 MB cap), and unified its language parameter to `knOnly: Boolean`.
- **Bounded WebView Undo/Redo Memory:** Added a 10 MB total snapshot memory budget (`MAX_HISTORY_CHARS`) alongside the 100-step cap in `editor.js` to protect large manuscripts from WebView memory bloat.
- **Single-Source Versioning (`VERSION` → Gradle → About App):** Wired `android/app/build.gradle.kts` to read the root `VERSION` file directly for `versionName`, `versionCode`, and `R.string.app_version`, eliminating hardcoded fallback version strings in `AboutKadataDialog`.

### Fixed
- **Hardened Legacy `.doc` Table Extraction (`DocHwpfParser`):** Added zero-row bounds checks, per-row/per-cell `runCatching` failure isolation, and guaranteed monotonic cursor progress (`idx += maxOf(1, consumedParas)`) for corrupt government `.doc` tables.

### Added
- **Architectural Unit Tests:** Added `KannadaTextConverterTest` and `SafeZipReaderTest` covering round-trip Nudi/Baraha conversion, script-splitting, ZIP path normalization, byte limits, and multi-format container routing.

---

## [0.18.0] - 2026-11-22

### Added
- **Full OpenDocument (`.odt`) Support:** Added pure-Kotlin `OdtParser` (resolving `styles.xml` + `content.xml` including headings, lists, tables, alignment, and legacy Kannada font metadata) and spec-compliant `OdtWriter`.
- **Web Page (`.html` & Zipped `.zip`) Support:** Added `HtmlParser` supporting both standalone `.html`/`.htm` files and Google Docs' zipped web page exports (`.zip` containing `.html`), plus `HtmlZipWriter` for clean UTF-8 zipped web page export.
- **EPUB Publication (`.epub`) Support:** Added `EpubParser` (OPF spine + XHTML chapters) and valid EPUB 3 `EpubWriter` (including uncompressed `mimetype`, `container.xml`, `content.opf`, `nav.xhtml`, and `toc.ncx`).
- **Plain Text (`.txt`) & Rich Text (`.rtf`) Export:** Added `TxtWriter` and `.rtf` export alongside `.docx`, `.doc`, and `.pdf` in both Save As and Share Different Format.
- **Google Docs Interop (100% Offline, Zero `INTERNET` Permission):**
  - **Open from Google Drive:** Supports Android's virtual `application/vnd.google-apps.document` URIs via `ContentResolver.openTypedAssetFileDescriptor(...)` so Google Docs stream cleanly into Kadata for editing.
  - **Export / Share to Google Docs:** Added **Google Docs (.docx)** export target emitting clean Modern Universal Kannada and launching Google Docs directly when installed (or falling back to the Share sheet).
- **Modern File-Type Icons & Filters:** Added distinct badges in `FileTypeIcon` for `ODT`, `HTML`, and `EPUB`, expanded `AndroidManifest.xml` intent filters, added unified `CreateDocumentWithMimeContract` SAF export launcher, and added an `EPUB` filter chip on Home.

### Fixed
- **Auto-Save Default Migration:** Bumped auto-save preference key (`autosave_interval_minutes_v2`) so existing installations cleanly adopt the `5`-minute default.

---

## [0.17.2] - 2026-11-22

### Changed
- **Settings Layout:** Moved **About App • ಆಪ್ ಬಗ್ಗೆ** along with **Developer: Adarsh Katagihallimath** to the very top of both the Home and Editor Settings dialogs, with all configuration options cleanly grouped below.

---

## [0.17.1] - 2026-11-22

### Changed
- **About App Dialog Layout:** Reordered the About App dialog to show **Developer & GitHub** first, followed by a dedicated **Kannada (`ಕನ್ನಡ`)** section and an **English** section below it (always displaying both languages).
- **Default Auto-Save Interval:** Changed the default auto-save interval from `10` minutes to **`5` minutes**.

---

## [0.17.0] - 2026-11-22

### Added
- **Auto-Detect System Theme (Default):** Added `System` theme mode (`System / Light / Dark` • `ಸಿಸ್ಟಮ್ / ಬೆಳಕು / ಕತ್ತಲೆ`) in both Home and Editor Settings; Kadata now automatically follows the device's system Light/Dark setting by default.
- **About App Dialog:** Added **About App • ಆಪ್ ಬಗ್ಗೆ** in Home Settings, Editor Settings, and the Editor 3-dot (`⋮`) menu, featuring an app overview, live version display, developer details (**Adarsh Katagihallimath**), clickable GitHub repository link, and on-device privacy & content safety commitments.
- **Halekannada & Classical Symbols:** Added classical epigraphic and manuscript symbols—including Kannada Sign Siddham (`಄`), Avagraha (`ಽ`), Candrabindu (`ಀ`, `ಁ`), Vedic signs (`ೱ`, `ೲ`, `◌ೳ`), Dandas (`।`, `॥`), Abbreviation (`॰`), Om (`ॐ`), and traditional fractions (`¼`, `½`, `¾`)—to both the `೧೨೩` and `ಹಳೆಗನ್ನಡ` keyboard tabs.

### Changed
- **True Pure Black (OLED) Dark Mode:** Replaced bluish-grey dark surfaces across Compose themes (`Theme.kt`), Home scrims, and the WebView editor (`editor.css`) with true `#000000` / neutral charcoal surfaces.

### Fixed
- **Visible Cursor with In-App `ಕ` Keyboard:** Replaced collapsed-range caret measurement with zero-mutation 1-character adjacent boundary measurement and switched `#kd-virtual-caret` to fixed viewport coordinates with a high-contrast blinking indicator, ensuring the cursor stays clearly visible at all times while typing with the Varnamala keyboard.

---

## [0.16.0] - 2026-11-22

### Added
- **Dedicated Halekannada (`ಹಳೆಗನ್ನಡ`) Keyboard Tab:** Added full Shakatarepha (`ಱ`) and Kula (`ೞ`) forms, Halekannada subscripts (`◌್ಱ`, `◌್ೞ`), 24 classical clusters, archaic vowels (`ಌ`, `ೡ`), and Champu/inscription marks.
- **100-Step Undo / 100-Step Redo with Auto-Scroll:** Built a custom 100-step Undo and 100-step Redo history engine that restores the exact cursor position, smoothly scrolls straight to the undone/redone section, and briefly highlights the affected paragraph.
- **Standalone Anusvara (`◌ಂ`) & Visarga (`◌ಃ`):** Added directly on the main `ವರ್ಣಮಾಲೆ` grid for direct attachment to any syllable.

### Changed
- **Unified Single-Page Vertical Long-Press Popup:** Removed separate `ಕಾಗುಣಿತ` vs `ಒತ್ತಕ್ಷರ` popup tabs and horizontal scrolling. Long-pressing any base consonant now displays its 17 Kagunita forms immediately followed below by all deduplicated Ottakshara combinations in one vertical scroll; long-pressing an Ottakshara shows only its 17 Kagunita forms in a clean vertical scroll.

### Fixed
- **Backwards (Leftward) Typing Cursor Bug:** Fixed an input event race where live browser selection was overwritten back to the pre-keystroke caret; guarded IME composition (`compositionstart`/`compositionend`) and switched Varnamala insertions to deterministic forward `Range` placement.

---

## [0.15.0] - 2026-11-22

### Added
- **In-Editor Rename:** Added a Rename (`✏️`) button next to the filename in the Editor top bar to rename open documents in place while preserving file extensions and syncing Room history.
- **Bilingual Global Format Labels & Compatibility Notes:** Renamed "Modern Unicode" globally to **Modern Universal Kannada • ಆಧುನಿಕ ಸಾರ್ವತ್ರಿಕ ಕನ್ನಡ**, added clear compatibility subtitles (*Supported on PC only* for Nudi/Baraha; *Supported on all modern devices, not supported in Nudi & Baraha* for Modern Universal Kannada), and added the Kadata seamless auto-detection note across Settings, Export/Share dialogs, Change Document Type, Document Info, Recent Files, and the Converter.

### Changed
- **Line-Aware Cursor Context on Keyboard Open:** Kept 1 full line above the cursor on phones (cursor on line 2) and 2–3 lines above the cursor on tablets instead of pushing the active line to the top edge.
- **Find-Only Default in Search:** Opening Search now opens only the Find bar; the Replace row expands only when tapping the Replace toggle button.

---

## [0.14.0] - 2026-11-22

### Added
- **Horizontal Column Details List View:** Upgraded Details List View to a full-width multi-column layout (`Name | Type | Format | Size | Modified | Status | ⋮`) on tablets/landscape and a compact 2-line horizontal layout on phones.
- **Consistent File Size & Format Backfill:** Added automatic background metadata backfill so every entry in Recent Files consistently displays accurate file size and detected Kannada format (`Nudi`, `Baraha`, `Universal`, `English`, or `Scanned`).
- **System Keyboard Toggle & Picker:** Tapping the `⌨️` button now toggles the system keyboard open and closed; long-pressing `⌨️` opens Android's Input Method Picker to switch keyboards.

### Changed
- **Streamlined `ಒತ್ತಕ್ಷರ` Subscripts Tab:** Kept only the attaching Ottakshara subscripts in the subscripts tab and consolidated consonant clusters, doubled consonants, Arkavattu, and nasal combinations into each letter's long-press view.

### Fixed
- **Random Blank-Page Scroll Jump:** Replaced unguarded scroll offsets with clamped `window.scrollTo` bounded by actual content height so opening the `ಕ` keyboard never overshoots into blank space.

---

## [0.13.0] - 2026-11-22

### Added
- **Natural Pinch-to-Zoom:** Enabled smooth two-finger pinch-to-zoom (`0.5x–4.0x`) in the Document Editor WebView and (`1x–4x`, pan, double-tap) in the PDF Viewer.
- **PDF Swipe Page Navigation:** Rebuilt `PdfViewerScreen` with `HorizontalPager` for natural swipe/slide page turning and a clean non-colliding top header.
- **Change Document Type:** Added **Change Document Type** in the Editor 3-dot menu to convert between Nudi, Baraha, and Modern Universal Kannada while disabling whichever format the document is already in.
- **Dual Hover Buttons (`ಕ` + `⌨️`) & Manual Keyboard Block Setting:** Changed the Kannada keyboard button icon to `ಕ`, placed an always-available `⌨️` system-keyboard button directly below it, fixed missing auto-focus in Search/New/Rename/Converter fields, and added an optional **Block Auto-Opening Keyboards** toggle in Settings.
- **3-Page Scroll Buffer & `imePadding()`:** Added ~3 pages of scrollable buffer below document content so typing near the bottom of short documents never hides behind the keyboard.
- **Grid vs. Details List View Toggle:** Added a persisted view switcher next to Recent Files.
- **Auto-Registration of Saved/Shared Exports:** Files created via Save As, Save a Copy, Share Different Format, and Change Document Type now appear automatically in Recent Files.
- **Normalized Cross-Span Find & Replace:** Trims/normalizes pasted search queries (whitespace, NBSP, zero-width characters) and matches phrases across inline `<span>` boundaries.

### Fixed
- **System Bar & Tablet Taskbar Clipping:** Applied root `navigationBarsPadding()` and a dedicated themed top status-bar strip so the app never clips behind Android navigation buttons or tablet taskbars and maintains crisp status-bar contrast.
- **Home Card Vertical Text Wrapping & Table Overflow:** Fixed `"Offline"` wrapping vertically on narrow phones and prevented wide Word tables from clipping off the right edge.

---

## [0.12.0] - 2026-11-22

### Added
- **Tablet Split Home Screen:** Added a side-by-side landscape tablet home layout featuring the crisp Kadata artwork showcase on the left and the blurred workspace on the right.
- **Tablet Side-by-Side Hover Keyboard:** On tablets (`>= 600dp`), opening the Kannada keyboard docks it on the left while shifting the document preview right to fill 100% of remaining space without reloading the WebView (`movableContentOf`).

---

## [0.11.0] - 2026-11-22

### Added
- **Resizable Split-Screen Keyboard Pane:** Docked the built-in Kannada keyboard flush at the bottom (capped at `<= 50%` height on phones, with comfortable defaults on tablets), auto-hid the editor title/formatting bars while open, and added a bottom-right corner resizer grip.

### Fixed
- **Open File Location:** Fixed Android 11+ `SecurityException` caused by granting read flags on unowned parent directory URIs, added `<queries>` in `AndroidManifest.xml`, and added MediaStore/Samsung My Files/DocumentsUI folder navigation.

---

## [0.10.0] - 2026-11-22

### Added
- **Expanded Floating Kannada Keyboard:** Added rare/Sanskrit/Vedic characters (`ಌ`, `ೡ`, `ೢ`, `ೣ`, `ೱ`, `ೲ`), canonical long-press ordering (`ಕ, ಕ್, ಕಾ...`), Android Back button stepping via `BackHandler`, and a dedicated back arrow inside secondary keyboard views.

---

## [0.9.0] - 2026-11-22

### Added
- **Floating Draggable Kannada Varnamala Keyboard:** Introduced the 4-corner snappable floating keyboard overlay with complete Kannada Varnamala, long-press Kagunita popups, Ottakshara subscripts, Kannada numerals, and smart cursor-range insertion.

### Changed
- **Compact Title Bar & Action Cards:** Reduced Home and Editor header height (`46–48dp`) and tightened Open/New/Converter card padding.

---

## [0.8.0] - 2026-11-22

### Added
- **Standalone Kannada Converter Screen:** Repurposed the font converter into a full-fledged Home-accessible converter page (`ConverterScreen` + native Kotlin `KannadaTextConverter`) with live input auto-detection, Copy/Paste buttons, and output format filtering excluding the pasted format.
- **Modern Distinct File-Type Icons & Card 3-Dot Menu:** Added custom compact `FileTypeIcon` badges for `DOCX`, `DOC`, `PDF`, `RTF`, and `TXT`, consolidated card actions (`Open File Location`, `Share`, `Share Different Format`, `Save As`, `Rename`, `Delete`) into a `⋮` menu, and added clear unsupported-format alerts (`PPT`, `XLSX`, etc.).

---

## [0.7.0] - 2026-11-22

### Fixed
- **Dark Mode Contrast & Status Bar Icons:** Fixed dark-mode text fallback on translucent Home surfaces, switched light-mode status bar icons to dark via `enableEdgeToEdge`, removed tonal tint on dark 3-dot menus, refined background blur intensity, stacked bilingual Open/New labels to prevent clipping, added the Kadata top-bar favicon, and rescaled adaptive icons safely within the 66dp keyline.

---

## [0.6.0] - 2026-11-22

### Added
- **Kadata (ಕಡತ) Rebrand & Artwork:** Renamed the app to **Kadata (ಕಡತ)**, generated multi-density adaptive launcher icons (`mdpi`–`xxxhdpi`), extracted logo-matched Light and Dark palettes, and added aspect-aware Tablet/Mobile landing artwork with a 1.5-second first-open-of-the-day splash and blurred Home background.

---

## [0.5.0] - 2026-11-21

### Added
- **Core Offline Architecture & Multi-Format Parsers:**
  - Native Android (Kotlin + Jetpack Compose) shell paired with a zero-dependency offline HTML5 WebView editor (`WebViewAssetLoader`) and JavaScript/Kotlin bidirectional Kannada converter (`kannada-converter.js` & `KannadaTextConverter.kt`).
  - Magic-byte file router (`DocumentRouter`) and parsers for **`.docx`** (style-inheritance XML parser), **`.doc`** (Apache POI HWPF), **`.rtf`** (`\fonttbl` + `Cp1252`), **`.txt`** (UTF-8 with `Cp1252` fallback), and **`.pdf`** (native `PdfRenderer` + `PDFBox-Android` text extraction).
  - Automatic per-run legacy-to-Unicode conversion supporting Nudi (`01 e`–`18 e`), KGP, Baraha (`BRH Kannada`), ShreeLipi, Akruti, Prakashak, and Surabhi with complex syllable accuracy (*Arkavattu*, mahaprana `ü`, `ø`/`È` post-ottakshara `ೃ`, spacers `ö`/`÷`).
  - Bidirectional PC-compatible export (`DocxWriter`, `RtfDocWriter`, `PdfExporter`) with mixed-script splitting (English in `Calibri`, Kannada in `Nudi 01 e` / `BRH Kannada`).
  - Room database for Recent Files and debounced local draft recovery, configurable auto-save intervals, bilingual UI toggle (English/Kannada), Find & Replace, `ACTION_VIEW` intent filters, and `run.ps1` build/deploy helper.

### Security & Privacy
- **100% Offline:** Completely omits `android.permission.INTERNET`. Zero telemetry, ads, analytics, or cloud uploads.
- **Scoped Storage Compliant:** Uses Android Storage Access Framework (SAF) and `FileProvider` without requesting `MANAGE_EXTERNAL_STORAGE`.
