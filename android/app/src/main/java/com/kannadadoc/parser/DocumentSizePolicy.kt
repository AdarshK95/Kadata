package com.kannadadoc.parser

import java.util.Locale

/**
 * Centralized offline file-size safety limits for Kadata.
 * Kadata enforces a strict FAIL-FAST policy: files exceeding safe device-memory limits are
 * rejected cleanly with a clear bilingual explanation rather than silently truncated.
 */
object DocumentSizePolicy {
    const val MAX_HTML_BYTES = 10 * 1024 * 1024             // 10 MB raw HTML or HTML entry inside ZIP
    const val MAX_TEXT_OR_RTF_BYTES = 15 * 1024 * 1024      // 15 MB plain text or RTF
    const val MAX_ZIP_ENTRY_BYTES = 25 * 1024 * 1024        // 25 MB single decompressed XML/chapter entry
    const val MAX_ZIP_TOTAL_BYTES = 60 * 1024 * 1024        // 60 MB total decompressed archive text
    const val MAX_ZIP_ENTRIES = 2_000                       // Max ZIP entries inspected/loaded
    const val MAX_LEGACY_DOC_BYTES = 30 * 1024 * 1024       // 30 MB binary OLE2 .doc
    const val MAX_RAW_ZIP_ARCHIVE_BYTES = 80 * 1024 * 1024  // 80 MB raw .docx/.odt/.epub/zipped-html file
    const val MAX_PDF_BYTES = 100 * 1024 * 1024             // 100 MB PDF (streamed page-by-page)

    fun maxRawFileBytes(format: DocFormat): Long = when (format) {
        DocFormat.HTML -> MAX_HTML_BYTES.toLong()
        DocFormat.TXT, DocFormat.RTF -> MAX_TEXT_OR_RTF_BYTES.toLong()
        DocFormat.DOC -> MAX_LEGACY_DOC_BYTES.toLong()
        DocFormat.DOCX, DocFormat.ODT, DocFormat.EPUB -> MAX_RAW_ZIP_ARCHIVE_BYTES.toLong()
        DocFormat.PDF, DocFormat.PDF_EXTRACTED -> MAX_PDF_BYTES.toLong()
        DocFormat.UNSUPPORTED -> MAX_RAW_ZIP_ARCHIVE_BYTES.toLong()
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb == mb.toLong().toDouble()) {
            "${mb.toLong()} MB"
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }
}

/**
 * Thrown whenever a file, stream, or archive entry exceeds Kadata's safe offline limits.
 * Always carries explicit English and Kannada explanations of why the file was rejected.
 */
class DocumentSizeLimitException(
    val fileName: String,
    val formatLabel: String,
    val actualSizeBytes: Long?,
    val maxAllowedBytes: Long,
    val customReasonEn: String? = null,
    val customReasonKn: String? = null
) : IllegalArgumentException(
    customReasonEn ?: buildDefaultMessageEn(fileName, formatLabel, actualSizeBytes, maxAllowedBytes)
) {
    val titleEn: String = "File Too Large to Open"
    val titleKn: String = "ಫೈಲ್ ತುಂಬಾ ದೊಡ್ಡದಾಗಿದೆ"

    val userMessageEn: String
        get() = customReasonEn ?: buildDefaultMessageEn(fileName, formatLabel, actualSizeBytes, maxAllowedBytes)

    val userMessageKn: String
        get() = customReasonKn ?: buildDefaultMessageKn(fileName, formatLabel, actualSizeBytes, maxAllowedBytes)

    companion object {
        private fun buildDefaultMessageEn(
            fileName: String,
            formatLabel: String,
            actualSizeBytes: Long?,
            maxAllowedBytes: Long
        ): String {
            val limitStr = DocumentSizePolicy.formatBytes(maxAllowedBytes)
            val sizePart = actualSizeBytes?.takeIf { it > 0L }?.let {
                " (${DocumentSizePolicy.formatBytes(it)})"
            }.orEmpty()
            val namePart = fileName.takeIf { it.isNotBlank() }?.let { "\"$it\"$sizePart" } ?: "This $formatLabel file$sizePart"
            return "$namePart could not be opened because it exceeds Kadata's $limitStr safety limit for $formatLabel files.\n\n" +
                "Kadata rejects oversized files instead of cutting off your content so your document stays safe and the app does not run out of memory."
        }

        private fun buildDefaultMessageKn(
            fileName: String,
            formatLabel: String,
            actualSizeBytes: Long?,
            maxAllowedBytes: Long
        ): String {
            val limitStr = DocumentSizePolicy.formatBytes(maxAllowedBytes)
            val sizePart = actualSizeBytes?.takeIf { it > 0L }?.let {
                " (${DocumentSizePolicy.formatBytes(it)})"
            }.orEmpty()
            val namePart = fileName.takeIf { it.isNotBlank() }?.let { "\"$it\"$sizePart" } ?: "ಈ $formatLabel ಫೈಲ್$sizePart"
            return "$namePart ಫೈಲ್ ಅನ್ನು ತೆರೆಯಲಾಗಲಿಲ್ಲ, ಏಕೆಂದರೆ ಇದು $formatLabel ಫೈಲ್‌ಗಳ $limitStr ಸುರಕ್ಷಿತ ಮಿತಿಯನ್ನು ಮೀರಿದೆ.\n\n" +
                "ನಿಮ್ಮ ದಾಖಲೆಯ ಭಾಗಗಳು ಕಡಿತವಾಗದಂತೆ ಮತ್ತು ಆಪ್ ಕ್ರ್ಯಾಶ್ ಆಗದಂತೆ ತಡೆಯಲು ದೊಡ್ಡ ಫೈಲ್‌ಗಳನ್ನು ಕಡತ ತೆರೆಯುವುದಿಲ್ಲ."
        }
    }
}
