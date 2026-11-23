package com.kannadadoc.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object GoogleDocsShareHelper {

    private const val GOOGLE_DOCS_PKG = "com.google.android.apps.docs.editors.docs"
    private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    /**
     * Opens a clean Modern Universal Kannada .docx file directly in the Google Docs app if installed;
     * otherwise falls back to the standard Android Share sheet.
     * Returns `true` if Google Docs opened directly, `false` if it fell back to the Share sheet.
     */
    fun openOrShareToGoogleDocs(context: Context, docxFile: File): Boolean {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            docxFile
        )

        val directViewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, DOCX_MIME)
            setPackage(GOOGLE_DOCS_PKG)
            clipData = ClipData.newRawUri(docxFile.name, uri)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val openedDirect = runCatching {
            context.startActivity(directViewIntent)
            true
        }.getOrDefault(false)

        if (!openedDirect) {
            ShareHelper.shareFile(
                context = context,
                file = docxFile,
                mimeType = DOCX_MIME,
                chooserTitle = "Open / Share with Google Docs"
            )
        }
        return openedDirect
    }
}
