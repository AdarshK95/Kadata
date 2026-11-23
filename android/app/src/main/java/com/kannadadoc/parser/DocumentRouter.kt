package com.kannadadoc.parser

import android.content.Context
import android.net.Uri
import java.io.InputStream

class DocumentRouter {

    companion object {
        private val MAGIC_ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        private val MAGIC_OLE2 = byteArrayOf(
            0xD0.toByte(),
            0xCF.toByte(),
            0x11,
            0xE0.toByte()
        )
        private val MAGIC_PDF = byteArrayOf(0x25, 0x50, 0x44, 0x46)
        private val MAGIC_RTF = byteArrayOf(0x7B, 0x5C, 0x72, 0x74, 0x66)
        private val MAGIC_PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        private val MAGIC_JPG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private const val SAMPLE_SIZE = 4096

        const val GOOGLE_DOCS_MIME = "application/vnd.google-apps.document"
        const val SUPPORTED_FORMATS_LABEL = "DOCX, DOC, PDF, ODT, RTF, TXT, HTML (incl. zipped), EPUB, Google Docs"
    }

    fun detectFormat(
        context: Context,
        uri: Uri,
        fileName: String? = null,
        mimeType: String? = null
    ): DocFormat {
        // Virtual Google Docs from Android's Drive provider are converted on ingest,
        // but guard here too if called directly.
        if (mimeType.equals(GOOGLE_DOCS_MIME, ignoreCase = true)) {
            return DocFormat.DOCX
        }

        // Local Google Drive .gdoc JSON shortcut files cannot be opened offline without Drive streaming
        if (fileName?.endsWith(".gdoc", ignoreCase = true) == true) {
            return DocFormat.UNSUPPORTED
        }

        val headerSample = context.contentResolver.openInputStream(uri)?.use { stream ->
            readHeaderBytes(stream, SAMPLE_SIZE)
        } ?: return DocFormat.UNSUPPORTED

        val magicMatch = detectFromBytes(headerSample, fileName, mimeType)

        if (magicMatch == DocFormat.DOCX) {
            // Inspect ZIP container entries to distinguish DOCX vs ODT vs EPUB vs Zipped HTML Web Page
            val zipDocFormat = context.contentResolver.openInputStream(uri)?.use { stream ->
                classifyZipContainer(stream)
            } ?: DocFormat.UNSUPPORTED
            return zipDocFormat
        }

        // Reject OLE2 PowerPoint (.ppt) or Excel (.xls) containers explicitly
        if (magicMatch == DocFormat.DOC) {
            val ext = fileName?.substringAfterLast('.', "")?.lowercase()
            if (ext == "ppt" || ext == "xls" || ext == "pps" || ext == "msg") {
                return DocFormat.UNSUPPORTED
            }
            if (mimeType != null && (
                mimeType.contains("powerpoint", ignoreCase = true) ||
                    mimeType.contains("excel", ignoreCase = true)
                )
            ) {
                return DocFormat.UNSUPPORTED
            }
        }

        return magicMatch
    }

    fun detectUnsupportedFormatLabel(
        context: Context,
        uri: Uri,
        fileName: String? = null,
        mimeType: String? = null
    ): String {
        if (fileName?.endsWith(".gdoc", ignoreCase = true) == true) {
            return "GDOC shortcut (open via Open File -> Google Drive instead)"
        }

        val ext = fileName?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() && it.length <= 8 && fileName.contains('.') }
            ?.uppercase()
        if (ext != null && ext !in setOf("BIN", "DAT", "TMP")) {
            return ext
        }

        val sample = context.contentResolver.openInputStream(uri)?.use { readHeaderBytes(it, 64) }
        if (sample != null) {
            if (startsWith(sample, MAGIC_ZIP, 0)) {
                val zipKind = context.contentResolver.openInputStream(uri)?.use { inspectUnsupportedZipKind(it) }
                if (zipKind != null) return zipKind
                return "ZIP"
            }
            if (startsWith(sample, MAGIC_PNG, 0)) return "PNG"
            if (startsWith(sample, MAGIC_JPG, 0)) return "JPG"
        }

        if (!mimeType.isNullOrBlank()) {
            return when {
                mimeType.contains("presentation", ignoreCase = true) -> "PPTX"
                mimeType.contains("powerpoint", ignoreCase = true) -> "PPT"
                mimeType.contains("spreadsheet", ignoreCase = true) -> "XLSX"
                mimeType.contains("excel", ignoreCase = true) -> "XLS"
                mimeType.startsWith("image/") -> mimeType.substringAfter('/').uppercase()
                mimeType.startsWith("video/") -> mimeType.substringAfter('/').uppercase()
                mimeType.startsWith("audio/") -> mimeType.substringAfter('/').uppercase()
                else -> mimeType.substringAfter('/').uppercase().take(12)
            }
        }
        return "UNKNOWN"
    }

    fun detectFromBytes(
        bytes: ByteArray,
        fileName: String? = null,
        mimeType: String? = null
    ): DocFormat {
        if (bytes.isEmpty()) {
            return when {
                isHtmlHint(fileName, mimeType) -> DocFormat.HTML
                isTextHint(fileName, mimeType) -> DocFormat.TXT
                else -> DocFormat.UNSUPPORTED
            }
        }

        val offset = if (
            bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) 3 else 0

        return when {
            startsWith(bytes, MAGIC_ZIP, 0) -> DocFormat.DOCX // Refined via classifyZipContainer
            startsWith(bytes, MAGIC_OLE2, 0) -> DocFormat.DOC
            startsWith(bytes, MAGIC_PDF, 0) -> DocFormat.PDF
            startsWith(bytes, MAGIC_RTF, offset) -> DocFormat.RTF
            looksLikeHtml(bytes, offset, fileName, mimeType) -> DocFormat.HTML
            looksLikePlainText(bytes, offset) -> DocFormat.TXT
            else -> DocFormat.UNSUPPORTED
        }
    }

    internal fun classifyZipContainer(inputStream: InputStream): DocFormat {
        var hasWordDoc = false
        var hasOdtMime = false
        var hasContentXml = false
        var hasEpubMime = false
        var hasContainerXml = false
        var hasHtmlFile = false
        var isPptOrXls = false

        try {
            SafeZipReader.inspectEntries(inputStream) { lower, isDirectory, readSample ->
                when {
                    lower == "word/document.xml" -> hasWordDoc = true
                    lower.startsWith("ppt/") || lower.startsWith("xl/") || lower == "androidmanifest.xml" -> {
                        isPptOrXls = true
                        return@inspectEntries false
                    }
                    lower == "mimetype" -> {
                        val mimeText = String(readSample(128), Charsets.US_ASCII).trim()
                        if (mimeText.contains("opendocument.text", ignoreCase = true)) {
                            hasOdtMime = true
                        } else if (mimeText.contains("epub", ignoreCase = true)) {
                            hasEpubMime = true
                        }
                    }
                    lower == "content.xml" -> hasContentXml = true
                    lower == "meta-inf/container.xml" -> hasContainerXml = true
                    !isDirectory && (lower.endsWith(".html") || lower.endsWith(".htm")) -> {
                        hasHtmlFile = true
                    }
                }
                true
            }
        } catch (_: Exception) {
            return DocFormat.UNSUPPORTED
        }

        return when {
            isPptOrXls -> DocFormat.UNSUPPORTED
            hasWordDoc -> DocFormat.DOCX
            hasOdtMime -> DocFormat.ODT
            hasEpubMime || hasContainerXml -> DocFormat.EPUB
            hasContentXml -> DocFormat.ODT
            hasHtmlFile -> DocFormat.HTML
            else -> DocFormat.UNSUPPORTED
        }
    }

    private fun inspectUnsupportedZipKind(inputStream: InputStream): String? {
        var found: String? = null
        return try {
            SafeZipReader.inspectEntries(inputStream) { lower, _, _ ->
                found = when {
                    lower.startsWith("ppt/") -> "PPTX"
                    lower.startsWith("xl/") -> "XLSX"
                    lower == "androidmanifest.xml" -> "APK"
                    else -> null
                }
                found == null
            }
            found
        } catch (_: Exception) {
            null
        }
    }

    internal fun detectFromStream(
        inputStream: InputStream,
        fileName: String? = null,
        mimeType: String? = null
    ): DocFormat {
        val buffered = if (inputStream is java.io.BufferedInputStream) {
            inputStream
        } else {
            java.io.BufferedInputStream(inputStream, SAMPLE_SIZE + 1024)
        }
        val sample = BoundedStreamReader.peekHeader(buffered, SAMPLE_SIZE)
        val initial = detectFromBytes(sample, fileName, mimeType)
        if (initial == DocFormat.DOCX) {
            return classifyZipContainer(buffered)
        }
        return initial
    }

    private fun looksLikeHtml(
        bytes: ByteArray,
        offset: Int,
        fileName: String?,
        mimeType: String?
    ): Boolean {
        if (isHtmlHint(fileName, mimeType)) return true
        val head = String(bytes, offset, (bytes.size - offset).coerceAtMost(512), Charsets.ISO_8859_1)
            .trimStart()
            .lowercase()
        return head.startsWith("<!doctype html") ||
            head.startsWith("<html") ||
            head.startsWith("<head") ||
            head.startsWith("<body")
    }

    private fun isHtmlHint(fileName: String?, mimeType: String?): Boolean {
        val lowerName = fileName?.lowercase() ?: ""
        return mimeType.equals("text/html", ignoreCase = true) ||
            mimeType.equals("application/xhtml+xml", ignoreCase = true) ||
            lowerName.endsWith(".html") ||
            lowerName.endsWith(".htm") ||
            lowerName.endsWith(".xhtml")
    }

    private fun startsWith(data: ByteArray, magic: ByteArray, offset: Int): Boolean {
        if (data.size - offset < magic.size) return false
        for (i in magic.indices) {
            if (data[offset + i] != magic[i]) return false
        }
        return true
    }

    private fun looksLikePlainText(bytes: ByteArray, offset: Int): Boolean {
        val length = bytes.size - offset
        if (length <= 0) return true

        var controlCount = 0
        for (i in offset until bytes.size) {
            val unsigned = bytes[i].toInt() and 0xFF
            if (unsigned == 0x00) return false
            val isAllowedControl = unsigned == 0x09 || unsigned == 0x0A || unsigned == 0x0D || unsigned == 0x0C
            if (unsigned < 0x20 && !isAllowedControl) {
                controlCount++
            }
        }
        return (controlCount.toFloat() / length) <= 0.05f
    }

    private fun isTextHint(fileName: String?, mimeType: String?): Boolean {
        return mimeType?.startsWith("text/") == true ||
            fileName?.endsWith(".txt", ignoreCase = true) == true
    }

    private fun readHeaderBytes(input: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(maxBytes)
        var totalRead = 0
        while (totalRead < maxBytes) {
            val read = input.read(buffer, totalRead, maxBytes - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return if (totalRead == maxBytes) buffer else buffer.copyOf(totalRead)
    }
}
