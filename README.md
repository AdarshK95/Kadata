# Kadata (ಕಡತ) — Offline Kannada Document Reader & Editor

[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2028%2B)-3DDC84?style=flat&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=flat&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-GPL%203.0-blue.svg)](LICENSE)

**Kadata (ಕಡತ)** is a 100% offline, privacy-first, tablet-optimized Android document editor designed for reading, editing, and creating documents in clean **Unicode Kannada**.

Its core design principle is **invisible conversion**: tap any `.docx`, `.doc`, `.rtf`, `.txt`, or `.pdf` file from WhatsApp, Gmail, or File Manager, and Kadata opens it immediately in clean, editable Unicode Kannada—with zero confusing font terminology on screen.

---

## 🌟 Key Features

- **Invisible Legacy Font Conversion**: Automatic, per-run conversion of 15+ legacy ASCII/ANSI fonts (Nudi, Baraha, ShreeLipi, Akruti, Prakashak, Surabhi, etc.) into standard Unicode Kannada.
- **Multi-Format Support**:
  - **`.docx` (Modern Word)**: Native style-inheritance parser (`w:rPr`, `w:rStyle`, `w:pStyle`, `w:docDefaults`) preserving tables, lists, and formatting.
  - **`.doc` (Word 97-2003)**: Binary OLE2 extraction powered by Apache POI HWPF.
  - **`.rtf` (Rich Text)**: RTF font table (`\fonttbl`) decoding with `Windows-1252` (Cp1252) high-ASCII support.
  - **`.txt` (Text Notes)**: Smart encoding detection (strict UTF-8 with automatic fallback to Cp1252 for legacy Nudi plain text).
  - **`.pdf` (PDFs)**: Built-in PDF viewing with one-tap **"Edit Text"** mode powered by PDFBox-Android.
- **Rich Text Kannada Editor**:
  - Integrated rich-text engine with TipTap/ProseMirror running offline in a local WebView.
  - Full support for Android IMEs (Gboard Kannada transliteration, handwriting, voice dictation).
  - Floating onscreen Kannada keyboard for quick editing and symbol insertion.
  - Bold, Italic, Underline, H1, H2, Bullet/Ordered Lists, Text Alignment, and Tables.
- **Tablet & Phone Optimized**:
  - Adaptive layout with 48dp+ touch targets, 1.7 line height for clean *ottakshara* rendering, and soft paper sheet margins.
  - Bilingual UI labels (Kannada + English toggle).
- **Non-Destructive Storage**:
  - Never overwrites the original legacy file.
  - Automatic draft saving via Room Database every 10 seconds.
  - 1-tap **Export/Share as clean Unicode Word (`.docx`)** or **Share as PDF** (print adapter).
  - Optional reverse export to **Legacy Nudi (`.docx`)** for government submission portals.

---

## 🏛️ System Architecture

Kadata uses a **Native Kotlin + Local Embedded WebView** hybrid architecture to provide modern rich-text editing while keeping 100% of file parsing, conversion, and database storage offline on the device:

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                    ANDROID SHELL (Kotlin + Jetpack Compose)             │
│  • Intent Filters (ACTION_VIEW) + Storage Access Framework (SAF)        │
│  • Recent Files & Auto-Save Drafts (Room DB)                            │
│  • Tablet-First Toolbar, File Library, Print/PDF Export, Share Sheet    │
└───────────────────┬───────────────────────────────────────┬─────────────┘
                    │                                       │
         ┌──────────▼──────────┐                 ┌──────────▼──────────┐
         │   NATIVE PARSERS    │                 │   NATIVE PDF VIEW   │
         │ • .docx: Zip+XML    │                 │ • Jetpack PDF       │
         │ • .doc: POI HWPF    │                 │ • PDFBox-Android    │
         │ • .rtf: FontTbl     │                 │   (Text Extraction) │
         │ • .txt: Cp1252/UTF8 │                 │                     │
         └──────────┬──────────┘                 └──────────┬──────────┘
                    │ Intermediate Document Model (IDM) JSON│
                    ▼                                       ▼
┌─────────────────────────────────────────────────────────────────────────┐
│             LOCAL WEBVIEW ENGINE (WebViewAssetLoader - Offline)         │
│  • Sanchaya kannada-font-converter (Per-Run ASCII → Unicode)            │
│  • TipTap (ProseMirror) Rich-Text Editor (Unicode Kannada + Tables)     │
│  • Bundled Fonts: Noto Sans Kannada (Unicode)                           │
│  • docx.js (Clean Unicode .docx Export)                                 │
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
│   │       │   ├── AndroidManifest.xml  # ACTION_VIEW Intent filters & FileProvider
│   │       │   ├── assets/              # Offline web assets & fonts
│   │       │   │   ├── editor/          # TipTap editor & Sanchaya converter engine
│   │       │   │   └── fonts/           # Noto Sans Kannada
│   │       │   └── java/com/kannadadoc/
│   │       │       ├── data/            # Room database, DAO, SAF storage manager
│   │       │       ├── export/          # PDF export, Docx writer, Share helpers
│   │       │       ├── parser/          # Magic-byte router, Docx, HWPF, RTF, PDF parsers
│   │       │       └── ui/              # Compose screens (Home, Editor, PDF, Theme)
│   │       └── test/                    # JUnit tests & unit test fixtures
│   ├── build.gradle.kts                 # Root build configuration
│   ├── gradle/                          # Version catalog (libs.versions.toml) & wrapper
│   └── gradle.properties                # AndroidX, JVM configuration
├── docs/                                # Project documentation, architecture & board
├── run.ps1                              # Interactive build, install & scrcpy mirror script
└── README.md                            # Project overview & documentation
```

---

## 🚀 Building & Running

### Prerequisites
- **Android SDK** (API Level 35, Build-Tools 35.0.0, Minimum API 28)
- **Java Development Kit (JDK)**: JDK 17 or higher
- **Gradle**: 8.11.1+ (included via Gradle Wrapper)
- Optional: `adb` and `scrcpy` in your PATH for live device mirroring

### 1. Build via Gradle
```bash
cd android
./gradlew assembleDebug
```

### 2. Run Tests
Verify magic-byte routing, high-ASCII fallback, and parser resolution:
```bash
cd android
./gradlew testDebugUnitTest
```

### 3. Deploy via Helper Script (Windows)
From the project root:
```powershell
.\run.ps1
```
The menu lets you:
- Build and install the APK onto a connected phone/tablet.
- Start `scrcpy` wireless or USB screen mirroring.
- Live-tail crashes with logcat filters.

---

## 🔒 Privacy & Permissions

- **100% Offline**: Kadata contains **no network permissions** (`INTERNET` permission is absent). No telemetry, analytics, or external calls are made.
- **Scoped Storage**: Uses Android's Storage Access Framework (SAF) to read and save only the documents you explicitly open.

---

## 📄 License

This project is licensed under the [GNU General Public License v3.0](LICENSE). Legacy conversion routines are powered by Sanchaya's `kannada-font-converter` (GPL-3.0).
