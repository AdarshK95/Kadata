package com.kannadadoc.parser

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

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

        const val SUPPORTED_FORMATS_LABEL = "DOC, DOCX, PDF, RTF, TXT"
    }

    fun detectFormat(
        context: Context,
        uri: Uri,
        fileName: String? = null,
        mimeType: String? = null
    ): DocFormat {
        val headerSample = context.contentResolver.openInputStream(uri)?.use { stream ->
            readHeaderBytes(stream, SAMPLE_SIZE)
        } ?: return DocFormat.UNSUPPORTED

        val magicMatch = detectFromBytes(headerSample, fileName, mimeType)

        if (magicMatch == DocFormat.DOCX) {
            val isRealDocx = context.contentResolver.openInputStream(uri)?.use { stream ->
                containsDocxEntry(stream)
            } ?: false
            return if (isRealDocx) DocFormat.DOCX else DocFormat.UNSUPPORTED
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

    /**
     * Identifies a human-friendly label for an unsupported file (e.g., "PPT", "PPTX", "XLSX", "PNG")
     * so we can show: "This PPT format is not supported. Supported formats: DOC, DOCX, PDF, RTF, TXT".
     */
    fun detectUnsupportedFormatLabel(
        context: Context,
        uri: Uri,
        fileName: String? = null,
        mimeType: String? = null
    ): String {
        val ext = fileName?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() && it.length <= 8 && fileName.contains('.') }
            ?.uppercase()
        if (ext != null && ext !in setOf("BIN", "DAT", "TMP")) {
            return ext
        }

        val sample = context.contentResolver.openInputStream(uri)?.use { readHeaderBytes(it, 64) }
        if (sample != null) {
            if (startsWith(sample, MAGIC_ZIP, 0)) {
                val zipKind = context.contentResolver.openInputStream(uri)?.use { inspectZipKind(it) }
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
            return if (isTextHint(fileName, mimeType)) DocFormat.TXT else DocFormat.UNSUPPORTED
        }

        val offset = if (
            bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) 3 else 0

        return when {
            startsWith(bytes, MAGIC_ZIP, 0) -> DocFormat.DOCX
            startsWith(bytes, MAGIC_OLE2, 0) -> DocFormat.DOC
            startsWith(bytes, MAGIC_PDF, 0) -> DocFormat.PDF
            startsWith(bytes, MAGIC_RTF, offset) -> DocFormat.RTF
            looksLikePlainText(bytes, offset) -> DocFormat.TXT
            else -> DocFormat.UNSUPPORTED
        }
    }

    internal fun containsDocxEntry(inputStream: InputStream): Boolean {
        return try {
            ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.equals("word/document.xml", ignoreCase = true)) {
                        return true
                    }
                    entry = zis.nextEntry
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun inspectZipKind(inputStream: InputStream): String? {
        return try {
            ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val lower = entry.name.lowercase()
                    when {
                        lower.startsWith("ppt/") -> return "PPTX"
                        lower.startsWith("xl/") -> return "XLSX"
                        lower == "androidmanifest.xml" -> return "APK"
                    }
                    entry = zis.nextEntry
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    internal fun detectFromStream(
        inputStream: InputStream,
        fileName: String? = null,
        mimeType: String? = null
    ): DocFormat {
        val allBytes = inputStream.readBytes()
        val sample = if (allBytes.size > SAMPLE_SIZE) allBytes.copyOf(SAMPLE_SIZE) else allBytes
        val initial = detectFromBytes(sample, fileName, mimeType)
        if (initial == DocFormat.DOCX) {
            return if (containsDocxEntry(ByteArrayInputStream(allBytes))) {
                DocFormat.DOCX
            } else {
                DocFormat.UNSUPPORTED
            }
        }
        return initial
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
