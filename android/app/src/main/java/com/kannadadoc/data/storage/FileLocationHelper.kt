package com.kannadadoc.data.storage

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import com.kannadadoc.data.local.RecentFileEntity

sealed class FileLocationResult {
    data object Opened : FileLocationResult()
    data class InfoMessage(val messageEn: String, val messageKn: String) : FileLocationResult()
}

object FileLocationHelper {

    /**
     * Navigates to the Android Files explorer at the document's parent folder and passes
     * DocumentsContract.EXTRA_INITIAL_URI so Android's Files app highlights the file.
     */
    fun openFileLocation(context: Context, entity: RecentFileEntity): FileLocationResult {
        val uri = Uri.parse(entity.uriString)
        val isInternalAppCopy = entity.isLocallyImported ||
            uri.authority == "${context.packageName}.fileprovider"

        if (isInternalAppCopy) {
            // File was copied offline inside Kadata's private folder (e.g., opened from WhatsApp or created in-app).
            // Still try opening Downloads/Files for convenience while informing the user clearly.
            return FileLocationResult.InfoMessage(
                messageEn = "This file is stored offline inside Kadata. Use \"Save As\" to save a copy in Downloads or any folder.",
                messageKn = "ಈ ಫೈಲ್ ಕಡತ ಆಪ್‌ನ ಒಳಗೆ ಆಫ್‌ಲೈನ್‌ನಲ್ಲಿ ಉಳಿಸಲಾಗಿದೆ. ಫೋಲ್ಡರ್‌ನಲ್ಲಿ ಉಳಿಸಲು \"ಬೇರೆ ಪ್ರತಿಯಾಗಿ ಉಳಿಸಿ\" ಬಳಸಿ."
            )
        }

        // 1. Try opening exact parent directory with EXTRA_INITIAL_URI pointing to the file (highlights it in Files)
        val parentDirUri = buildParentDirectoryUri(context, uri)
        if (parentDirUri != null) {
            val dirIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(parentDirUri, DocumentsContract.Document.MIME_TYPE_DIR)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (tryStart(context, dirIntent)) {
                return FileLocationResult.Opened
            }
        }

        // 2. Try Android's DocumentsUI browse intent with EXTRA_INITIAL_URI
        val browseIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.android.externalstorage.documents/root/primary")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryStart(context, browseIntent)) {
            return FileLocationResult.Opened
        }

        // 3. Fallback: Open Downloads view in system Files app
        val downloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryStart(context, downloadsIntent)) {
            return FileLocationResult.Opened
        }

        return FileLocationResult.InfoMessage(
            messageEn = "Could not open file manager on this device.",
            messageKn = "ಈ ಸಾಧನದಲ್ಲಿ ಫೈಲ್ ಮ್ಯಾನೇಜರ್ ತೆರೆಯಲು ಸಾಧ್ಯವಾಗಲಿಲ್ಲ."
        )
    }

    private fun buildParentDirectoryUri(context: Context, fileUri: Uri): Uri? {
        return try {
            if (fileUri.authority == "com.android.externalstorage.documents" &&
                DocumentsContract.isDocumentUri(context, fileUri)
            ) {
                val docId = DocumentsContract.getDocumentId(fileUri)
                // Example docId: "primary:Download/KannadaDocTests/file.docx"
                val colonIdx = docId.indexOf(':')
                if (colonIdx > 0) {
                    val volume = docId.substring(0, colonIdx)
                    val relPath = docId.substring(colonIdx + 1)
                    val parentPath = relPath.substringBeforeLast('/', "")
                    val parentDocId = "$volume:$parentPath"
                    return DocumentsContract.buildDocumentUri(fileUri.authority, parentDocId)
                }
            }
            null
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
