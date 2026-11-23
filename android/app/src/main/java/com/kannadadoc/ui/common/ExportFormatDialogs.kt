package com.kannadadoc.ui.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kannadadoc.data.local.DefaultSaveFormat
import com.kannadadoc.ui.editor.ExportAction
import com.kannadadoc.ui.editor.KannadaEncodingChoice
import com.kannadadoc.ui.editor.TargetFileType

/**
 * Dynamic SAF CreateDocument contract accepting Pair(suggestedFileName, mimeType)
 * so a single launcher cleanly handles all 9 export formats (.docx, .doc, .pdf, .odt, .txt, .rtf, .zip, .epub, Google Docs).
 */
data class CreateDocumentRequest(
    val suggestedName: String,
    val mimeType: String,
    val initialParentUri: Uri? = null
)

class CreateDocumentWithMimeContract : ActivityResultContract<CreateDocumentRequest, Uri?>() {
    override fun createIntent(context: Context, input: CreateDocumentRequest): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = input.mimeType
            putExtra(Intent.EXTRA_TITLE, input.suggestedName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && input.initialParentUri != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, input.initialParentUri)
            }
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return if (resultCode == Activity.RESULT_OK) intent?.data else null
    }
}

@Composable
fun TwoStepFormatExportDialog(
    action: ExportAction,
    chosenEncoding: KannadaEncodingChoice?,
    knOnly: Boolean,
    onSelectEncoding: (KannadaEncodingChoice) -> Unit,
    onBackToEncoding: () -> Unit,
    onSelectFileType: (KannadaEncodingChoice, TargetFileType) -> Unit,
    onDismiss: () -> Unit
) {
    val actionTitle = when (action) {
        ExportAction.SHARE_DIFFERENT -> if (knOnly) "ಬೇರೆ ಫಾರ್ಮ್ಯಾಟ್ ಹಂಚಿಕೊಳ್ಳಿ" else "Share Different Format"
        ExportAction.SAVE_COPY -> if (knOnly) "ಬೇರೆ ಪ್ರತಿಯಾಗಿ ಉಳಿಸಿ" else "Save As"
    }

    if (chosenEncoding == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(actionTitle) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Step 1 of 2: Choose Kannada format • ಹಂತ 1/2: ಕನ್ನಡ ಫಾರ್ಮ್ಯಾಟ್ ಆಯ್ಕೆಮಾಡಿ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    KannadaEncodingChoice.entries.forEach { enc ->
                        Surface(
                            onClick = { onSelectEncoding(enc) },
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = enc.bilingualTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = enc.bilingualSubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = DefaultSaveFormat.KADATA_ALL_FORMATS_NOTE_BILINGUAL,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(if (knOnly) "ರದ್ದು" else "Cancel • ರದ್ದು")
                }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("$actionTitle (${chosenEncoding.bilingualTitle})") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (knOnly) "ಹಂತ 2/2: ಫೈಲ್ ಪ್ರಕಾರ ಆಯ್ಕೆಮಾಡಿ"
                        else "Step 2 of 2: Choose file format • ಹಂತ 2/2: ಫೈಲ್ ಪ್ರಕಾರ ಆಯ್ಕೆಮಾಡಿ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TargetFileType.entries.forEach { fileType ->
                        Button(
                            onClick = { onSelectFileType(chosenEncoding, fileType) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Text(
                                text = fileType.label,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                Row {
                    TextButton(onClick = onBackToEncoding) {
                        Text(if (knOnly) "ಹಿಂದೆ" else "Back • ಹಿಂದೆ")
                    }
                    TextButton(onClick = onDismiss) {
                        Text(if (knOnly) "ರದ್ದು" else "Cancel • ರದ್ದು")
                    }
                }
            }
        )
    }
}
