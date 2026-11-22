# Kadata (ಕಡತ) — Offline Kannada Document Reader, Editor & Converter

[![Download APK](https://img.shields.io/badge/Download-Kadata%20APK-007ACC?style=flat&logo=android&logoColor=white)](https://github.com/AdarshK95/Kadata/releases/latest)
[![Version](https://img.shields.io/badge/Version-0.17.2-1A4D7A?style=flat)](VERSION)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2028%2B)-3DDC84?style=flat&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=flat&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Offline](https://img.shields.io/badge/Privacy-100%25%20Offline-9E7128?style=flat)](#privacy--permissions)
[![License](https://img.shields.io/badge/License-GPL%203.0-blue.svg)](LICENSE)

**Kadata (ಕಡತ)** is a 100% offline, privacy-first Android document reader, editor, and Kannada format converter built for phones and tablets.

Its core design principle is **invisible, lossless conversion**: open any `.docx`, `.doc`, `.rtf`, `.txt`, or `.pdf` file from WhatsApp, Gmail, or your File Manager—whether authored in **Nudi (`ನುಡಿ`)**, **Baraha (`ಬರಹ`)**, or **Modern Universal Kannada (`ಆಧುನಿಕ ಸಾರ್ವತ್ರಿಕ ಕನ್ನಡ`)**—and Kadata automatically detects and renders it cleanly for effortless reading, editing, and PC-compatible sharing.

---

## 🌟 Key Features

- **Automatic Bidirectional Kannada Conversion**:
  - Per-run detection and conversion across legacy ASCII/ANSI fonts (Nudi `01 e`–`18 e`, KGP, Baraha / `BRH Kannada`, ShreeLipi, Akruti, Prakashak, Surabhi) and Modern Universal Kannada.
  - Handles complex Kannada orthography accurately (*Arkavattu* `ರ್`, mahaprana `ü`, post-ottakshara `ೃ`, compound syllables, and mixed English + Kannada text).
  - **PC-Compatible Round-Trip Export**: Exports `.docx` and `.doc` files with script-splitting—keeping English text in `Calibri` and Kannada text in `Nudi 01 e` or `BRH Kannada` for Windows PC users—or exports as **Modern Universal Kannada** / **PDF**.
- **Multi-Format Document Support**:
  - **`.docx` (Modern Word)**: Custom XML parser with full style-inheritance resolution (`w:rPr`, `w:rStyle`, `w:pStyle`, `w:docDefaults`) preserving headings, lists, alignment, and tables.
  - **`.doc` (Word 97–2003)**: Binary OLE2 extraction powered by Apache POI HWPF.
  - **`.rtf` (Rich Text)**: Native `\fonttbl` font-table decoder with `Windows-1252` (`Cp1252`) support.
  - **`.txt` (Plain Text)**: Strict UTF-8 decoding with automatic `Cp1252` fallback for legacy Nudi text files.
  - **`.pdf` (PDF Viewer + Text Extraction)**: Native `PdfRenderer` viewer with horizontal swipe navigation (`HorizontalPager`), smooth pinch-to-zoom (`1x–4x`), pan, double-tap zoom, and one-tap **"Edit"** text extraction via `PDFBox-Android`.
  - Graceful unsupported-format alerts for non-document files (e.g., `.ppt`, `.xlsx`, images).
- **Offline Rich-Text Editor**:
  - Lightweight, zero-dependency offline HTML5 rich-text canvas running via `WebViewAssetLoader`.
  - Formatting toolbar (Bold, Italic, Underline, H1, H2, Bullet/Ordered Lists, Alignment, Font Size `A−`/`A+`), natural pinch-to-zoom (`0.5x–4.0x`), and responsive table wrapping.
  - **100-Step Undo / 100-Step Redo** with automatic scroll-to-section and subtle line highlight.
  - **Normalized Cross-Span Find & Replace** (trims/normalizes pasted whitespace and matches across inline styling spans, with toggleable Replace row).
  - **Change Document Type** & **In-Editor Rename** directly from the editor.
- **Built-In Kannada Varnamala & Halekannada Keyboard (`ಕ`) + System Keyboard Control (`⌨️`)**:
  - Draggable 4-corner hover buttons: **`ಕ`** opens the built-in Kannada keyboard; **`⌨️`** toggles the default Android keyboard (tap) or opens the Input Method Picker (long-press).
  - Complete **Varnamala (`ವರ್ಣಮಾಲೆ`)** with unified single-page vertical long-press popups showing all 17 Kagunita variants plus every consonant's deduplicated Ottakshara combinations (including full 34-consonant matrices), attaching **Ottakshara Subscripts (`ಒತ್ತಕ್ಷರ`)**, dedicated **Halekannada (`ಹಳೆಗನ್ನಡ`)** tab (`ಱ`, `ೞ`, classical clusters, `಄` Siddham, `ಽ`, `ೱ`, `ೲ`, poetry/shasana signs), and **Kannada Numerals & Symbols (`೧೨೩`)**.
  - Adaptive layout: resizable side-by-side pane on tablets; `<= 50%` bottom-docked split pane on phones; guaranteed visible blinking caret and 3-page bottom scroll buffer.
  - Optional **Block Auto-Opening Keyboards** setting for distraction-free reading and manual keyboard triggering.
- **Standalone Kannada Converter**:
  - Dedicated converter screen accessible directly from the Home action bar—paste text in any format for live auto-detection, smart output format selection, and one-tap Copy/Paste.
- **Adaptive Phone & Tablet Experience**:
  - Landscape Tablet split Home screen (uninterrupted artwork showcase on the left, blurred workspace on the right).
  - **Grid View** and full-width **Details List View** (`Name | Type | Format | Size | Modified | Status`) with consistent metadata backfill.
  - **Auto-Detect System Theme** (default), **Light Parchment**, and **True Pure Black (OLED) Dark Mode**, plus English/Kannada UI toggle (`Eng` / `ಕನ್ನಡ`).
  - Non-destructive auto-saving (debounced local Room drafts + configurable file auto-save intervals) and automatic Recent Files registration for all saved/shared exports.

---

## 🏛️ System Architecture

Kadata uses a **Native Kotlin + Local Offline WebView** hybrid architecture so all parsing, font conversion, editing, export, and database storage execute 100% locally on the device:

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                    ANDROID SHELL (Kotlin + Jetpack Compose)             │
│  • Intent Filters (ACTION_VIEW) + Storage Access Framework (SAF)        │
│  • Recent Files & Auto-Save Drafts (Room DB) + AppPreferences           │
│  • Adaptive Phone/Tablet Home, Standalone Converter, Floating ಕ KB      │
│  • Native Export Writers: DocxWriter, RtfDocWriter, PdfExporter, Print  │
└───────────────────┬───────────────────────────────────────┬─────────────┘
                    │                                       │
         ┌──────────▼──────────┐                 ┌──────────▼──────────┐
         │   NATIVE PARSERS    │                 │   NATIVE PDF VIEW   │
         │ • DocumentRouter    │                 │ • PdfRenderer +     │
         │ • .docx: Zip + XML  │                 │   HorizontalPager   │
         │ • .doc: POI HWPF    │                 │ • Pinch-Zoom / Pan  │
         │ • .rtf: FontTbl     │                 │ • PDFBox-Android    │
         │ • .txt: UTF8/Cp1252 │                 │   (Text Extraction) │
         └──────────┬──────────┘                 └──────────┬──────────┘
                    │ Intermediate Document Model (IDM) JSON│
                    ▼                                       ▼
┌─────────────────────────────────────────────────────────────────────────┐
│             LOCAL WEBVIEW ENGINE (WebViewAssetLoader — Offline)         │
│  • Offline HTML5 Rich-Text Editor (editor.js / editor.css / index.html) │
│  • Bidirectional Legacy ↔ Unicode Converter (kannada-converter.js)      │
│  • 100-Step Undo/Redo, Normalized Cross-Span Search, Virtual Caret      │
│  • Bundled Fonts: Noto Sans Kannada, Nudi 01 e, BRH Kannada             │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 📁 Repository Structure

```text
.
├── android/                             # Android application project
│   ├── app/
│   │   ├── build.gradle.kts             # Dependencies (Compose, Room, POI HWPF, PDFBox)
│   │   └── src/
│   │       ├── main/
│   │       │   ├── AndroidManifest.xml  # ACTION_VIEW filters, FileProvider & file-manager queries
│   │       │   ├── assets/              # Offline web assets & bundled fonts
│   │       │   │   ├── editor/          # index.html, editor.js, editor.css, kannada-converter.js
│   │       │   │   └── fonts/           # unicode/ (Noto Sans Kannada) & legacy/ (Nudi01e, BRHKannada)
│   │       │   ├── res/                 # Adaptive icons & Tablet/Mobile WebP artwork
│   │       │   └── java/com/kannadadoc/
│   │       │       ├── converter/       # Pure Kotlin KannadaTextConverter (Nudi/Baraha/Universal)
│   │       │       ├── data/            # Room DB, DAOs, AppPreferences, SAF & FileLocationHelper
│   │       │       ├── export/          # DocxWriter, RtfDocWriter, PdfExporter, ShareHelper
│   │       │       ├── parser/          # Magic-byte DocumentRouter, Docx, HWPF, RTF, TXT, PDF parsers
│   │       │       └── ui/              # Compose UI (home, editor, pdf, converter, common, theme)
│   │       └── test/                    # JUnit unit tests & test fixtures
│   ├── build.gradle.kts                 # Root Gradle build configuration
│   └── gradle/                          # Version catalog & Gradle wrapper
├── VERSION                              # Single source of truth for current app version (0.17.0)
├── CHANGELOG.md                         # Release history & versioning policy
├── run.ps1                              # Interactive Windows build, install & scrcpy mirror script
└── README.md                            # Project overview & documentation
```

---

## 🚀 Building & Running

### Prerequisites
- **Android SDK** (Compile SDK 35, Minimum API 28 / Android 8.0+)
- **Java Development Kit (JDK)**: JDK 17 or higher
- **Gradle**: Included via Gradle Wrapper (`gradlew`)
- Optional: `adb` and `scrcpy` in your `PATH` for live device mirroring

### 1. Build via Gradle
```bash
cd android
./gradlew assembleDebug
```

### 2. Run Unit Tests
Verify magic-byte routing, legacy ASCII fallback, and parser resolution:
```bash
cd android
./gradlew testDebugUnitTest
```

### 3. Deploy via Helper Script (Windows)
From the project root:
```powershell
.\run.ps1
```
The script lets you build and install the APK onto a connected USB/wireless phone or tablet, launch `scrcpy` screen mirroring, and inspect filtered logcat output.

---

## 🔢 Versioning Policy

Kadata currently uses pre-1.0 semantic versioning tracked in [`VERSION`](VERSION) and [`CHANGELOG.md`](CHANGELOG.md):
- **`0.x.0`** — Significant new features or major behavioral improvements.
- **`0.x.y`** — Minor bug fixes, small enhancements, or cosmetic refinements.
- **`1.0.0`** — Reserved for the official production v1 release.

---

## 🔒 Privacy & Permissions

- **100% Offline & On-Device**: Kadata intentionally omits `android.permission.INTERNET`. Everything—file parsing, Kannada conversion, drafts, and exports—runs locally on your device.
- **Zero Data Collection**: We never read your files, collect your content, or transmit any telemetry, analytics, or crash data.
- **Scoped Storage Compliant**: Uses Android's Storage Access Framework (SAF) to access only the documents you explicitly open or save, without requesting `MANAGE_EXTERNAL_STORAGE`.

---

## 👨‍💻 Developer

Developed and maintained by **Adarsh Katagihallimath**  
- **GitHub Repository**: [https://github.com/AdarshK95/Kadata](https://github.com/AdarshK95/Kadata)
- **Issues & Feedback**: [https://github.com/AdarshK95/Kadata/issues](https://github.com/AdarshK95/Kadata/issues)

---

## 📄 License

This project is licensed under the [GNU General Public License v3.0](LICENSE). Legacy Kannada conversion tables and mappings are inspired by Sanchaya's `kannada-font-converter` (GPL-3.0).
