package com.kannadadoc.data.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.kannadadoc.data.local.RecentFileDao
import com.kannadadoc.data.local.RecentFileEntity
import com.kannadadoc.export.DocxWriter
import com.kannadadoc.parser.DocFormat
import com.kannadadoc.parser.DocumentParserFacade
import com.kannadadoc.parser.DocumentRouter
import com.kannadadoc.parser.DocumentSizeLimitException
import com.kannadadoc.parser.DocumentSizePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

class UnsupportedFormatException(val formatName: String) :
    IllegalArgumentException("This $formatName format is not supported. Supported formats: ${DocumentRouter.SUPPORTED_FORMATS_LABEL}")

data class UriAccessInfo(
    val isWritable: Boolean,
    val sourceAppLabel: String?,
    val requiresSaveBeforeEdit: Boolean
)

data class IngestedDocument(
    val entity: RecentFileEntity,
    val resolvedUri: Uri,
    val accessInfo: UriAccessInfo = UriAccessInfo(isWritable = true, sourceAppLabel = null, requiresSaveBeforeEdit = false),
    val alreadyExisted: Boolean = false
)

data class PhysicalRenameResult(
    val uriString: String,
    val displayName: String,
    val modifiedAt: Long
)

class SafStorageManager(
    private val context: Context,
    private val recentFileDao: RecentFileDao,
    private val documentRouter: DocumentRouter,
    private val parserFacade: DocumentParserFacade = DocumentParserFacade()
) {
    companion object {
        val SUPPORTED_SAF_MIME_TYPES = arrayOf("*/*")
        const val KADATA_PUBLIC_FOLDER = "Kadata Documents"
        const val KADATA_RELATIVE_PATH = "Documents/Kadata Documents/"
        private val INTERNAL_HASH_PREFIX_REGEX = Regex("^[0-9a-f]{12}_", RegexOption.IGNORE_CASE)
        private val TRAILING_ENCODING_SUFFIX_REGEX = Regex("(?:_(?:Nudi|Baraha|Unicode|Universal))+$", RegexOption.IGNORE_CASE)
    }

    fun stripEncodingSuffix(baseName: String): String =
        baseName.replace(TRAILING_ENCODING_SUFFIX_REGEX, "").ifBlank { "Document" }

    fun inspectUriAccess(uri: Uri, intentFlags: Int = 0): UriAccessInfo {
        val authority = uri.authority.orEmpty()
        val lowerAuth = authority.lowercase()

        if (authority == "${context.packageName}.fileprovider") {
            val isImported = uri.path?.contains("imported", ignoreCase = true) == true
            return if (isImported) {
                UriAccessInfo(isWritable = false, sourceAppLabel = "External App", requiresSaveBeforeEdit = true)
            } else {
                UriAccessInfo(isWritable = true, sourceAppLabel = null, requiresSaveBeforeEdit = false)
            }
        }

        if (authority == MediaStore.AUTHORITY) {
            return UriAccessInfo(isWritable = true, sourceAppLabel = null, requiresSaveBeforeEdit = false)
        }

        val knownServiceLabel = detectExternalServiceLabel(authority)
        if (knownServiceLabel != null) {
            return UriAccessInfo(isWritable = false, sourceAppLabel = knownServiceLabel, requiresSaveBeforeEdit = true)
        }

        val isLocalStorageProvider = lowerAuth == "com.android.externalstorage.documents" ||
            lowerAuth == "com.android.providers.downloads.documents" ||
            lowerAuth.contains("myfiles") ||
            uri.scheme == "file"

        if (isLocalStorageProvider) {
            val supportsWrite = checkDocumentSupportsWrite(uri, intentFlags)
            return if (supportsWrite) {
                UriAccessInfo(isWritable = true, sourceAppLabel = null, requiresSaveBeforeEdit = false)
            } else {
                UriAccessInfo(isWritable = false, sourceAppLabel = "Read-Only Storage", requiresSaveBeforeEdit = true)
            }
        }

        val dynamicAppName = resolveProviderAppLabel(authority) ?: "External App"
        return UriAccessInfo(isWritable = false, sourceAppLabel = dynamicAppName, requiresSaveBeforeEdit = true)
    }

    private fun detectExternalServiceLabel(authority: String): String? =
        ImportSourcePolicy.detectKnownServiceLabel(authority)

    private fun resolveProviderAppLabel(authority: String): String? {
        if (authority.isBlank()) return null
        return runCatching {
            val pm = context.packageManager
            val providerInfo = pm.resolveContentProvider(authority, 0) ?: return@runCatching null
            val appInfo = providerInfo.applicationInfo ?: return@runCatching null
            pm.getApplicationLabel(appInfo)?.toString()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun checkDocumentSupportsWrite(uri: Uri, intentFlags: Int): Boolean {
        if (uri.scheme == "file") {
            val f = uri.path?.let { File(it) }
            return f != null && f.canWrite()
        }
        val hasPersistedWrite = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        val hasIntentWrite = (intentFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
        if (!hasPersistedWrite && !hasIntentWrite) {
            val took = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                true
            }.getOrDefault(false)
            if (!took) return false
        }

        if (DocumentsContract.isDocumentUri(context, uri)) {
            val flagSupports = runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) {
                        (cursor.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
                    } else true
                } ?: true
            }.getOrDefault(true)
            if (!flagSupports) return false
        }
        return true
    }

    suspend fun migrateLegacySavedUnicodeRows() = withContext(Dispatchers.IO) {
        runCatching {
            for (row in recentFileDao.getAllSnapshot()) {
                val savedStr = row.savedUnicodeUri ?: continue
                if (savedStr.isBlank() || savedStr == row.uriString) {
                    recentFileDao.upsert(row.copy(savedUnicodeUri = null, isEdited = true))
                    continue
                }
                val savedUri = runCatching { Uri.parse(savedStr) }.getOrNull() ?: continue
                val savedSize = queryActualFileSize(savedUri)
                if (savedSize <= 0L) {
                    recentFileDao.upsert(row.copy(savedUnicodeUri = null))
                    continue
                }
                if (recentFileDao.getByUri(savedStr) != null) {
                    recentFileDao.upsert(row.copy(savedUnicodeUri = null))
                    continue
                }
                val savedFileName = queryFileMetadata(savedUri, null).displayName
                val promotedFormat = if (savedFileName.endsWith(".pdf", ignoreCase = true)) DocFormat.PDF else DocFormat.DOCX
                val savedLastMod = queryFileLastModified(savedUri).takeIf { it > 0L } ?: row.lastModifiedAt

                if (row.detectedFormat == DocFormat.PDF && promotedFormat == DocFormat.DOCX) {
                    recentFileDao.upsert(row.copy(savedUnicodeUri = null))
                    recentFileDao.upsert(
                        RecentFileEntity(
                            uriString = savedStr,
                            displayName = savedFileName,
                            detectedFormat = DocFormat.DOCX,
                            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            fileSizeBytes = savedSize,
                            isLocallyImported = true,
                            converterOverride = row.converterOverride,
                            savedUnicodeUri = null,
                            kannadaFormat = row.kannadaFormat,
                            lastOpenedAt = row.lastOpenedAt,
                            lastModifiedAt = savedLastMod,
                            isEdited = true,
                            sourceAppLabel = null
                        )
                    )
                } else {
                    val cleanName = if (row.displayName.endsWith(".doc", ignoreCase = true) && promotedFormat == DocFormat.DOCX) {
                        row.displayName.substringBeforeLast('.') + ".docx"
                    } else row.displayName
                    recentFileDao.deleteByUri(row.uriString)
                    recentFileDao.upsert(
                        row.copy(
                            id = 0,
                            uriString = savedStr,
                            displayName = cleanName,
                            detectedFormat = promotedFormat,
                            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            fileSizeBytes = savedSize,
                            isLocallyImported = true,
                            savedUnicodeUri = null,
                            lastModifiedAt = savedLastMod,
                            isEdited = true,
                            sourceAppLabel = null
                        )
                    )
                }
            }
        }
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
            val rawAccessInfo = inspectUriAccess(sourceUri, intentFlags)

            val isVirtualGoogleDoc = rawMimeType.equals(DocumentRouter.GOOGLE_DOCS_MIME, ignoreCase = true)
            val effectiveDisplayName = if (isVirtualGoogleDoc && !meta.displayName.endsWith(".docx", ignoreCase = true)) {
                "${meta.displayName}.docx"
            } else meta.displayName

            if (!isVirtualGoogleDoc) {
                val preSniffFormat = runCatching {
                    documentRouter.detectFormat(context, sourceUri, effectiveDisplayName, rawMimeType)
                }.getOrNull()

                if (preSniffFormat == DocFormat.UNSUPPORTED) {
                    val badLabel = documentRouter.detectUnsupportedFormatLabel(context, sourceUri, meta.displayName, rawMimeType)
                    throw UnsupportedFormatException(badLabel)
                }

                if (preSniffFormat != null) {
                    val preKnownSize = if (meta.sizeBytes > 0L) meta.sizeBytes else {
                        runCatching { contentResolver.openAssetFileDescriptor(sourceUri, "r")?.use { it.length } ?: -1L }.getOrDefault(-1L)
                    }
                    val maxAllowed = DocumentSizePolicy.maxRawFileBytes(preSniffFormat)
                    if (preKnownSize > maxAllowed) {
                        throw DocumentSizeLimitException(effectiveDisplayName, preSniffFormat.name, preKnownSize, maxAllowed)
                    }
                }
            }

            val persisted = if (isVirtualGoogleDoc) false else tryPersistPermission(sourceUri, intentFlags)
            var newlyCreatedLocalFile: File? = null
            var matchedExistingRow: RecentFileEntity? = null

            val (finalUri, isLocallyImported, finalSize) = if (persisted) {
                matchedExistingRow = recentFileDao.getByUri(sourceUri.toString())
                Triple(
                    sourceUri,
                    false,
                    if (meta.sizeBytes >= 0L) meta.sizeBytes else {
                        runCatching { contentResolver.openAssetFileDescriptor(sourceUri, "r")?.use { it.length } ?: -1L }.getOrDefault(-1L)
                    }
                )
            } else {
                val staged = if (isVirtualGoogleDoc) {
                    copyVirtualGoogleDocToStorageDeduped(sourceUri, effectiveDisplayName)
                } else {
                    copyToImportedStorageDeduped(sourceUri, effectiveDisplayName)
                }
                if (staged.existingRow != null) {
                    matchedExistingRow = staged.existingRow
                    Triple(Uri.parse(staged.existingRow.uriString), true, staged.sizeBytes)
                } else {
                    if (staged.newlyCreatedFile) {
                        newlyCreatedLocalFile = staged.file
                    }
                    val providerUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", staged.file)
                    matchedExistingRow = recentFileDao.getByUri(providerUri.toString())
                    Triple(providerUri, true, staged.sizeBytes)
                }
            }

            val alreadyExisted = matchedExistingRow != null
            val existing = matchedExistingRow

            val detectedFormat = documentRouter.detectFormat(
                context = context,
                uri = finalUri,
                fileName = effectiveDisplayName,
                mimeType = if (isVirtualGoogleDoc) "application/vnd.openxmlformats-officedocument.wordprocessingml.document" else rawMimeType
            )

            if (detectedFormat == DocFormat.UNSUPPORTED) {
                val badLabel = documentRouter.detectUnsupportedFormatLabel(context, finalUri, meta.displayName, rawMimeType)
                newlyCreatedLocalFile?.let { runCatching { it.delete() } }
                throw UnsupportedFormatException(badLabel)
            }

            val maxAllowedForDetected = DocumentSizePolicy.maxRawFileBytes(detectedFormat)
            if (finalSize > maxAllowedForDetected) {
                newlyCreatedLocalFile?.let { runCatching { it.delete() } }
                throw DocumentSizeLimitException(effectiveDisplayName, detectedFormat.name, finalSize, maxAllowedForDetected)
            }

            val preserveIncomingName = ImportSourcePolicy.shouldPreserveIncomingFileName(
                sourceAppLabel = rawAccessInfo.sourceAppLabel ?: existing?.sourceAppLabel,
                displayName = effectiveDisplayName
            )

            val resolvedDisplayName = if (!preserveIncomingName && DocumentTitleHealer.isGenericDisplayName(effectiveDisplayName)) {
                DocumentTitleHealer.healDisplayName(context, finalUri, effectiveDisplayName, detectedFormat, parserFacade)
            } else {
                effectiveDisplayName
            }

            val cleanExisting = existing?.displayName
                ?.replace(INTERNAL_HASH_PREFIX_REGEX, "")
                ?.takeIf {
                    it.isNotBlank() && (preserveIncomingName || !DocumentTitleHealer.isGenericDisplayName(it))
                }
            val finalDisplayName = cleanExisting ?: resolvedDisplayName.replace(INTERNAL_HASH_PREFIX_REGEX, "")

            val now = System.currentTimeMillis()
            val fileDiskModified = queryFileLastModified(finalUri)
            val preservedModifiedAt = when {
                existing != null && fileDiskModified > existing.lastModifiedAt + 2_000L -> fileDiskModified
                existing != null -> existing.lastModifiedAt
                fileDiskModified > 0L -> fileDiskModified
                else -> now
            }

            val effectiveAccess = if (isLocallyImported && rawAccessInfo.requiresSaveBeforeEdit) {
                rawAccessInfo
            } else if (existing?.sourceAppLabel != null && isLocallyImported) {
                UriAccessInfo(isWritable = false, sourceAppLabel = existing.sourceAppLabel, requiresSaveBeforeEdit = true)
            } else {
                inspectUriAccess(finalUri, intentFlags)
            }

            val entity = RecentFileEntity(
                id = existing?.id ?: 0,
                uriString = finalUri.toString(),
                displayName = finalDisplayName,
                detectedFormat = detectedFormat,
                mimeType = rawMimeType ?: existing?.mimeType,
                fileSizeBytes = finalSize,
                isLocallyImported = isLocallyImported,
                converterOverride = existing?.converterOverride,
                savedUnicodeUri = null,
                kannadaFormat = existing?.kannadaFormat,
                lastOpenedAt = now,
                lastModifiedAt = preservedModifiedAt,
                isEdited = existing?.isEdited ?: false,
                sourceAppLabel = existing?.sourceAppLabel ?: effectiveAccess.sourceAppLabel
            )

            recentFileDao.upsert(entity)
            IngestedDocument(
                entity = entity,
                resolvedUri = finalUri,
                accessInfo = effectiveAccess,
                alreadyExisted = alreadyExisted
            )
        }
    }

    suspend fun openExistingRecentCard(entity: RecentFileEntity): Result<IngestedDocument> =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(entity.uriString)
                val readable = runCatching { context.contentResolver.openInputStream(uri)?.use { true } ?: false }.getOrDefault(false)
                if (!readable) error("This file could not be opened. It may have been moved or deleted from storage.")
                val now = System.currentTimeMillis()
                recentFileDao.updateLastOpenedAt(entity.uriString, now)

                val access = if (entity.sourceAppLabel != null &&
                    uri.authority == "${context.packageName}.fileprovider" &&
                    uri.path?.contains("imported", ignoreCase = true) == true
                ) {
                    UriAccessInfo(isWritable = false, sourceAppLabel = entity.sourceAppLabel, requiresSaveBeforeEdit = true)
                } else {
                    inspectUriAccess(uri)
                }
                IngestedDocument(entity = entity.copy(lastOpenedAt = now), resolvedUri = uri, accessInfo = access)
            }
        }

    suspend fun registerNewBlankDocument(
        title: String,
        defaultFont: String = "Noto Sans Kannada",
        formatTag: String = "Universal",
        docxWriter: DocxWriter = DocxWriter()
    ): IngestedDocument = withContext(Dispatchers.IO + NonCancellable) {
        val safeTitle = sanitizeFileName(title.substringBeforeLast('.')).ifBlank { "Untitled" }
        val uniqueName = resolveUniqueKadataFileName("$safeTitle.docx")
        val blankDoc = parserFacade.createBlankDocument(uriString = "", fileName = uniqueName)
        saveNewFileInKadataDocuments(
            desiredFileName = uniqueName,
            format = DocFormat.DOCX,
            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            kannadaFormat = formatTag,
            isEdited = false
        ) { out -> docxWriter.write(blankDoc, out, defaultFont = defaultFont) }
    }

    suspend fun saveNewFileInKadataDocuments(
        desiredFileName: String,
        format: DocFormat,
        mimeType: String,
        kannadaFormat: String?,
        isEdited: Boolean = true,
        writer: suspend (OutputStream) -> Unit
    ): IngestedDocument = withContext(Dispatchers.IO + NonCancellable) {
        val uniqueName = resolveUniqueKadataFileName(desiredFileName)
        val stagedFile = stageToTempFile(writer)
        try {
            val createdUri: Uri
            val isInternalFallback: Boolean
            val writtenSize = stagedFile.length()

            val mediaUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeStagedFileToPublicMediaStore(stagedFile, uniqueName, mimeType)
            } else null

            if (mediaUri != null) {
                createdUri = mediaUri
                isInternalFallback = false
            } else {
                val docsDir = File(context.filesDir, "documents").apply { mkdirs() }
                val targetFile = resolveUniqueInternalFile(docsDir, uniqueName)
                moveOrCopyAtomic(stagedFile, targetFile)
                createdUri = uriForFile(targetFile)
                isInternalFallback = true
            }

            val now = System.currentTimeMillis()
            val entity = RecentFileEntity(
                uriString = createdUri.toString(),
                displayName = uniqueName,
                detectedFormat = format,
                mimeType = mimeType,
                fileSizeBytes = writtenSize,
                isLocallyImported = isInternalFallback,
                converterOverride = null,
                savedUnicodeUri = null,
                kannadaFormat = kannadaFormat,
                lastOpenedAt = now,
                lastModifiedAt = now,
                isEdited = isEdited,
                sourceAppLabel = null
            )
            recentFileDao.upsert(entity)
            IngestedDocument(
                entity = entity,
                resolvedUri = createdUri,
                accessInfo = UriAccessInfo(isWritable = true, sourceAppLabel = null, requiresSaveBeforeEdit = false)
            )
        } finally {
            runCatching { if (stagedFile.exists()) stagedFile.delete() }
        }
    }

    suspend fun writeStagedToUri(targetUri: Uri, writer: suspend (OutputStream) -> Unit): Long =
        withContext(Dispatchers.IO + NonCancellable) {
            ensureStorageHeadroom(minBytes = 2L * 1024L * 1024L)
            val stagedFile = stageToTempFile(writer)
            try {
                val writtenBytes = stagedFile.length()
                if (writtenBytes <= 0L) error("Generated document was empty — aborted save to protect existing file.")

                val ownedInternal = resolveOwnedInternalFile(targetUri.toString(), allowMissing = true)
                if (ownedInternal != null) {
                    moveOrCopyAtomic(stagedFile, ownedInternal)
                    return@withContext ownedInternal.length()
                }

                val resolver = context.contentResolver
                val outStream = runCatching { resolver.openOutputStream(targetUri, "wt") }.getOrNull()
                    ?: runCatching { resolver.openOutputStream(targetUri, "w") }.getOrNull()
                    ?: resolver.openOutputStream(targetUri)
                    ?: error("Storage denied write access to this file.")

                outStream.use { out ->
                    FileInputStream(stagedFile).use { input -> input.copyTo(out, bufferSize = 32 * 1024) }
                    out.flush()
                }
                writtenBytes
            } finally {
                runCatching { if (stagedFile.exists()) stagedFile.delete() }
            }
        }

    private fun writeStagedFileToPublicMediaStore(stagedFile: File, fileName: String, mimeType: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, KADATA_RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return@runCatching null
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(stagedFile).use { input -> input.copyTo(out, bufferSize = 32 * 1024) }
                    out.flush()
                } ?: error("Cannot open MediaStore output stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                uri
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        }.getOrNull()
    }

    private suspend fun stageToTempFile(writer: suspend (OutputStream) -> Unit): File {
        val stagingDir = File(context.cacheDir, "save_staging").apply { mkdirs() }
        val tmp = File(stagingDir, "stage_${UUID.randomUUID()}.tmp")
        FileOutputStream(tmp).use { fos ->
            writer(fos)
            fos.flush()
            runCatching { fos.fd.sync() }
        }
        return tmp
    }

    private fun moveOrCopyAtomic(sourceTmp: File, targetFile: File) {
        targetFile.parentFile?.mkdirs()
        val sameDirTmp = File(targetFile.parentFile, "${targetFile.name}.tmp")
        sourceTmp.copyTo(sameDirTmp, overwrite = true)
        FileOutputStream(sameDirTmp, true).use { runCatching { it.fd.sync() } }
        if (!sameDirTmp.renameTo(targetFile)) {
            if (targetFile.exists()) targetFile.delete()
            if (!sameDirTmp.renameTo(targetFile)) {
                sameDirTmp.copyTo(targetFile, overwrite = true)
                sameDirTmp.delete()
            }
        }
    }

    private fun ensureStorageHeadroom(minBytes: Long) {
        val free = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free < minBytes) error("Device storage is nearly full (${free / 1024} KB free). Please free up space and tap Save again.")
    }

    suspend fun resolveUniqueKadataFileName(desiredFileName: String): String = withContext(Dispatchers.IO) {
        val ext = desiredFileName.substringAfterLast('.', "docx")
        val rawBase = desiredFileName.substringBeforeLast('.').ifBlank { "Document" }
        val cleanBase = sanitizeFileName(rawBase).ifBlank { "Document" }

        val existingNames = buildSet {
            recentFileDao.getAllSnapshot().forEach { add(it.displayName.lowercase()) }
            File(context.filesDir, "documents").listFiles()?.forEach { add(it.name.lowercase()) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    context.contentResolver.query(
                        collection,
                        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                        arrayOf("$KADATA_RELATIVE_PATH%"),
                        null
                    )?.use { cursor ->
                        while (cursor.moveToNext()) cursor.getString(0)?.let { add(it.lowercase()) }
                    }
                }
            }
        }

        val first = "$cleanBase.$ext"
        if (first.lowercase() !in existingNames) return@withContext first
        var n = 2
        while (n < 1000) {
            val candidate = "$cleanBase ($n).$ext"
            if (candidate.lowercase() !in existingNames) return@withContext candidate
            n++
        }
        "${cleanBase}_${System.currentTimeMillis() % 100000}.$ext"
    }

    private fun resolveUniqueInternalFile(dir: File, desiredName: String): File {
        var candidate = File(dir, desiredName)
        if (!candidate.exists()) return candidate
        val ext = desiredName.substringAfterLast('.', "docx")
        val base = desiredName.substringBeforeLast('.')
        var n = 2
        while (candidate.exists() && n < 1000) {
            candidate = File(dir, "$base ($n).$ext")
            n++
        }
        return candidate
    }

    fun isKadataManagedUri(uri: Uri): Boolean {
        val auth = uri.authority.orEmpty()
        return auth == "${context.packageName}.fileprovider" || auth == MediaStore.AUTHORITY
    }

    suspend fun saveExportedFileInKadataDocuments(
        baseFileName: String,
        suffix: String,
        extension: String,
        format: DocFormat,
        mimeType: String,
        kannadaFormat: String?,
        writer: suspend (OutputStream) -> Unit
    ): Pair<RecentFileEntity, File> = withContext(Dispatchers.IO + NonCancellable) {
        val cleanBase = sanitizeFileName(stripEncodingSuffix(baseFileName.substringBeforeLast('.'))).ifBlank { "Document" }
        val desiredName = if (suffix.isBlank()) "$cleanBase.$extension" else "${cleanBase}_${suffix}.$extension"
        val ingested = saveNewFileInKadataDocuments(
            desiredFileName = desiredName,
            format = format,
            mimeType = mimeType,
            kannadaFormat = kannadaFormat,
            isEdited = true,
            writer = writer
        )
        val shareableFile = copyUriToExportCache(ingested.resolvedUri, ingested.entity.displayName)
        ingested.entity to shareableFile
    }

    suspend fun registerSafSavedUri(
        targetUri: Uri,
        fallbackDisplayName: String,
        format: DocFormat,
        mimeType: String,
        kannadaFormat: String? = null
    ): RecentFileEntity = withContext(Dispatchers.IO) {
        tryPersistPermission(targetUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val meta = queryFileMetadata(targetUri, fallbackDisplayName)
        val resolvedName = meta.displayName
            .replace(INTERNAL_HASH_PREFIX_REGEX, "")
            .takeIf { it.isNotBlank() && !it.equals("Untitled", ignoreCase = true) }
            ?: fallbackDisplayName
        val sizeBytes = if (meta.sizeBytes >= 0) meta.sizeBytes else {
            runCatching { context.contentResolver.openAssetFileDescriptor(targetUri, "r")?.use { it.length } ?: -1L }.getOrDefault(-1L)
        }
        val now = System.currentTimeMillis()
        val uriStr = targetUri.toString()
        val existing = recentFileDao.getByUri(uriStr)
        val entity = RecentFileEntity(
            id = existing?.id ?: 0,
            uriString = uriStr,
            displayName = resolvedName,
            detectedFormat = format,
            mimeType = mimeType,
            fileSizeBytes = sizeBytes,
            isLocallyImported = false,
            converterOverride = null,
            savedUnicodeUri = null,
            kannadaFormat = kannadaFormat ?: existing?.kannadaFormat,
            lastOpenedAt = now,
            lastModifiedAt = now,
            isEdited = true,
            sourceAppLabel = null
        )
        recentFileDao.upsert(entity)
        entity
    }

    suspend fun copyUriToExportCache(sourceUri: Uri, targetDisplayName: String): File = withContext(Dispatchers.IO) {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = sanitizeFileName(targetDisplayName).ifBlank { "Document.docx" }
        val outFile = File(exportDir, safeName)
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            outFile.outputStream().use { output -> input.copyTo(output, bufferSize = 16 * 1024) }
        } ?: error("Unable to read existing file for sharing")
        outFile
    }

    fun uriForFile(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    suspend fun queryActualFileSize(uri: Uri): Long = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") {
            val f = uri.path?.let { File(it) }
            if (f != null && f.exists()) return@withContext f.length()
        }
        val meta = runCatching { queryFileMetadata(uri, null) }.getOrNull()
        if (meta != null && meta.sizeBytes > 0L) return@withContext meta.sizeBytes
        val afdLen = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }.getOrDefault(-1L)
        if (afdLen >= 0L) return@withContext afdLen
        if (meta != null && meta.sizeBytes == 0L) return@withContext 0L
        -1L
    }

    suspend fun queryFileLastModified(uri: Uri): Long = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") {
            val f = uri.path?.let { File(it) }
            if (f != null && f.exists()) return@withContext f.lastModified()
        }
        resolveOwnedInternalFile(uri.toString())?.let { return@withContext it.lastModified() }
        if (uri.scheme == "content") {
            val cols = arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED, MediaStore.MediaColumns.DATE_MODIFIED)
            runCatching {
                context.contentResolver.query(uri, cols, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val docModIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        if (docModIdx >= 0 && !cursor.isNull(docModIdx)) {
                            val v = cursor.getLong(docModIdx)
                            if (v > 0L) return@withContext v
                        }
                        val mediaModIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                        if (mediaModIdx >= 0 && !cursor.isNull(mediaModIdx)) {
                            val sec = cursor.getLong(mediaModIdx)
                            if (sec > 0L) return@withContext if (sec < 10_000_000_000L) sec * 1000L else sec
                        }
                    }
                }
            }
        }
        0L
    }

    /**
     * Patch A physical rename eligibility: strictly limited to Kadata-owned storage
     * (`Documents/Kadata Documents/` via MediaStore and internal `filesDir/documents/` fallback).
     */
    fun canPhysicallyRename(uriString: String): Boolean =
        FileRenamePolicy.isKadataOwnedRenamableUri(uriString, context.packageName)

    /**
     * Physically renames a Kadata-owned document on disk (no metadata-only fallback):
     * - MediaStore (`Documents/Kadata Documents/`): verifies ownership/path & absence of collision,
     *   updates `DISPLAY_NAME`, verifies resulting name, and commits Room metadata in one @Transaction.
     * - Internal `filesDir/documents/`: verifies absence of collision, calls `File.renameTo`, and
     *   commits the new FileProvider URI across Room in one @Transaction.
     * If the Room transaction fails after OS rename succeeds, physically rolls back the filename.
     */
    suspend fun renameOwnedDocumentFile(
        oldUriString: String,
        currentDisplayName: String,
        rawNewName: String,
        recoveryVault: RecoveryVaultManager
    ): Result<PhysicalRenameResult> = withContext(Dispatchers.IO + NonCancellable) {
        if (!canPhysicallyRename(oldUriString)) {
            return@withContext Result.failure(
                IllegalStateException("Only files stored in Kadata Documents can be renamed directly.")
            )
        }

        val finalName = FileRenamePolicy.validateAndNormalizeNewFileName(rawNewName, currentDisplayName)
            .getOrElse { return@withContext Result.failure(it) }

        val oldUri = runCatching { Uri.parse(oldUriString) }.getOrNull()
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid document URI."))

        val now = System.currentTimeMillis()

        // Path 1: Android 10+ MediaStore file inside Documents/Kadata Documents/
        if (oldUri.authority.equals(MediaStore.AUTHORITY, ignoreCase = true)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return@withContext Result.failure(
                    IllegalStateException("MediaStore rename is not supported on this Android version.")
                )
            }
            val mediaInfo = queryMediaStoreOwnedRow(oldUri)
                ?: return@withContext Result.failure(
                    IllegalStateException("File could not be found in Kadata Documents.")
                )
            if (!mediaInfo.relativePath.trim('/').startsWith(KADATA_RELATIVE_PATH.trim('/'), ignoreCase = true)) {
                return@withContext Result.failure(
                    IllegalStateException("Only files inside Documents/Kadata Documents can be renamed directly.")
                )
            }
            if (hasMediaStoreNameCollision(finalName, excludeId = mediaInfo.id)) {
                return@withContext Result.failure(
                    IllegalStateException("A file named \"$finalName\" already exists in Kadata Documents.")
                )
            }

            val previousOnDiskName = mediaInfo.displayName
            val outcome = FileRenamePolicy.executeRenameWithRollback(
                performPhysicalRename = {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                    }
                    val updatedRows = context.contentResolver.update(oldUri, values, null, null)
                    if (updatedRows <= 0) {
                        error("Android did not allow renaming this file.")
                    }
                    val verifiedName = queryMediaStoreOwnedRow(oldUri)?.displayName
                    if (verifiedName != null && !verifiedName.equals(finalName, ignoreCase = true)) {
                        // Roll back silent OS suffixing (e.g. Name (1).docx) so UI never diverges from disk
                        runCatching {
                            context.contentResolver.update(
                                oldUri,
                                ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, previousOnDiskName) },
                                null,
                                null
                            )
                        }
                        error("A file named \"$finalName\" already exists or could not be used.")
                    }
                    PhysicalRenameResult(
                        uriString = oldUriString,
                        displayName = verifiedName ?: finalName,
                        modifiedAt = now
                    )
                },
                commitDatabaseTransaction = { res ->
                    recentFileDao.commitPhysicalRenameTransaction(
                        oldUri = oldUriString,
                        newUri = res.uriString,
                        newDocKey = recoveryVault.docKeyFor(res.uriString),
                        newDisplayName = res.displayName,
                        modifiedAt = res.modifiedAt
                    )
                },
                rollbackPhysicalRename = {
                    val rollbackRows = context.contentResolver.update(
                        oldUri,
                        ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, previousOnDiskName) },
                        null,
                        null
                    )
                    rollbackRows > 0
                }
            )
            outcome.onSuccess { res ->
                recoveryVault.rebindActiveCheckpointFile(oldUriString, res.uriString, res.displayName)
            }
            return@withContext outcome
        }

        // Path 2: Internal fallback file inside filesDir/documents/ (never filesDir/imported/)
        val oldFile = resolveOwnedInternalFile(oldUriString, allowMissing = false)
            ?: return@withContext Result.failure(
                IllegalStateException("Original file could not be found on disk.")
            )
        val docsDirCanon = File(context.filesDir, "documents").canonicalPath
        if (oldFile.parentFile?.canonicalPath != docsDirCanon) {
            return@withContext Result.failure(
                IllegalStateException("Only files stored in Kadata Documents can be renamed directly.")
            )
        }
        val targetFile = File(oldFile.parentFile, finalName)
        if (targetFile.exists() && targetFile.canonicalPath != oldFile.canonicalPath) {
            return@withContext Result.failure(
                IllegalStateException("A file named \"$finalName\" already exists.")
            )
        }

        val outcome = FileRenamePolicy.executeRenameWithRollback(
            performPhysicalRename = {
                if (!oldFile.renameTo(targetFile) || !targetFile.exists()) {
                    error("Unable to rename file on device storage.")
                }
                val newUriStr = uriForFile(targetFile).toString()
                PhysicalRenameResult(
                    uriString = newUriStr,
                    displayName = finalName,
                    modifiedAt = now
                )
            },
            commitDatabaseTransaction = { res ->
                recentFileDao.commitPhysicalRenameTransaction(
                    oldUri = oldUriString,
                    newUri = res.uriString,
                    newDocKey = recoveryVault.docKeyFor(res.uriString),
                    newDisplayName = res.displayName,
                    modifiedAt = res.modifiedAt
                )
            },
            rollbackPhysicalRename = {
                targetFile.renameTo(oldFile) && oldFile.exists()
            }
        )
        outcome.onSuccess { res ->
            recoveryVault.rebindActiveCheckpointFile(oldUriString, res.uriString, res.displayName)
        }
        outcome
    }

    private data class MediaStoreRowInfo(
        val id: Long,
        val displayName: String,
        val relativePath: String
    )

    private fun queryMediaStoreOwnedRow(uri: Uri): MediaStoreRowInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val id = cursor.getLong(0)
                val name = cursor.getString(1).orEmpty()
                val rel = cursor.getString(2).orEmpty()
                MediaStoreRowInfo(id = id, displayName = name, relativePath = rel)
            }
        }.getOrNull()
    }

    private fun hasMediaStoreNameCollision(targetDisplayName: String, excludeId: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$KADATA_RELATIVE_PATH%"),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1).orEmpty()
                    if (id != excludeId && name.equals(targetDisplayName, ignoreCase = true)) {
                        return@use true
                    }
                }
                false
            } ?: false
        }.getOrDefault(false)
    }

    suspend fun deleteInternalFilesIfUnreferenced(removedUriString: String, removedSavedUriString: String?) =
        withContext(Dispatchers.IO) {
            val remaining = recentFileDao.getAllSnapshot()
            val activeUris = buildSet {
                for (item in remaining) {
                    add(item.uriString)
                    item.savedUnicodeUri?.let { add(it) }
                }
            }
            for (uriStr in listOfNotNull(removedUriString, removedSavedUriString).distinct()) {
                if (uriStr in activeUris) continue
                resolveOwnedInternalFile(uriStr)?.let { file -> runCatching { if (file.exists()) file.delete() } }
            }
        }

    suspend fun sweepOrphanedFiles() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val exportMaxAgeMs = 24L * 60 * 60 * 1000
        val orphanMaxAgeMs = 7L * 24 * 60 * 60 * 1000

        for (cacheSub in listOf("exports", "save_staging")) {
            File(context.cacheDir, cacheSub).listFiles()?.forEach { file ->
                if (file.isFile && (now - file.lastModified()) > exportMaxAgeMs) runCatching { file.delete() }
            }
        }

        val referencedPaths = buildSet {
            val allRecent = runCatching { recentFileDao.getAllSnapshot() }.getOrDefault(emptyList())
            for (item in allRecent) {
                resolveOwnedInternalFile(item.uriString)?.canonicalPath?.let { add(it) }
                item.savedUnicodeUri?.let { saved -> resolveOwnedInternalFile(saved)?.canonicalPath?.let { add(it) } }
            }
        }

        for (dirName in listOf("imported", "documents")) {
            File(context.filesDir, dirName).listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return@forEach
                if (canonical !in referencedPaths && (now - file.lastModified()) > orphanMaxAgeMs) runCatching { file.delete() }
            }
        }
    }

    private fun resolveOwnedInternalFile(uriString: String, allowMissing: Boolean = false): File? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        if (uri.authority != "${context.packageName}.fileprovider") return null
        val path = uri.path ?: return null
        val segments = path.trimStart('/').split('/')
        if (segments.size < 2) return null
        val fileName = segments.last()
        val firstSeg = segments.firstOrNull().orEmpty()
        val parentDir = when {
            firstSeg.equals("imported_docs", ignoreCase = true) ||
                path.contains("imported", ignoreCase = true) -> File(context.filesDir, "imported")
            firstSeg.equals("saved_docs", ignoreCase = true) ||
                path.contains("documents", ignoreCase = true) -> File(context.filesDir, "documents")
            else -> return null
        }
        val candidate = File(parentDir, fileName)
        return if (allowMissing || candidate.exists()) candidate else null
    }

    fun openInputStream(uri: Uri): InputStream? = context.contentResolver.openInputStream(uri)

    private fun tryPersistPermission(uri: Uri, intentFlags: Int): Boolean {
        if (uri.scheme != "content") return false
        if (uri.authority == "${context.packageName}.fileprovider") return true
        if (uri.authority == MediaStore.AUTHORITY) return true

        val persistableFlags = intentFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val flagsToRequest = if (persistableFlags != 0) persistableFlags else Intent.FLAG_GRANT_READ_URI_PERMISSION

        return try {
            context.contentResolver.takePersistableUriPermission(uri, flagsToRequest)
            true
        } catch (_: SecurityException) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            } catch (_: SecurityException) {
                false
            }
        }
    }

    private data class DedupedImportCopy(
        val file: File,
        val sizeBytes: Long,
        val existingRow: RecentFileEntity?,
        val newlyCreatedFile: Boolean
    )

    private suspend fun copyVirtualGoogleDocToStorageDeduped(
        sourceUri: Uri,
        displayName: String
    ): DedupedImportCopy {
        val importedDir = File(context.filesDir, "imported").apply { mkdirs() }
        val safeName = sanitizeFileName(displayName).let {
            if (it.endsWith(".docx", ignoreCase = true)) it else "$it.docx"
        }
        val tmpFile = File(importedDir, ".import_${UUID.randomUUID()}.tmp")

        val docxMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        var digestResult: Pair<String, Long>? = runCatching {
            context.contentResolver.openTypedAssetFileDescriptor(sourceUri, docxMime, null)?.use { afd ->
                afd.createInputStream().use { input -> streamToFileWithSha256(input, tmpFile) }
            }
        }.getOrNull()

        if (digestResult == null || digestResult.second <= 0L) {
            val altMimes = context.contentResolver.getStreamTypes(sourceUri, "*/*") ?: emptyArray()
            for (mime in altMimes) {
                digestResult = runCatching {
                    context.contentResolver.openTypedAssetFileDescriptor(sourceUri, mime, null)?.use { afd ->
                        afd.createInputStream().use { input -> streamToFileWithSha256(input, tmpFile) }
                    }
                }.getOrNull()
                if (digestResult != null && digestResult.second > 0L) break
            }
        }

        if (digestResult == null || digestResult.second <= 0L) {
            runCatching { tmpFile.delete() }
            return copyToImportedStorageDeduped(sourceUri, displayName)
        }

        return finalizeDedupedImport(importedDir, tmpFile, safeName, digestResult.first, digestResult.second)
    }

    private suspend fun copyToImportedStorageDeduped(
        sourceUri: Uri,
        displayName: String
    ): DedupedImportCopy {
        val importedDir = File(context.filesDir, "imported").apply { mkdirs() }
        val safeName = sanitizeFileName(displayName).ifBlank { "Document" }
        val tmpFile = File(importedDir, ".import_${UUID.randomUUID()}.tmp")
        val (sha256, sizeBytes) = try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                streamToFileWithSha256(input, tmpFile)
            } ?: error("Unable to open input stream for URI: $sourceUri")
        } catch (t: Throwable) {
            runCatching { tmpFile.delete() }
            throw t
        }
        return finalizeDedupedImport(importedDir, tmpFile, safeName, sha256, sizeBytes)
    }

    private suspend fun finalizeDedupedImport(
        importedDir: File,
        tmpFile: File,
        safeDisplayName: String,
        contentSha256: String,
        sizeBytes: Long
    ): DedupedImportCopy {
        // 1. Check existing imported RecentFile rows by exact byte length + content SHA-256
        //    (dedupes both new content-hashed imports and legacy URI-hashed imports already on device).
        val existingMatch = findExistingImportedRowByContent(sizeBytes, contentSha256)
        if (existingMatch != null) {
            runCatching { tmpFile.delete() }
            return DedupedImportCopy(
                file = existingMatch.second,
                sizeBytes = sizeBytes,
                existingRow = existingMatch.first,
                newlyCreatedFile = false
            )
        }

        // 2. Place under deterministic content-SHA256 filename inside filesDir/imported/
        val canonicalName = ImportSourcePolicy.buildContentDedupedImportFileName(contentSha256, safeDisplayName)
        val canonicalFile = File(importedDir, canonicalName)
        if (canonicalFile.exists() && canonicalFile.length() == sizeBytes && computeFileSha256(canonicalFile) == contentSha256) {
            runCatching { tmpFile.delete() }
            return DedupedImportCopy(
                file = canonicalFile,
                sizeBytes = sizeBytes,
                existingRow = null,
                newlyCreatedFile = false
            )
        }

        if (!tmpFile.renameTo(canonicalFile)) {
            try {
                tmpFile.copyTo(canonicalFile, overwrite = true)
            } finally {
                runCatching { tmpFile.delete() }
            }
        }
        return DedupedImportCopy(
            file = canonicalFile,
            sizeBytes = canonicalFile.length(),
            existingRow = null,
            newlyCreatedFile = true
        )
    }

    private suspend fun findExistingImportedRowByContent(
        sizeBytes: Long,
        contentSha256: String
    ): Pair<RecentFileEntity, File>? {
        val candidates = recentFileDao.getAllSnapshot().filter {
            it.isLocallyImported && (it.fileSizeBytes == sizeBytes || it.fileSizeBytes <= 0L)
        }
        for (row in candidates) {
            val file = resolveOwnedInternalFile(row.uriString, allowMissing = false) ?: continue
            if (file.parentFile?.name != "imported") continue
            if (file.length() != sizeBytes) continue
            if (computeFileSha256(file) == contentSha256) {
                return row to file
            }
        }
        return null
    }

    private fun streamToFileWithSha256(input: InputStream, dest: File): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        FileOutputStream(dest).use { output ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
                output.write(buffer, 0, read)
                total += read
            }
            output.flush()
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        return hex to total
    }

    private fun computeFileSha256(file: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun queryFileMetadata(uri: Uri, titleHint: String?): FileMetadata {
        val fallbackSegment = Uri.decode(uri.lastPathSegment ?: "")
            .substringAfterLast('/').substringAfterLast(':').ifBlank { "Untitled" }
        var displayName = fallbackSegment
        var alternateName: String? = null
        var size = -1L

        for (param in listOf("filename", "name", "displayName", "title")) {
            val qVal = uri.getQueryParameter(param)?.trim()
            if (!qVal.isNullOrEmpty() && !isGenericWhatsAppName(qVal)) { alternateName = qVal; break }
        }

        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                            cursor.getString(nameIdx)?.trim()?.takeIf { it.isNotEmpty() }?.let { displayName = Uri.decode(it) }
                        }
                        val preferredCols = listOf("original_name", "file_name", "filename", "media_name", "title", "document_name", "name", "_data")
                        for (colName in preferredCols) {
                            val idx = cursor.getColumnIndex(colName)
                            if (idx >= 0 && !cursor.isNull(idx)) {
                                val candidate = runCatching { cursor.getString(idx) }.getOrNull()?.trim()?.substringAfterLast('/')?.trim()
                                if (!candidate.isNullOrEmpty() && candidate.length <= 160 && !isGenericWhatsAppName(candidate)) {
                                    alternateName = Uri.decode(candidate); break
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0 && !cursor.isNull(nameIdx)) displayName = Uri.decode(cursor.getString(nameIdx))
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                    }
                }
            }
        }

        val bestName = if (isGenericWhatsAppName(displayName)) {
            val cleanHint = titleHint?.trim()?.substringAfterLast('/')?.takeIf { it.isNotBlank() && it.length <= 160 && !isGenericWhatsAppName(it) }
            val chosen = cleanHint ?: alternateName
            if (chosen != null) {
                val origExt = displayName.substringAfterLast('.', "")
                if (origExt.isNotEmpty() && !chosen.contains('.')) "$chosen.$origExt" else chosen
            } else displayName
        } else displayName

        val cleanedName = if (uri.authority == "${context.packageName}.fileprovider") {
            bestName.replace(INTERNAL_HASH_PREFIX_REGEX, "")
        } else bestName
        return FileMetadata(displayName = cleanedName, sizeBytes = size)
    }

    suspend fun healDisplayNameIfGeneric(uri: Uri, currentDisplayName: String, format: DocFormat): String =
        withContext(Dispatchers.IO) {
            DocumentTitleHealer.healDisplayName(context, uri, currentDisplayName, format, parserFacade)
        }

    private fun isGenericWhatsAppName(name: String): Boolean = DocumentTitleHealer.isGenericDisplayName(name)
    fun sanitizeFileName(name: String): String = name.replace(Regex("[^a-zA-Z0-9._\\-\\u0C80-\\u0CFF ]"), "_").trim()

    private data class FileMetadata(val displayName: String, val sizeBytes: Long)
}
