package com.kannadadoc.data.storage

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.kannadadoc.data.local.RecentFileEntity
import java.io.File

sealed class FileLocationResult {
    data object Opened : FileLocationResult()
    data class InfoMessage(val messageEn: String, val messageKn: String) : FileLocationResult()
}

object FileLocationHelper {

    private data class ResolvedLocation(
        val volumeId: String,          // e.g. "primary" or SD card UUID
        val relativeFolderPath: String, // e.g. "Download/KannadaDocTests" (no leading/trailing slash)
        val absoluteFolderPath: String?, // e.g. "/storage/emulated/0/Download/KannadaDocTests"
        val absoluteFilePath: String?,   // e.g. "/storage/emulated/0/Download/KannadaDocTests/file.docx"
        val parentDocUri: Uri
    )

    /**
     * Navigates directly into the document's exact parent directory in the device's file explorer.
     *
     * Fixes the earlier Android 11+ SecurityException caused by attaching FLAG_GRANT_READ_URI_PERMISSION
     * to an unowned parent directory URI (which forced fallback to Internal Storage root),
     * resolves Downloads/MediaStore URIs (msf:/raw:/document:) into real folder paths, and
     * supports Samsung My Files (START_PATH + highlight) as well as Google/AOSP DocumentsUI.
     */
    fun resolveParentDocumentUri(context: Context, uri: Uri, displayName: String = ""): Uri? {
        if (uri.authority == "${context.packageName}.fileprovider") {
            return DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:${Environment.DIRECTORY_DOCUMENTS}/${SafStorageManager.KADATA_PUBLIC_FOLDER}"
            )
        }
        return resolveLocation(context, uri, displayName)?.parentDocUri
    }

    fun openFileLocation(context: Context, entity: RecentFileEntity): FileLocationResult {
        val uri = Uri.parse(entity.uriString)
        val isInternalAppCopy = uri.authority == "${context.packageName}.fileprovider"

        if (isInternalAppCopy) {
            return FileLocationResult.InfoMessage(
                messageEn = "Stored offline inside Kadata (${entity.displayName}). Use \"Save As\" to place a copy in Downloads or any folder.",
                messageKn = "ಈ ಫೈಲ್ ಕಡತ ಆಪ್ ಒಳಗೆ ಆಫ್‌ಲೈನ್‌ನಲ್ಲಿದೆ (${entity.displayName}). ಫೋಲ್ಡರ್‌ನಲ್ಲಿ ಉಳಿಸಲು \"ಬೇರೆ ಪ್ರತಿಯಾಗಿ ಉಳಿಸಿ\" ಬಳಸಿ."
            )
        }

        val resolved = resolveLocation(context, uri, entity.displayName)

        if (resolved != null) {
            // Strategy 1: Samsung My Files (jumps directly to exact subfolder & supports file selection)
            if (resolved.absoluteFolderPath != null && tryOpenSamsungMyFiles(context, resolved)) {
                return FileLocationResult.Opened
            }

            // Strategy 2: Google DocumentsUI / AOSP DocumentsUI (NO FLAG_GRANT_READ_URI_PERMISSION!)
            for (pkg in listOf("com.google.android.documentsui", "com.android.documentsui")) {
                val docUiIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(resolved.parentDocUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (tryStart(context, docUiIntent)) {
                    return FileLocationResult.Opened
                }
            }

            // Strategy 3: Generic file manager handling vnd.android.document/directory (NO grant flag!)
            val genericDirIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(resolved.parentDocUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStart(context, genericDirIntent)) {
                return FileLocationResult.Opened
            }
        }

        // Strategy 4: If URI came from Downloads provider and path couldn't be queried, open Downloads directly
        if (uri.authority == "com.android.providers.downloads.documents") {
            val dlIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStart(context, dlIntent)) {
                return FileLocationResult.Opened
            }
        }

        val folderHint = resolved?.relativeFolderPath?.takeIf { it.isNotBlank() }
        return if (folderHint != null) {
            FileLocationResult.InfoMessage(
                messageEn = "Location: Internal Storage/$folderHint/${entity.displayName}",
                messageKn = "ಸ್ಥಳ: Internal Storage/$folderHint/${entity.displayName}"
            )
        } else {
            FileLocationResult.InfoMessage(
                messageEn = "Could not open folder directly for ${entity.displayName}.",
                messageKn = "${entity.displayName} ಫೋಲ್ಡರ್ ತೆರೆಯಲು ಸಾಧ್ಯವಾಗಲಿಲ್ಲ."
            )
        }
    }

    private fun tryOpenSamsungMyFiles(context: Context, loc: ResolvedLocation): Boolean {
        val folderPath = loc.absoluteFolderPath ?: return false
        val samsungIntent = Intent("samsung.myfiles.intent.action.LAUNCH_MY_FILES").apply {
            setPackage("com.sec.android.app.myfiles")
            putExtra("samsung.myfiles.intent.extra.START_PATH", folderPath)
            if (loc.absoluteFilePath != null) {
                // Supported by newer One UI My Files builds to scroll/highlight the target file
                putExtra("samsung.myfiles.intent.extra.SELECT_FILE_PATH", loc.absoluteFilePath)
                putExtra("file_path", loc.absoluteFilePath)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return tryStart(context, samsungIntent)
    }

    private fun resolveLocation(context: Context, uri: Uri, displayName: String): ResolvedLocation? {
        val primaryRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')

        // Case A: Direct ExternalStorageProvider URI (e.g. primary:Download/Folder/file.docx)
        if (uri.authority == "com.android.externalstorage.documents" &&
            DocumentsContract.isDocumentUri(context, uri)
        ) {
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            if (!docId.isNullOrBlank()) {
                val colonIdx = docId.indexOf(':')
                if (colonIdx > 0) {
                    val volume = docId.substring(0, colonIdx)
                    val relFilePath = docId.substring(colonIdx + 1).trimStart('/')
                    val relFolder = relFilePath.substringBeforeLast('/', "").trim('/')
                    val parentDocUri = DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "$volume:$relFolder"
                    )
                    val absFolder = if (volume.equals("primary", true)) {
                        if (relFolder.isEmpty()) primaryRoot else "$primaryRoot/$relFolder"
                    } else {
                        val sdBase = "/storage/$volume"
                        if (relFolder.isEmpty()) sdBase else "$sdBase/$relFolder"
                    }
                    val absFile = if (volume.equals("primary", true)) {
                        "$primaryRoot/$relFilePath"
                    } else {
                        "/storage/$volume/$relFilePath"
                    }
                    return ResolvedLocation(volume, relFolder, absFolder, absFile, parentDocUri)
                }
            }
        }

        // Case B: DownloadsProvider raw path (e.g. raw:/storage/emulated/0/Download/Folder/file.docx)
        if (uri.authority == "com.android.providers.downloads.documents" &&
            DocumentsContract.isDocumentUri(context, uri)
        ) {
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull().orEmpty()
            if (docId.startsWith("raw:")) {
                val rawFile = File(docId.removePrefix("raw:"))
                val parentPath = rawFile.parent ?: "$primaryRoot/Download"
                val relFolder = parentPath.removePrefix(primaryRoot).trim('/')
                val parentDocUri = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:$relFolder"
                )
                return ResolvedLocation("primary", relFolder, parentPath, rawFile.absolutePath, parentDocUri)
            }
        }

        // Case C: MediaStore resolution (handles Android 10–15 msf:xxxx Downloads & MediaDocumentsProvider URIs)
        val mediaResolved = resolveViaMediaStore(context, uri, displayName, primaryRoot)
        if (mediaResolved != null) {
            return mediaResolved
        }

        // Case D: Downloads provider fallback -> open Download folder directly (not Internal Storage root)
        if (uri.authority == "com.android.providers.downloads.documents") {
            val relFolder = Environment.DIRECTORY_DOWNLOADS
            val parentDocUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:$relFolder"
            )
            return ResolvedLocation(
                volumeId = "primary",
                relativeFolderPath = relFolder,
                absoluteFolderPath = "$primaryRoot/$relFolder",
                absoluteFilePath = "$primaryRoot/$relFolder/$displayName",
                parentDocUri = parentDocUri
            )
        }

        return null
    }

    private fun resolveViaMediaStore(
        context: Context,
        uri: Uri,
        displayName: String,
        primaryRoot: String
    ): ResolvedLocation? {
        // 1. Try MediaStore.getMediaUri (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mediaUri = runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
            if (mediaUri != null) {
                queryMediaStorePath(context, mediaUri, displayName, primaryRoot)?.let { return it }
            }
        }

        // 2. If docId is "msf:<id>" or "document:<id>", query MediaStore.Files directly by _ID
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull().orEmpty()
            val numericId = when {
                docId.startsWith("msf:") -> docId.removePrefix("msf:").toLongOrNull()
                docId.startsWith("document:") -> docId.removePrefix("document:").toLongOrNull()
                docId.all { it.isDigit() } -> docId.toLongOrNull()
                else -> null
            }
            if (numericId != null) {
                val filesUri = MediaStore.Files.getContentUri("external", numericId)
                queryMediaStorePath(context, filesUri, displayName, primaryRoot)?.let { return it }
            }
        }

        // 3. Query the URI itself for RELATIVE_PATH or _data column
        return queryMediaStorePath(context, uri, displayName, primaryRoot)
    }

    @Suppress("DEPRECATION")
    private fun queryMediaStorePath(
        context: Context,
        queryUri: Uri,
        fallbackName: String,
        primaryRoot: String
    ): ResolvedLocation? {
        return try {
            val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATA
                )
            } else {
                arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME)
            }

            context.contentResolver.query(queryUri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null

                val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val fileName = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                    cursor.getString(nameIdx)
                } else {
                    fallbackName
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    if (relIdx >= 0 && !cursor.isNull(relIdx)) {
                        val relFolder = cursor.getString(relIdx).trim('/')
                        if (relFolder.isNotEmpty()) {
                            val parentDocUri = DocumentsContract.buildDocumentUri(
                                "com.android.externalstorage.documents",
                                "primary:$relFolder"
                            )
                            return ResolvedLocation(
                                volumeId = "primary",
                                relativeFolderPath = relFolder,
                                absoluteFolderPath = "$primaryRoot/$relFolder",
                                absoluteFilePath = "$primaryRoot/$relFolder/$fileName",
                                parentDocUri = parentDocUri
                            )
                        }
                    }
                }

                val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (dataIdx >= 0 && !cursor.isNull(dataIdx)) {
                    val dataPath = cursor.getString(dataIdx)
                    if (!dataPath.isNullOrBlank()) {
                        val f = File(dataPath)
                        val parent = f.parent ?: primaryRoot
                        val relFolder = parent.removePrefix(primaryRoot).trim('/')
                        val parentDocUri = DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents",
                            "primary:$relFolder"
                        )
                        return ResolvedLocation(
                            volumeId = "primary",
                            relativeFolderPath = relFolder,
                            absoluteFolderPath = parent,
                            absoluteFilePath = f.absolutePath,
                            parentDocUri = parentDocUri
                        )
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryStart(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
