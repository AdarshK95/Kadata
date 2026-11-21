package com.kannadadoc.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.kannadadoc.data.local.RecentFileDao
import com.kannadadoc.data.local.RecentFileEntity
import com.kannadadoc.parser.DocFormat
import com.kannadadoc.parser.DocumentRouter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.hwpf.HWPFDocument
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class UnsupportedFormatException(val formatName: String) :
    IllegalArgumentException("This $formatName format is not supported. Supported formats: ${DocumentRouter.SUPPORTED_FORMATS_LABEL}")

data class IngestedDocument(
    val entity: RecentFileEntity,
    val resolvedUri: Uri
)

class SafStorageManager(
    private val context: Context,
    private val recentFileDao: RecentFileDao,
    private val documentRouter: DocumentRouter
) {
    companion object {
        // Allow selecting any file so .doc/.rtf aren't hidden by OEM file pickers,
        // and unsupported files (e.g. .ppt, .xlsx) immediately display a helpful message.
        val SUPPORTED_SAF_MIME_TYPES = arrayOf("*/*")

        // Matches WhatsApp's sanitized media filenames like DOC-20261121-WA0003.docx
        private val WHATSAPP_GENERIC_NAME_REGEX = Regex(
            "^(DOC|AUD|IMG|VID|PTT)-\\d{8}-WA\\d+(\\.[A-Za-z0-9]+)?$",
            RegexOption.IGNORE_CASE
        )
        private val PURE_NUMERIC_NAME_REGEX = Regex("^\\d+(\\.[A-Za-z0-9]+)?$")
        private val INTERNAL_HASH_PREFIX_REGEX = Regex("^[0-9a-f]{12}_", RegexOption.IGNORE_CASE)
        private val DC_TITLE_REGEX = Regex("<dc:title[^>]*>([^<]+)</dc:title>", RegexOption.IGNORE_CASE)
    }

    suspend fun ingestDocument(
        sourceUri: Uri,
        intentFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION,
        titleHint: String? = null
    ): Result<IngestedDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val contentResolver = context.contentResolver
            val meta = queryFileMetadata(sourceUri, titleHint)
            val rawMimeType = contentResolver.getType(sourceUri)

            val persisted = tryPersistPermission(sourceUri, intentFlags)

            val (finalUri, isLocallyImported, finalSize) = if (persisted) {
                Triple(sourceUri, false, meta.sizeBytes)
            } else {
                val importedFile = copyToImportedStorage(sourceUri, meta.displayName)
                val providerUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    importedFile
                )
                Triple(providerUri, true, importedFile.length())
            }

            val detectedFormat = documentRouter.detectFormat(
                context = context,
                uri = finalUri,
                fileName = meta.displayName,
                mimeType = rawMimeType
            )

            if (detectedFormat == DocFormat.UNSUPPORTED) {
                val badLabel = documentRouter.detectUnsupportedFormatLabel(
                    context = context,
                    uri = finalUri,
                    fileName = meta.displayName,
                    mimeType = rawMimeType
                )
                if (isLocallyImported) {
                    runCatching {
                        val importedDir = File(context.filesDir, "imported")
                        finalUri.lastPathSegment?.let { File(importedDir, it).delete() }
                    }
                }
                throw UnsupportedFormatException(badLabel)
            }

            // If WhatsApp gave only DOC-YYYYMMDD-WAxxxx, also check embedded document title metadata
            var resolvedDisplayName = meta.displayName
            if (isGenericWhatsAppName(resolvedDisplayName)) {
                val embeddedTitle = extractEmbeddedTitle(finalUri, detectedFormat)
                if (!embeddedTitle.isNullOrBlank()) {
                    resolvedDisplayName = attachExtensionIfMissing(embeddedTitle, resolvedDisplayName, detectedFormat)
                }
            }

            val existing = recentFileDao.getByUri(finalUri.toString())
            // Preserve custom user renames if this file already exists in Recent Files
            val cleanExisting = existing?.displayName
                ?.replace(INTERNAL_HASH_PREFIX_REGEX, "")
                ?.takeIf { it.isNotBlank() }
            val finalDisplayName = cleanExisting ?: resolvedDisplayName.replace(INTERNAL_HASH_PREFIX_REGEX, "")

            val entity = RecentFileEntity(
                id = existing?.id ?: 0,
                uriString = finalUri.toString(),
                displayName = finalDisplayName,
                detectedFormat = detectedFormat,
                mimeType = rawMimeType,
                fileSizeBytes = finalSize,
                isLocallyImported = isLocallyImported,
                converterOverride = existing?.converterOverride,
                savedUnicodeUri = existing?.savedUnicodeUri,
                lastOpenedAt = System.currentTimeMillis()
            )

            recentFileDao.upsert(entity)
            IngestedDocument(entity = entity, resolvedUri = finalUri)
        }
    }

    suspend fun registerNewBlankDocument(title: String): IngestedDocument = withContext(Dispatchers.IO) {
        val docsDir = File(context.filesDir, "documents").apply { mkdirs() }
        val safeTitle = sanitizeFileName(title).ifBlank { "Untitled" }
        val file = File(docsDir, "${safeTitle}_${System.currentTimeMillis()}.docx")
        val providerUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val entity = RecentFileEntity(
            uriString = providerUri.toString(),
            displayName = "$safeTitle.docx",
            detectedFormat = DocFormat.DOCX,
            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            fileSizeBytes = 0L,
            isLocallyImported = true,
            savedUnicodeUri = providerUri.toString(),
            lastOpenedAt = System.currentTimeMillis()
        )
        recentFileDao.upsert(entity)
        IngestedDocument(entity = entity, resolvedUri = providerUri)
    }

    fun resolveSavedDocxFile(originalFileName: String, suffix: String = "Unicode"): File {
        val docsDir = File(context.filesDir, "documents").apply { mkdirs() }
        val base = originalFileName.substringBeforeLast('.').ifBlank { "Document" }
        val cleanBase = sanitizeFileName(base)
        return File(docsDir, "${cleanBase}_${suffix}.docx")
    }

    fun resolveExportCacheFile(baseFileName: String, suffix: String, extension: String): File {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val base = baseFileName.substringBeforeLast('.').ifBlank { "Document" }
        val cleanBase = sanitizeFileName(base)
        val fullName = if (suffix.isBlank()) "$cleanBase.$extension" else "${cleanBase}_${suffix}.$extension"
        return File(exportDir, fullName)
    }

    /**
     * Copies any readable URI (SAF or internal FileProvider) to cacheDir/exports/<displayName>
     * so "Share" (as-is) works reliably across WhatsApp/Gmail/Bluetooth with the real filename.
     */
    suspend fun copyUriToExportCache(sourceUri: Uri, targetDisplayName: String): File = withContext(Dispatchers.IO) {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = sanitizeFileName(targetDisplayName).ifBlank { "Document.docx" }
        val outFile = File(exportDir, safeName)
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output, bufferSize = 16 * 1024)
            }
        } ?: error("Unable to read existing file for sharing")
        outFile
    }

    fun uriForFile(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun openInputStream(uri: Uri): InputStream? =
        context.contentResolver.openInputStream(uri)

    private fun tryPersistPermission(uri: Uri, intentFlags: Int): Boolean {
        if (uri.scheme != "content") return false
        if (uri.authority == "${context.packageName}.fileprovider") return true

        val persistableFlags = intentFlags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val flagsToRequest = if (persistableFlags != 0) {
            persistableFlags
        } else {
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        return try {
            context.contentResolver.takePersistableUriPermission(uri, flagsToRequest)
            true
        } catch (_: SecurityException) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                true
            } catch (_: SecurityException) {
                false
            }
        }
    }

    private fun copyToImportedStorage(sourceUri: Uri, displayName: String): File {
        val importedDir = File(context.filesDir, "imported").apply { mkdirs() }
        val safeName = sanitizeFileName(displayName)
        val shortHash = sha256Prefix(sourceUri.toString())
        val targetFile = File(importedDir, "${shortHash}_$safeName")

        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output, bufferSize = 16 * 1024)
            }
        } ?: error("Unable to open input stream for URI: $sourceUri")

        return targetFile
    }

    /**
     * Multi-source filename resolution designed to recover original names from WhatsApp & Gmail:
     * 1. Inspects ALL ContentProvider cursor columns (_display_name, title, original_name,
     *    file_name, media_name, name, _data) rather than only OpenableColumns.DISPLAY_NAME.
     * 2. Inspects URI query parameters (filename, name, title, displayName).
     * 3. Uses Intent title/subject/clipData label hints when the provider returns DOC-YYYYMMDD-WAxxxx.
     */
    private fun queryFileMetadata(uri: Uri, titleHint: String?): FileMetadata {
        val fallbackSegment = Uri.decode(uri.lastPathSegment ?: "")
            .substringAfterLast('/')
            .substringAfterLast(':')
            .ifBlank { "Untitled" }

        var displayName = fallbackSegment
        var alternateName: String? = null
        var size = -1L

        // Check URI query parameters (some providers pass ?name=... or ?filename=...)
        for (param in listOf("filename", "name", "displayName", "title")) {
            val qVal = uri.getQueryParameter(param)?.trim()
            if (!qVal.isNullOrEmpty() && !isGenericWhatsAppName(qVal)) {
                alternateName = qVal
                break
            }
        }

        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            size = cursor.getLong(sizeIdx)
                        }

                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                            val dn = cursor.getString(nameIdx)?.trim()
                            if (!dn.isNullOrEmpty()) displayName = Uri.decode(dn)
                        }

                        // Scan extra provider columns for non-sanitized original names
                        val preferredCols = listOf(
                            "original_name", "file_name", "filename", "media_name",
                            "title", "document_name", "name", "_data"
                        )
                        for (colName in preferredCols) {
                            val idx = cursor.getColumnIndex(colName)
                            if (idx >= 0 && !cursor.isNull(idx)) {
                                val raw = runCatching { cursor.getString(idx) }.getOrNull()?.trim()
                                val candidate = raw?.substringAfterLast('/')?.trim()
                                if (!candidate.isNullOrEmpty() &&
                                    candidate.length <= 160 &&
                                    !isGenericWhatsAppName(candidate)
                                ) {
                                    alternateName = Uri.decode(candidate)
                                    break
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Fallback to minimal OpenableColumns projection if provider rejects null projection
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                            displayName = Uri.decode(cursor.getString(nameIdx))
                        }
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            size = cursor.getLong(sizeIdx)
                        }
                    }
                }
            }
        }

        // If provider returned WhatsApp's DOC-YYYYMMDD-WAxxxx, prefer Intent hint or alternate column
        val bestName = if (isGenericWhatsAppName(displayName)) {
            val cleanHint = titleHint?.trim()?.substringAfterLast('/')?.takeIf {
                it.isNotBlank() && it.length <= 160 && !isGenericWhatsAppName(it)
            }
            val chosen = cleanHint ?: alternateName
            if (chosen != null) {
                val origExt = displayName.substringAfterLast('.', "")
                if (origExt.isNotEmpty() && !chosen.contains('.')) "$chosen.$origExt" else chosen
            } else {
                displayName
            }
        } else {
            displayName
        }

        val cleanedName = if (uri.authority == "${context.packageName}.fileprovider") {
            bestName.replace(INTERNAL_HASH_PREFIX_REGEX, "")
        } else {
            bestName
        }
        return FileMetadata(displayName = cleanedName, sizeBytes = size)
    }

    private fun extractEmbeddedTitle(uri: Uri, format: DocFormat): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                when (format) {
                    DocFormat.DOCX -> {
                        ZipInputStream(stream).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                if (entry.name.equals("docProps/core.xml", ignoreCase = true)) {
                                    val xml = String(zis.readBytes(), Charsets.UTF_8)
                                    val title = DC_TITLE_REGEX.find(xml)?.groupValues?.getOrNull(1)?.trim()
                                    if (!title.isNullOrBlank() && title.length <= 120 && !isGenericWhatsAppName(title)) {
                                        return title
                                    }
                                }
                                entry = zis.nextEntry
                            }
                        }
                        null
                    }
                    DocFormat.PDF -> {
                        PDDocument.load(stream).use { pdDoc ->
                            val title = pdDoc.documentInformation?.title?.trim()
                            title?.takeIf { it.isNotBlank() && it.length <= 120 && !isGenericWhatsAppName(it) }
                        }
                    }
                    DocFormat.DOC -> {
                        HWPFDocument(stream).use { doc ->
                            val title = doc.summaryInformation?.title?.trim()
                            title?.takeIf { it.isNotBlank() && it.length <= 120 && !isGenericWhatsAppName(it) }
                        }
                    }
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun attachExtensionIfMissing(title: String, originalName: String, format: DocFormat): String {
        if (title.contains('.')) return title
        val ext = originalName.substringAfterLast('.', "").ifBlank {
            when (format) {
                DocFormat.DOCX -> "docx"
                DocFormat.DOC -> "doc"
                DocFormat.PDF -> "pdf"
                DocFormat.RTF -> "rtf"
                DocFormat.TXT -> "txt"
                else -> "docx"
            }
        }
        return "$title.$ext"
    }

    private fun isGenericWhatsAppName(name: String): Boolean {
        val clean = name.trim()
        return clean.isEmpty() ||
            clean.equals("Untitled", ignoreCase = true) ||
            WHATSAPP_GENERIC_NAME_REGEX.matches(clean) ||
            PURE_NUMERIC_NAME_REGEX.matches(clean)
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9._\\-\\u0C80-\\u0CFF ]"), "_").trim()

    private fun sha256Prefix(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    private data class FileMetadata(
        val displayName: String,
        val sizeBytes: Long
    )
}
