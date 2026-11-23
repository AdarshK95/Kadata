package com.kannadadoc.data.storage

import android.content.Context
import android.net.Uri
import com.kannadadoc.converter.KannadaTextConverter
import com.kannadadoc.parser.DocBlock
import com.kannadadoc.parser.DocFormat
import com.kannadadoc.parser.DocumentParserFacade
import com.kannadadoc.parser.ParsedDocument
import com.kannadadoc.parser.SafeZipReader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.apache.poi.hwpf.HWPFDocument

/**
 * Multi-tier document title healer designed to replace opaque WhatsApp / provider filenames
 * (e.g., `DOC-20261122-WA0000.docx`, `DOC-20261122-WA0002 (1).pdf`, `1732265.docx`, `msf:1234`)
 * with human-readable titles:
 *   Tier 1: Embedded document metadata (`<dc:title>` in DOCX/ODT/EPUB, `<title>` in HTML,
 *           PDF DocumentInformation title, DOC SummaryInformation title) — stripping noisy
 *           exporter prefixes like `"Microsoft Word - "` and converting legacy Nudi/Baraha ASCII.
 *   Tier 2: First meaningful heading or opening line from the document's actual text content
 *           (automatically normalized via [KannadaTextConverter] so Nudi/Baraha Kannada headings
 *           heal into clean Modern Universal Kannada titles).
 */
object DocumentTitleHealer {

    private const val MAX_TITLE_LENGTH = 60

    private val INTERNAL_HASH_PREFIX_REGEX = Regex("^[0-9a-f]{12}_", RegexOption.IGNORE_CASE)
    private val TRAILING_COPY_COUNTER_REGEX = Regex("(?:\\s*\\(\\d+\\)|[_\\-]\\d+)$")
    private val WHATSAPP_MEDIA_REGEX = Regex(
        "^(DOC|AUD|IMG|VID|PTT)-\\d{8}-WA\\d+.*$",
        RegexOption.IGNORE_CASE
    )
    private val WHATSAPP_VERBOSE_REGEX = Regex(
        "^WhatsApp\\s+(Document|File|Audio|Image|Video)\\b.*$",
        RegexOption.IGNORE_CASE
    )
    private val PURE_NUMERIC_REGEX = Regex("^\\d+$")
    private val OPAQUE_PROVIDER_ID_REGEX = Regex(
        "^(msf|document|primary|raw|media|item|file)[:_\\-\\s]?\\d+$",
        RegexOption.IGNORE_CASE
    )
    private val GENERIC_PLACEHOLDER_REGEX = Regex(
        "^(Untitled|Document|New\\s*Document|Doc|File|Note|Word\\s*Document|Presentation|Slide|Book|Print|Layout|Sheet|Page)(\\s*[-_]?\\d*)?$",
        RegexOption.IGNORE_CASE
    )
    private val SCANNER_GENERIC_REGEX = Regex(
        "^(Scan|Scanned|CamScanner|Adobe\\s*Scan|DocScan)[\\s_\\-0-9.]*$",
        RegexOption.IGNORE_CASE
    )
    private val EXPORTER_PREFIX_REGEX = Regex(
        "^(Microsoft\\s+Word|Microsoft\\s+PowerPoint|LibreOffice\\s+Writer|WPS\\s+Writer)\\s*[-–—:]\\s*",
        RegexOption.IGNORE_CASE
    )
    private val DC_TITLE_REGEX = Regex("<(?:dc:)?title[^>]*>([^<]+)</(?:dc:)?title>", RegexOption.IGNORE_CASE)
    private val HTML_TITLE_REGEX = Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE)
    private val UNSAFE_FILENAME_CHARS_REGEX = Regex("[/\\\\:*?\"<>|\\r\\n\\t]+")
    private val MEANINGFUL_LETTER_REGEX = Regex("[\\u0C80-\\u0CFFA-Za-z]")
    private val NOISE_LINE_REGEX = Regex(
        "^(page\\s*\\d+(\\s*(/|of)\\s*\\d+)?|ಪುಟ\\s*\\d+(\\s*/\\s*\\d+)?|\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4}|[\\d\\s\\-–—.,:/|•*()]+)$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Returns true when [rawName] is a generic WhatsApp media name, opaque provider ID,
     * pure numeric name, or placeholder like `"Untitled"` / `"Document1"`.
     */
    fun isGenericDisplayName(rawName: String): Boolean {
        val strippedHash = rawName.trim().replace(INTERNAL_HASH_PREFIX_REGEX, "")
        if (strippedHash.isEmpty()) return true

        val baseNoExt = if (strippedHash.contains('.')) {
            strippedHash.substringBeforeLast('.')
        } else {
            strippedHash
        }.trim()

        val normalizedBase = baseNoExt.replace(TRAILING_COPY_COUNTER_REGEX, "").trim()
        if (normalizedBase.isEmpty()) return true

        return WHATSAPP_MEDIA_REGEX.matches(normalizedBase) ||
            WHATSAPP_VERBOSE_REGEX.matches(normalizedBase) ||
            PURE_NUMERIC_REGEX.matches(normalizedBase) ||
            OPAQUE_PROVIDER_ID_REGEX.matches(normalizedBase) ||
            GENERIC_PLACEHOLDER_REGEX.matches(normalizedBase) ||
            SCANNER_GENERIC_REGEX.matches(normalizedBase)
    }

    /**
     * Cleans, decodes, converts (if legacy Nudi/Baraha ASCII), and validates a candidate title.
     * Returns `null` if the candidate is blank, generic, or lacks meaningful text.
     */
    fun cleanCandidateTitle(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        var s = raw
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .substringBefore('\n')
            .substringBefore('\r')
            .trim()

        // Strip common Word/PDF exporter prefixes like "Microsoft Word - MyDoc.docx"
        s = s.replace(EXPORTER_PREFIX_REGEX, "").trim()
        // If metadata itself ended with an office extension (e.g. "Microsoft Word - Report.docx"), strip it cleanly
        val lower = s.lowercase()
        for (ext in listOf(".docx", ".doc", ".pdf", ".odt", ".rtf", ".txt", ".html", ".htm", ".epub")) {
            if (lower.endsWith(ext) && s.length > ext.length + 1) {
                s = s.dropLast(ext.length).trim()
                break
            }
        }

        // Convert legacy Nudi/Baraha ASCII headings/titles into readable Modern Universal Kannada
        if (KannadaTextConverter.hasLegacyAsciiSignature(s)) {
            s = KannadaTextConverter.convertMixedAsciiToUnicode(s)
        }

        if (NOISE_LINE_REGEX.matches(s.trim())) return null

        s = s
            .replace(UNSAFE_FILENAME_CHARS_REGEX, " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim(' ', '-', '–', '—', '.', ',', ':', ';', '_', '•', '·', '।', '॥', '|')

        if (s.length < 2) return null
        if (!MEANINGFUL_LETTER_REGEX.containsMatchIn(s)) return null
        if (NOISE_LINE_REGEX.matches(s)) return null
        if (isGenericDisplayName(s)) return null

        if (s.length > MAX_TITLE_LENGTH) {
            val cut = s.substring(0, MAX_TITLE_LENGTH)
            val lastSpace = cut.lastIndexOf(' ')
            s = if (lastSpace >= MAX_TITLE_LENGTH / 2) {
                cut.substring(0, lastSpace).trimEnd(' ', '-', ',', '.', ':', ';')
            } else {
                cut.trimEnd(' ', '-', ',', '.', ':', ';')
            }
        }

        return s.takeIf { it.length >= 2 && !isGenericDisplayName(it) }
    }

    /**
     * Extracts the best human-readable title from a parsed document (preferring the first heading,
     * falling back to the first meaningful non-noise paragraph or header table cell).
     */
    fun extractTitleFromParsedDocument(parsed: ParsedDocument): String? {
        val unicodeDoc = KannadaTextConverter.normalizeDocumentToUnicode(parsed)

        // Pass 1: First heading among early blocks
        for (block in unicodeDoc.blocks.take(12)) {
            if (block is DocBlock.Paragraph && block.headingLevel in 1..3) {
                val rawText = block.runs.joinToString("") { it.text }.trim()
                val cleaned = cleanCandidateTitle(rawText)
                if (cleaned != null) return cleaned
            }
        }

        // Pass 2: First meaningful non-empty paragraph (or early table cell)
        for (block in unicodeDoc.blocks.take(20)) {
            when (block) {
                is DocBlock.Paragraph -> {
                    val rawText = block.runs.joinToString("") { it.text }
                    for (line in rawText.lines()) {
                        val cleaned = cleanCandidateTitle(line)
                        if (cleaned != null) return cleaned
                    }
                }
                is DocBlock.Table -> {
                    for (row in block.rows.take(2)) {
                        for (cell in row.take(3)) {
                            for (p in cell) {
                                val rawText = p.runs.joinToString("") { it.text }
                                val cleaned = cleanCandidateTitle(rawText)
                                if (cleaned != null) return cleaned
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * End-to-end healing entrypoint: if [currentDisplayName] is generic (e.g. WhatsApp `DOC-...-WA...`),
     * attempts Tier 1 (embedded metadata) followed by Tier 2 (first heading/line of document content).
     * Always preserves the original file extension.
     */
    suspend fun healDisplayName(
        context: Context,
        uri: Uri,
        currentDisplayName: String,
        format: DocFormat,
        parserFacade: DocumentParserFacade
    ): String {
        if (!isGenericDisplayName(currentDisplayName)) {
            return currentDisplayName
        }

        // Fast path for PDF: read DocumentInformation.title AND Page 1 text in a single PDDocument.load
        if (format == DocFormat.PDF || format == DocFormat.PDF_EXTRACTED) {
            val pdfTitle = extractPdfTitleOrFirstLine(context, uri)
            if (pdfTitle != null) {
                return attachExtensionIfMissing(pdfTitle, currentDisplayName, format)
            }
            return currentDisplayName
        }

        // Tier 1: Fast embedded document metadata across DOCX, ODT, EPUB, HTML, DOC
        val metaTitle = extractEmbeddedMetadataTitle(context, uri, format)
        if (metaTitle != null) {
            return attachExtensionIfMissing(metaTitle, currentDisplayName, format)
        }

        // Tier 2: First meaningful heading / opening line from actual document content
        val parsed = runCatching {
            parserFacade.parseDocument(context, uri, currentDisplayName, format).getOrNull()
        }.getOrNull()

        val contentTitle = parsed?.let { extractTitleFromParsedDocument(it) }
        if (contentTitle != null) {
            return attachExtensionIfMissing(contentTitle, currentDisplayName, format)
        }

        return currentDisplayName
    }

    private fun extractPdfTitleOrFirstLine(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                PDDocument.load(stream).use { pdDoc ->
                    // Tier 1: PDF metadata title
                    cleanCandidateTitle(pdDoc.documentInformation?.title)?.let { return@use it }

                    // Tier 2: Page 1 first meaningful text line
                    if (pdDoc.numberOfPages <= 0) return@use null
                    val stripper = PDFTextStripper().apply {
                        startPage = 1
                        endPage = 1
                    }
                    val page1Text = stripper.getText(pdDoc).orEmpty()
                    for (line in page1Text.lines().take(25)) {
                        val cleaned = cleanCandidateTitle(line)
                        if (cleaned != null) return@use cleaned
                    }
                    null
                }
            }
        }.getOrNull()
    }

    private fun extractEmbeddedMetadataTitle(
        context: Context,
        uri: Uri,
        format: DocFormat
    ): String? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                when (format) {
                    DocFormat.DOCX -> {
                        val entries = SafeZipReader.readMatchingEntries(
                            inputStream = stream,
                            maxEntryBytes = 512 * 1024,
                            maxTotalBytes = 1024 * 1024
                        ) { norm -> norm == "docprops/core.xml" }
                        val coreBytes = entries["docprops/core.xml"] ?: return@use null
                        val xml = String(coreBytes, Charsets.UTF_8)
                        val raw = DC_TITLE_REGEX.find(xml)?.groupValues?.getOrNull(1)
                        cleanCandidateTitle(raw)
                    }
                    DocFormat.ODT -> {
                        val entries = SafeZipReader.readMatchingEntries(
                            inputStream = stream,
                            maxEntryBytes = 512 * 1024,
                            maxTotalBytes = 1024 * 1024
                        ) { norm -> norm == "meta.xml" }
                        val metaBytes = entries["meta.xml"] ?: return@use null
                        val xml = String(metaBytes, Charsets.UTF_8)
                        val raw = DC_TITLE_REGEX.find(xml)?.groupValues?.getOrNull(1)
                        cleanCandidateTitle(raw)
                    }
                    DocFormat.EPUB -> {
                        val entries = SafeZipReader.readMatchingEntries(
                            inputStream = stream,
                            maxEntryBytes = 512 * 1024,
                            maxTotalBytes = 1024 * 1024
                        ) { norm -> norm.endsWith(".opf") }
                        val opfBytes = entries.values.firstOrNull() ?: return@use null
                        val xml = String(opfBytes, Charsets.UTF_8)
                        val raw = DC_TITLE_REGEX.find(xml)?.groupValues?.getOrNull(1)
                        cleanCandidateTitle(raw)
                    }
                    DocFormat.DOC -> {
                        HWPFDocument(stream).use { doc ->
                            cleanCandidateTitle(doc.summaryInformation?.title)
                        }
                    }
                    DocFormat.HTML -> {
                        val headBytes = ByteArray(16 * 1024)
                        val read = stream.read(headBytes)
                        if (read <= 0) return@use null
                        val text = String(headBytes, 0, read, Charsets.UTF_8)
                        val raw = HTML_TITLE_REGEX.find(text)?.groupValues?.getOrNull(1)
                        cleanCandidateTitle(raw)
                    }
                    else -> null
                }
            }
        }.getOrNull()
    }

    fun attachExtensionIfMissing(title: String, originalName: String, format: DocFormat): String {
        val ext = originalName.substringAfterLast('.', "")
            .takeIf { originalName.contains('.') && it.length in 1..6 }
            ?: defaultExtensionFor(format)
        return if (title.endsWith(".$ext", ignoreCase = true)) title else "$title.$ext"
    }

    private fun defaultExtensionFor(format: DocFormat): String = when (format) {
        DocFormat.DOCX -> "docx"
        DocFormat.DOC -> "doc"
        DocFormat.PDF, DocFormat.PDF_EXTRACTED -> "pdf"
        DocFormat.ODT -> "odt"
        DocFormat.RTF -> "rtf"
        DocFormat.TXT -> "txt"
        DocFormat.HTML -> "html"
        DocFormat.EPUB -> "epub"
        DocFormat.UNSUPPORTED -> "docx"
    }
}
