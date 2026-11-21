# KannadaDoc Test Fixtures

Drop the contents of this archive into `android/app/src/test/resources/fixtures/` for JUnit tests, or push them to your Lenovo Idea Tab Pro (`adb push`) to test SAF, WhatsApp `ACTION_VIEW` intents, and font conversion.

## Fixture Inventory

| File | Expected `DocFormat` | Magic Bytes (Hex) | Purpose |
|---|---|---|---|
| `01_router_magic_bytes/01_valid_word_nudi.docx` | `DOCX` | `50 4B 03 04` | Valid .docx containing Nudi 01 e Kannada runs mixed with Calibri/Arial English runs. |
| `01_router_magic_bytes/02_non_word_archive.xlsx` | `UNSUPPORTED` | `50 4B 03 04` | ZIP archive without word/document.xml; confirms DocumentRouter rejects non-Word ZIPs. |
| `01_router_magic_bytes/03_legacy_word97_ole2.doc` | `DOC` | `D0 CF 11 E0 A1 B1 1A E1` | Valid minimal OLE2 Compound Binary File (CFBF) header for binary .doc routing tests. |
| `01_router_magic_bytes/04_sample_document.pdf` | `PDF` | `25 50 44 46` | 2-page PDF for magic-byte routing, Jetpack two-page tablet viewer, and PDFBox extraction. |
| `01_router_magic_bytes/05_misnamed_rtf_as_doc.doc` | `RTF` | `7B 5C 72 74 66` | RTF with Nudi 01 e \fonttbl and Cp1252 hex escapes, deliberately named .doc. |
| `01_router_magic_bytes/06_nudi_cp1252_notes.txt` | `TXT` | `50 C0 A3 C1 F0 6C 50 C0` | Windows-1252 (Cp1252) Nudi text (invalid UTF-8) to verify high-ASCII acceptance & fallback. |
| `01_router_magic_bytes/07_binary_image_rejected.png` | `UNSUPPORTED` | `89 50 4E 47 0D 0A 1A 0A` | Binary PNG with NUL bytes; confirms DocumentRouter rejects non-document binaries. |
| `02_parser_and_font_fixtures/08_misnamed_docx_as_doc.doc` | `DOCX` | `50 4B 03 04` | Real .docx renamed to .doc; confirms magic-byte detection overrides file extension. |
| `02_parser_and_font_fixtures/09_style_inheritance_nudi.docx` | `DOCX` | `50 4B 03 04` | Tests all 4 DocxParser style inheritance levels (direct rPr, rStyle, pStyle+basedOn, docDefaults). |
| `02_parser_and_font_fixtures/10_mixed_table_baraha_english.docx` | `DOCX` | `50 4B 03 04` | Word table combining Calibri English headers with Nudi 01 e, BRH Kannada, and Shree-Kan-0850 cells. |
| `02_parser_and_font_fixtures/11_unicode_kannada_already_clean.docx` | `DOCX` | `50 4B 03 04` | Clean Unicode Kannada (Noto Sans Kannada); confirms converter passes Unicode runs untouched. |
| `02_parser_and_font_fixtures/12_utf8_bom_rtf.rtf` | `RTF` | `EF BB BF 7B 5C 72 74 66` | RTF prefixed with a UTF-8 BOM (EF BB BF) to verify DocumentRouter BOM offset handling. |

## Notes
- **Nudi ASCII text:** Fixtures `01`, `05`, `06`, `08`, `09` include authentic Nudi 01 e strings (`PÀ£ÁðlPÀ ¸ÀPÁðgÀ` = ಕರ್ನಾಟಕ ಸರ್ಕಾರ, `PÀ£ÀßqÀ` = ಕನ್ನಡ, `£ÀªÀÄ¸ÁÌgÀ` = ನಮಸ್ಕಾರ, `¢£ÁAPÀ` = ದಿನಾಂಕ, `¨ÉAUÀ¼ÀÆgÀÄ` = ಬೆಂಗಳೂರು).
- **Legacy `.doc` (`03_legacy_word97_ole2.doc`):** Valid OLE2 Compound Binary File (CFBF) header container for Phase 1 routing. For Phase 2 Apache POI `HWPF` paragraph extraction tests, also copy 2–3 real Word 97-2003 `.doc` files from your dad's actual documents into `02_parser_and_font_fixtures/`.
- **Quick tablet push:**
  ```bash
  adb push 01_router_magic_bytes /sdcard/Download/KannadaDocTests/
  adb push 02_parser_and_font_fixtures /sdcard/Download/KannadaDocTests/
  ```
