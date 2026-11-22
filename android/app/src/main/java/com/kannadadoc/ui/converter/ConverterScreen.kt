package com.kannadadoc.ui.converter

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kannadadoc.converter.KannadaFormat
import com.kannadadoc.converter.KannadaTextConverter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen(
    knOnly: Boolean,
    blockAutoKeyboard: Boolean,
    onBack: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val inputFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (!blockAutoKeyboard) {
            kotlinx.coroutines.delay(180)
            runCatching { inputFocusRequester.requestFocus() }
            keyboardController?.show()
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var inputText by remember { mutableStateOf("") }
    // Auto-detected input format; user can optionally distinguish Nudi vs Baraha if both look like KGP ASCII
    val detectedInfo = remember(inputText) { KannadaTextConverter.detectInputFormat(inputText) }
    var inputLegacyKind by remember { mutableStateOf(KannadaFormat.NUDI) }

    val effectiveSourceFormat = remember(detectedInfo, inputLegacyKind) {
        if (detectedInfo.detectedFormat == KannadaFormat.UNICODE) {
            KannadaFormat.UNICODE
        } else {
            inputLegacyKind
        }
    }

    // Strictly exclude the source/pasted format from available output formats
    val allowedOutputs = remember(effectiveSourceFormat) {
        KannadaTextConverter.allowedOutputFormats(effectiveSourceFormat)
    }

    var selectedOutputFormat by remember { mutableStateOf(KannadaFormat.UNICODE) }

    // Whenever detected input format changes, ensure selectedOutputFormat is NEVER the same as input
    LaunchedEffect(allowedOutputs) {
        if (selectedOutputFormat !in allowedOutputs) {
            selectedOutputFormat = allowedOutputs.first()
        }
    }

    val outputText = remember(inputText, effectiveSourceFormat, selectedOutputFormat) {
        if (inputText.isEmpty()) ""
        else KannadaTextConverter.convert(inputText, effectiveSourceFormat, selectedOutputFormat)
    }

    fun copyToClipboard(text: String, label: String) {
        if (text.isEmpty()) return
        clipboard.setText(AnnotatedString(text))
        scope.launch {
            snackbarHostState.showSnackbar(
                if (knOnly) "$label ನಕಲಿಸಲಾಗಿದೆ" else "$label copied to clipboard"
            )
        }
    }

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(22.dp))
                    }
                    Text(
                        text = if (knOnly) "ಕನ್ನಡ ಪರಿವರ್ತಕ" else "Kannada Converter • ಕನ್ನಡ ಪರಿವರ್ತಕ",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // =================================================================
            // TOP BOX: INPUT (Auto-detects pasted format + Paste + Copy + Clear)
            // =================================================================
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (knOnly) "ಇನ್‌ಪುಟ್ ಪಠ್ಯ" else "Input Text • ಇನ್‌ಪುಟ್",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = if (inputText.isBlank()) {
                                        if (knOnly) "ಸ್ವಯಂ ಪತ್ತೆ ಸಿದ್ಧವಾಗಿದೆ" else "Auto-detect ready"
                                    } else {
                                        val label = if (knOnly) effectiveSourceFormat.labelKn else detectedInfo.confidenceNoteEn
                                        if (knOnly) "ಪತ್ತೆಯಾದದ್ದು: $label" else "Detected: $label"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    clipboard.getText()?.text?.let { pasted ->
                                        inputText = pasted
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.ContentPaste,
                                    contentDescription = "Paste",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (inputText.isNotEmpty()) {
                                IconButton(onClick = { inputText = "" }) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = "Clear",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            FilledTonalButton(
                                onClick = { copyToClipboard(inputText, if (knOnly) "ಇನ್‌ಪುಟ್" else "Input") },
                                enabled = inputText.isNotEmpty(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (knOnly) "ನಕಲಿಸಿ" else "Copy")
                            }
                        }
                    }

                    // If pasted text is legacy ASCII, allow toggling whether source is Nudi or Baraha
                    if (inputText.isNotBlank() && detectedInfo.detectedFormat != KannadaFormat.UNICODE) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (knOnly) "ಮೂಲ:" else "Source:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            listOf(KannadaFormat.NUDI, KannadaFormat.BARAHA).forEach { fmt ->
                                FilterChip(
                                    selected = inputLegacyKind == fmt,
                                    onClick = { inputLegacyKind = fmt },
                                    label = { Text(if (knOnly) fmt.labelKn else fmt.labelEn) }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                if (knOnly) "ನುಡಿ, ಬರಹ ಅಥವಾ ಯೂನಿಕೋಡ್ ಕನ್ನಡ ಪಠ್ಯವನ್ನು ಇಲ್ಲಿ ಅಂಟಿಸಿ..."
                                else "Paste Nudi, Baraha, or Unicode Kannada text here..."
                            )
                        },
                        minLines = 5,
                        maxLines = 9,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(inputFocusRequester)
                    )
                }
            }

            // =================================================================
            // BOTTOM BOX: OUTPUT (Output format selector excluding source + Copy)
            // =================================================================
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (knOnly) "ಔಟ್‌ಪುಟ್ ಫಾರ್ಮ್ಯಾಟ್" else "Output Format",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        FilledTonalButton(
                            onClick = { copyToClipboard(outputText, if (knOnly) "ಔಟ್‌ಪುಟ್" else "Converted text") },
                            enabled = outputText.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (knOnly) "ನಕಲಿಸಿ" else "Copy")
                        }
                    }

                    // Format selector row on top side of Output box (strictly excludes pasted/source format)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        allowedOutputs.forEach { targetFormat ->
                            FilterChip(
                                selected = selectedOutputFormat == targetFormat,
                                onClick = { selectedOutputFormat = targetFormat },
                                label = {
                                    Text(if (knOnly) targetFormat.labelKn else targetFormat.labelEn)
                                }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = outputText,
                        onValueChange = {},
                        readOnly = true,
                        placeholder = {
                            Text(
                                if (knOnly) "ಪರಿವರ್ತಿಸಿದ ಕನ್ನಡ ಪಠ್ಯ ಇಲ್ಲಿ ಕಾಣಿಸುತ್ತದೆ..."
                                else "Converted Kannada text appears here automatically..."
                            )
                        },
                        minLines = 5,
                        maxLines = 9,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
