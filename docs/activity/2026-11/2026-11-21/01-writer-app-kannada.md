<!--
feature: 1
title: KannadaDoc - Offline Tablet-First Kannada Writer & Document App
type: plan
phase: Plan
pointer: 1.1 › 0/5
updated: 21 Nov 26, 09.35 PM
file: 2026-11/2026-11-21/01-writer-app-kannada.md
-->

## User summary

**What this is:** KannadaDoc — An offline, tablet-first Android document & writer app designed for Lenovo Idea Tab Pro (12.7") featuring invisible conversion from legacy ASCII fonts (Nudi, Baraha, ShreeLipi, etc.) to editable Unicode Kannada.
**What you asked, as I understand it:** Implement the full architectural blueprint into the project plan and documentation.
**Now:** Blueprint captured, architecture defined, ready to approve phased implementation.
**Running:** nothing
**Next:** User approves plan or selects starting phase (Android scaffold vs Web Editor pipeline).
**Waiting on you:** Confirm which phase to start first (Phase 1/2 Android parsers or Phase 3 Web-editor Sanchaya bridge).

### What happened
- 21 Nov 26, 09.35 PM · Plan — Documented system architecture, tech stack, intermediate document model (IDM), UI/UX specs, folder layout, and 5-phase implementation plan.

## Instructions

- I1 · 21 Nov 26, 09:33 PM — "create a empty plan file call it writer app - kannada"
- I2 · 21 Nov 26, 09.35 PM — "here is the plan just add it to the documentation This blueprint covers the full architecture, data pipelines, UI/UX design, folder layout, and phased build plan for an offline, tablet-first Android document app (working name: KannadaDoc) built for the Lenovo Idea Tab Pro (12.7\")."

## Clarifications

## Context

- Target Hardware: Lenovo Idea Tab Pro (12.7" 3K display, stylus support).
- Core Principle: Invisible conversion — open .doc, .docx, .rtf, .txt, .pdf and view/edit directly in clean Unicode Kannada with zero confusing font terminology.
- Tech Stack: Native Kotlin (Android Shell, Room, SAF, POI HWPF, PDFBox) + Embedded Local WebView (Vite, TipTap ProseMirror, Sanchaya font converter, docx.js).

## Report

### Summary
Full architecture, data pipelines, and phased build plan for KannadaDoc.

### 1. System Architecture & Tech Stack
- **Android Shell**: Kotlin 2.x, Jetpack Compose Material 3 (minSdk = 28, 	argetSdk = 35), Room DB, SAF, Intent Filters.
- **Parsers**:
  - .docx: Native ZipInputStream + XmlPullParser with <w:styles> inheritance traversal.
  - .doc: org.apache.poi:poi + org.apache.poi:poi-scratchpad (5.2.3), excluding poi-ooxml & xmlbeans.
  - .rtf: Lightweight native parser with \fonttbl parsing and Cp1252 hex escape handling.
  - .txt: Strict UTF-8 with fallback to Cp1252.
  - .pdf: Native androidx.pdf:pdf-viewer-fragment + PDFBox-Android text extraction on "Edit Text".
- **Local WebView Engine**:
  - androidx.webkit:WebViewAssetLoader serving from https://appassets.androidplatform.net/.
  - sanchaya/kannada-font-converter (15 legacy fonts, 2,077 test cases).
  - TipTap (ProseMirror) rich-text editor for clean Android IME / Gboard / voice typing composition.
  - docx.js for clean Unicode .docx export.

### 2. Intermediate Document Model (IDM) & Conversion Pipeline
- Extract per-run text, font family, formatting (bold/italic/size/color) into structured JSON.
- Run-level font matching via regex table mapping to Sanchaya converter IDs (Nudi, Baraha, shreelipi, kruti, prakashak, surabhi, etc.).
- Native "View Original" fallback using bundled legacy TTF fonts in @font-face.

### 3. Tablet-First UI/UX Design
- **Home Library**: 56dp+ touch targets, 2 large primary cards ("Open File", "New Writing"), Recent Files grid, bilingual labels.
- **Document Editor**: Clean top bar (Undo/Redo, Zoom, Save, Share PDF), collapsible formatting strip, centered 820px paper sheet, 18pt Noto Sans Kannada with 1.7 line-height (prevent ottakshara clipping), subtle auto-fix banner with manual override bottom sheet.
- **PDF Viewer**: Native Jetpack PDF viewer with stylus annotation & one-tap "[ ✏️ Edit Text ]" button.

### 4. Storage & Saving
- Non-destructive save policy: Never overwrite original legacy file; save working copies as <Name>_Unicode.docx via SAF.
- Auto-save Room draft every 10 seconds.
- 1-tap Share as PDF via WebView.createPrintDocumentAdapter() and Android ACTION_SEND.

## Items

### 1.1 · Phase 1: Android Shell, File Intents & Storage (scaffold)
- Phase: Plan
- Pointer: 1.1 › 0/4
- Steps:
  1. Scaffold Kotlin + Jetpack Compose project structure (minSdk 28, Material 3).
  2. Configure AndroidManifest.xml with ACTION_VIEW intent filters & FileProvider.
  3. Implement SAF picker + contentResolver.takePersistableUriPermission() and Room RecentFileDao.
  4. Implement magic-byte routing (50 4B 03 04, D0 CF 11 E0, 25 50 44 46, 7B 5C 72 74 66).

### 1.2 · Phase 2: Native Parsers & Test Harness (build)
- Phase: Plan
- Pointer: 1.2 › 0/4
- Steps:
  1. Build DocxParser.kt with style inheritance resolution (word/styles.xml).
  2. Integrate Apache POI HWPF for DocHwpfParser.kt.
  3. Build RtfParser.kt (Cp1252) and TxtParser.kt.
  4. Implement JUnit test harness with real sample fixture documents.

### 1.3 · Phase 3: Offline Web Editor & Sanchaya Conversion Bridge (build)
- Phase: Plan
- Pointer: 1.3 › 0/4
- Steps:
  1. Scaffold web-editor/ with Vite + TipTap.
  2. Vendor Sanchaya converter engine and wire FontRegistry.ts + pipeline.ts.
  3. Implement Android @JavascriptInterface bridge & WebViewAssetLoader.
  4. Validate Kannada IME typing, handwriting, and voice dictation in TipTap.

### 1.4 · Phase 4: Save, Export & PDF Flow (build)
- Phase: Plan
- Pointer: 1.4 › 0/4
- Steps:
  1. Build docxExporter.ts (TipTap JSON → docx.js Blob → Base64).
  2. Implement PdfExporter.kt using PrintDocumentAdapter + @media print CSS.
  3. Integrate androidx.pdf:pdf-viewer-fragment and wire "Edit Text" extraction.
  4. Wire Room 10s auto-save.

### 1.5 · Phase 5: Tablet Polish & Hardening (build)
- Phase: Plan
- Pointer: 1.5 › 0/4
- Steps:
  1. Build Home Library Compose screen with bilingual labels and large touch targets.
  2. Add auto-dismissing format banner and manual font override bottom sheet.
  3. Harden edge cases (WhatsApp transient URIs, complex ottaksharas, large docs).
  4. Release build configuration.

## Findings

## Decisions

## Build findings

## Changes

## Realign

## Verification

## Open items

