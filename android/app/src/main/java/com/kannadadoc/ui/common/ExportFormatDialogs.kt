package com.kannadadoc.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kannadadoc.ui.editor.ExportAction
import com.kannadadoc.ui.editor.KannadaEncodingChoice
import com.kannadadoc.ui.editor.TargetFileType

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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (knOnly) "ಹಂತ 1/2: ಕನ್ನಡ ಫಾರ್ಮ್ಯಾಟ್ ಆಯ್ಕೆಮಾಡಿ"
                        else "Step 1 of 2: Choose Kannada format",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    KannadaEncodingChoice.entries.forEach { enc ->
                        OutlinedButton(
                            onClick = { onSelectEncoding(enc) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Text(
                                text = if (knOnly) enc.labelKn else "${enc.labelEn} • ${enc.labelKn}",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(if (knOnly) "ರದ್ದು" else "Cancel")
                }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("$actionTitle (${chosenEncoding.labelEn})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (knOnly) "ಹಂತ 2/2: ಫೈಲ್ ಪ್ರಕಾರ ಆಯ್ಕೆಮಾಡಿ"
                        else "Step 2 of 2: Choose file type",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TargetFileType.entries.forEach { fileType ->
                        Button(
                            onClick = { onSelectFileType(chosenEncoding, fileType) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Text(fileType.label, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                Row {
                    TextButton(onClick = onBackToEncoding) {
                        Text(if (knOnly) "ಹಿಂದೆ" else "Back")
                    }
                    TextButton(onClick = onDismiss) {
                        Text(if (knOnly) "ರದ್ದು" else "Cancel")
                    }
                }
            }
        )
    }
}
