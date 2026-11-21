package com.kannadadoc.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager
import android.net.Uri
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import com.kannadadoc.data.local.AppPreferences
import com.kannadadoc.data.local.AppThemeMode
import com.kannadadoc.data.local.DefaultSaveFormat
import com.kannadadoc.data.local.UserSettings
import com.kannadadoc.export.ShareHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    userSettings: UserSettings,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onSaveFormatChange: (DefaultSaveFormat) -> Unit,
    onAutoSaveIntervalChange: (Int) -> Unit,
    onDefaultFontSizeChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val knOnly = userSettings.isKannadaUi

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isWebReady by remember { mutableStateOf(false) }
    var lastInjectedKey by remember { mutableStateOf<String?>(null) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showDocInfoDialog by remember { mutableStateOf(false) }
    var showKannadaKeyboard by remember { mutableStateOf(false) }
    var keyboardAnchor by remember { mutableStateOf(KeyboardAnchor.BOTTOM_LEFT) }

    // Two-step export / share different format dialog state
    var pendingExportAction by remember { mutableStateOf<ExportAction?>(null) }
    var chosenEncoding by remember { mutableStateOf<KannadaEncodingChoice?>(null) }

    // Mutation confirmation dialog states
    var showConfirmReplaceAll by remember { mutableStateOf(false) }

    // Find & Replace UI state
    var showFindBar by remember { mutableStateOf(false) }
    var showReplaceRow by remember { mutableStateOf(true) }
    var findQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var currentMatchIdx by remember { mutableIntStateOf(0) }
    var totalMatches by remember { mutableIntStateOf(0) }

    // Separate SAF CreateDocument launchers for .doc, .docx, and .pdf
    val createDocxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(TargetFileType.DOCX.mimeType)
    ) { uri -> uri?.let { viewModel.writePendingSafCopy(it) } }

    val createDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(TargetFileType.DOC.mimeType)
    ) { uri -> uri?.let { viewModel.writePendingSafCopy(it) } }

    val createPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(TargetFileType.PDF.mimeType)
    ) { uri -> uri?.let { viewModel.writePendingSafCopy(it) } }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    LaunchedEffect(uiState.conversionBannerText) {
        if (uiState.conversionBannerText != null) {
            delay(4_200)
            viewModel.dismissConversionBanner()
        }
    }

    // Hide the green saved tick mark after 2.5 seconds (Save icon stays green while !isDirty)
    LaunchedEffect(uiState.showSavedTick) {
        if (uiState.showSavedTick) {
            delay(2_500)
            viewModel.clearSavedTick()
        }
    }

    // Sync Light/Dark theme to the WebView canvas
    LaunchedEffect(isWebReady, userSettings.isDarkTheme) {
        if (isWebReady) {
            webViewRef?.evaluateJavascript(
                "window.KannadaDocEditor && window.KannadaDocEditor.setDarkTheme(${userSettings.isDarkTheme});",
                null
            )
        }
    }

    // Configurable Auto-Save file timer (default 10 minutes) — saves automatically when dirty
    LaunchedEffect(isWebReady, uiState.isDirty, uiState.isOriginalView, userSettings.autoSaveIntervalMinutes, userSettings.defaultSaveFormat) {
        if (isWebReady && uiState.isDirty && !uiState.isOriginalView) {
            val intervalMs = userSettings.autoSaveIntervalMinutes.coerceAtLeast(1) * 60_000L
            delay(intervalMs)
            webViewRef?.evaluateJavascript(
                "window.KannadaDocEditor && window.KannadaDocEditor.requestExport('${userSettings.defaultSaveFormat.jsExportMode}', true);",
                null
            )
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                webViewRef?.evaluateJavascript("window.KannadaDocEditor && window.KannadaDocEditor.flushDraft();", null)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isWebReady, uiState.uriString, uiState.parsedDocJson) {
        val json = uiState.parsedDocJson
        val docKey = "${uiState.uriString}:${json?.hashCode()}"
        if (isWebReady && json != null && lastInjectedKey != docKey) {
            lastInjectedKey = docKey
            val jsDocArg = JSONObject.quote(json)
            val jsConvArg = JSONObject.quote(uiState.activeConverter)
            val jsDraftArg = JSONObject.quote(uiState.savedDraftHtml ?: "")
            webViewRef?.evaluateJavascript(
                "window.KannadaDocEditor.setDarkTheme(${userSettings.isDarkTheme});" +
                    "window.KannadaDocEditor.setFontSize(${uiState.fontSizePt});" +
                    "window.KannadaDocEditor.loadDocument($jsDocArg, $jsConvArg, $jsDraftArg);",
                null
            )
        }
    }

    fun execJs(script: String) {
        webViewRef?.evaluateJavascript(script, null)
    }

    fun closeFindBar() {
        showFindBar = false
        findQuery = ""
        currentMatchIdx = 0
        totalMatches = 0
        execJs("window.KannadaDocEditor && window.KannadaDocEditor.clearSearch();")
    }

    val savedGreen = if (userSettings.isDarkTheme) Color(0xFF66BB6A) else Color(0xFF2E7D32)
    val unsavedGrey = if (userSettings.isDarkTheme) Color(0xFF8E968E) else Color(0xFF9E9E9E)

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = if (showKannadaKeyboard) 0.dp else 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                ) {
                    AnimatedVisibility(visible = !showKannadaKeyboard) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                    IconButton(
                        onClick = {
                            execJs("window.KannadaDocEditor && window.KannadaDocEditor.setVirtualKeyboardMode(false, false, 0);")
                            execJs("window.KannadaDocEditor && window.KannadaDocEditor.clearSearch();")
                            execJs("window.KannadaDocEditor && window.KannadaDocEditor.flushDraft();")
                            onBack()
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(22.dp))
                    }

                    Text(
                        text = uiState.documentTitle.ifBlank { "Untitled" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                    )
                    // Icon-only Save button (green when saved/clean, grey when edited/dirty)
                    // + green tick mark shown for 2.5 seconds after manual or auto-save
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                execJs(
                                    "window.KannadaDocEditor.requestExport('${userSettings.defaultSaveFormat.jsExportMode}', false);"
                                )
                            },
                            enabled = !uiState.isSaving && !uiState.isOriginalView
                        ) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = if (knOnly) "ಉಳಿಸಿ" else "Save",
                                tint = if (uiState.isDirty) unsavedGrey else savedGreen,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        AnimatedVisibility(visible = uiState.showSavedTick) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Saved",
                                tint = savedGreen,
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .size(20.dp)
                            )
                        }
                    }

                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                            containerColor = if (userSettings.isDarkTheme) Color.Black else MaterialTheme.colorScheme.surface
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (knOnly) "ಹುಡುಕಿ / ಬದಲಿಸಿ" else "Find & Replace • ಹುಡುಕಿ / ಬದಲಿಸಿ")
                                },
                                leadingIcon = { Icon(Icons.Default.FindReplace, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    showFindBar = true
                                    showReplaceRow = true
                                }
                            )
                            HorizontalDivider()
                            // Clean 3-option Share / Save menu
                            DropdownMenuItem(
                                text = { Text(if (knOnly) "ಹಂಚಿಕೊಳ್ಳಿ" else "Share • ಹಂಚಿಕೊಳ್ಳಿ") },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.shareExistingFileAsIs()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (knOnly) "ಬೇರೆ ಫಾರ್ಮ್ಯಾಟ್ ಹಂಚಿಕೊಳ್ಳಿ"
                                        else "Share Different Format"
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    chosenEncoding = null
                                    pendingExportAction = ExportAction.SHARE_DIFFERENT
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (knOnly) "ಪ್ರತಿ ಉಳಿಸಿ" else "Save a Copy • ಪ್ರತಿ ಉಳಿಸಿ") },
                                leadingIcon = { Icon(Icons.Default.Save, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    chosenEncoding = null
                                    pendingExportAction = ExportAction.SAVE_COPY
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (uiState.showFormattingStrip) {
                                            if (knOnly) "ಫಾರ್ಮ್ಯಾಟ್ ಪಟ್ಟಿ ಮರೆಮಾಡಿ" else "Hide Formatting Bar"
                                        } else {
                                            if (knOnly) "ಫಾರ್ಮ್ಯಾಟ್ ಪಟ್ಟಿ ತೋರಿಸಿ" else "Show Formatting Bar"
                                        }
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.FormatSize, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.toggleFormattingStrip()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (uiState.isOriginalView) {
                                            if (knOnly) "ತಿದ್ದುಪಡಿ ಮೋಡ್‌ಗೆ ಹಿಂತಿರುಗಿ" else "Back to Editable Kannada"
                                        } else {
                                            if (knOnly) "ಮೂಲ ದಾಖಲೆ ನೋಡಿ (ಓದಲು ಮಾತ್ರ)" else "View Original Document (Read-Only)"
                                        }
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.Visibility, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    val next = !uiState.isOriginalView
                                    if (next && showKannadaKeyboard) {
                                        showKannadaKeyboard = false
                                        execJs("window.KannadaDocEditor.setVirtualKeyboardMode(false, false, 0);")
                                    }
                                    viewModel.setOriginalView(next)
                                    execJs("window.KannadaDocEditor.setOriginalView($next);")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (knOnly) "ಸೆಟ್ಟಿಂಗ್‌ಗಳು" else "Settings • ಸೆಟ್ಟಿಂಗ್‌ಗಳು") },
                                leadingIcon = { Icon(Icons.Default.Settings, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    showSettingsDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (knOnly) "ಪ್ರಿಂಟ್ ಮಾಡಿ" else "Print Document") },
                                leadingIcon = { Icon(Icons.Default.Print, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    webViewRef?.let { wv ->
                                        ShareHelper.printWebView(context, wv, uiState.documentTitle)
                                    }
                                }
                            )
                            HorizontalDivider()
                            // Document Information stays at the very bottom below Print Document
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = if (knOnly) "ದಾಖಲೆ ಮಾಹಿತಿ" else "Document Information • ದಾಖಲೆ ಮಾಹಿತಿ",
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            text = "${uiState.documentInfo.detectedEncodingLabel} • ${uiState.documentInfo.fileFormat.badgeLabel}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                },
                                leadingIcon = { Icon(Icons.Default.Info, null) },
                                onClick = {
                                    showOverflowMenu = false
                                    showDocInfoDialog = true
                                }
                            )
                        }
                    }
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        // Stable WebView slot preserved across Tablet side-by-side Row <-> Phone Column transitions
        val latestOnExportReady by rememberUpdatedState({ mode: String, idmJson: String, isAutoSave: Boolean ->
            viewModel.handleExportPayload(
                exportMode = mode,
                idmJson = idmJson,
                isAutoSave = isAutoSave,
                onRequestSafCreateFile = { suggested, mime ->
                    when (mime) {
                        TargetFileType.DOC.mimeType -> createDocLauncher.launch(suggested)
                        TargetFileType.PDF.mimeType -> createPdfLauncher.launch(suggested)
                        else -> createDocxLauncher.launch(suggested)
                    }
                }
            )
        })
        val latestOnReplaceDone by rememberUpdatedState({ replacedCount: Int ->
            if (replacedCount > 1) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Replaced $replacedCount occurrences")
                }
            } else if (replacedCount == 0 && findQuery.isNotEmpty()) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("No matches found")
                }
            }
        })
        val stableWebViewSlot = remember {
            movableContentOf {
                EditorWebViewHost(
                    onWebViewCreated = { webViewRef = it },
                    onEditorReady = { isWebReady = true },
                    onDocumentLoaded = viewModel::onWebDocumentLoaded,
                    onContentDirty = viewModel::markDirty,
                    onDraftChanged = viewModel::onDraftChanged,
                    onExportReady = { mode, idmJson, isAutoSave ->
                        latestOnExportReady(mode, idmJson, isAutoSave)
                    },
                    onSearchResults = { current, total ->
                        currentMatchIdx = current
                        totalMatches = total
                    },
                    onReplaceDone = { count ->
                        latestOnReplaceDone(count)
                    },
                    onEditorError = { err ->
                        viewModel.reportEditorError(err)
                    }
                )
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val viewportWidth = maxWidth
            val viewportHeight = maxHeight
            val isTabletSideMode = viewportWidth >= 600.dp

            Column(modifier = Modifier.fillMaxSize()) {
            if (uiState.isSaving) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Collapsible Formatting Strip (Table button removed as requested)
            AnimatedVisibility(visible = uiState.showFormattingStrip && !uiState.isOriginalView && !showKannadaKeyboard) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FormatButton("Find & Replace", Icons.Default.Search) {
                            if (showFindBar) {
                                closeFindBar()
                            } else {
                                showFindBar = true
                                showReplaceRow = true
                            }
                        }
                        FormatButton("Undo", Icons.AutoMirrored.Filled.Undo) {
                            execJs("window.KannadaDocEditor.execFormat('undo');")
                        }
                        FormatButton("Redo", Icons.AutoMirrored.Filled.Redo) {
                            execJs("window.KannadaDocEditor.execFormat('redo');")
                        }
                        FormatButton("Smaller text", Icons.Default.TextDecrease) {
                            val next = viewModel.adjustFontSize(-2)
                            execJs("window.KannadaDocEditor.setFontSize($next);")
                        }
                        FormatButton("Larger text", Icons.Default.TextIncrease) {
                            val next = viewModel.adjustFontSize(+2)
                            execJs("window.KannadaDocEditor.setFontSize($next);")
                        }
                        Spacer(Modifier.width(6.dp))
                        FormatButton("B", Icons.Default.FormatBold) {
                            execJs("window.KannadaDocEditor.execFormat('bold');")
                        }
                        FormatButton("I", Icons.Default.FormatItalic) {
                            execJs("window.KannadaDocEditor.execFormat('italic');")
                        }
                        FormatButton("U", Icons.Default.FormatUnderlined) {
                            execJs("window.KannadaDocEditor.execFormat('underline');")
                        }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(onClick = { execJs("window.KannadaDocEditor.execFormat('heading1');") }) {
                            Text("H1")
                        }
                        OutlinedButton(onClick = { execJs("window.KannadaDocEditor.execFormat('heading2');") }) {
                            Text("H2")
                        }
                        OutlinedButton(onClick = { execJs("window.KannadaDocEditor.execFormat('paragraph');") }) {
                            Text("Normal")
                        }
                        Spacer(Modifier.width(6.dp))
                        FormatButton("Bullets", Icons.AutoMirrored.Filled.FormatListBulleted) {
                            execJs("window.KannadaDocEditor.execFormat('bulletList');")
                        }
                        FormatButton("Numbers", Icons.Default.FormatListNumbered) {
                            execJs("window.KannadaDocEditor.execFormat('orderedList');")
                        }
                        Spacer(Modifier.width(6.dp))
                        FormatButton("Left", Icons.AutoMirrored.Filled.FormatAlignLeft) {
                            execJs("window.KannadaDocEditor.execFormat('alignLeft');")
                        }
                        FormatButton("Center", Icons.Default.FormatAlignCenter) {
                            execJs("window.KannadaDocEditor.execFormat('alignCenter');")
                        }
                        FormatButton("Right", Icons.AutoMirrored.Filled.FormatAlignRight) {
                            execJs("window.KannadaDocEditor.execFormat('alignRight');")
                        }
                    }
                }
            }

            // Find & Replace Bar
            AnimatedVisibility(visible = showFindBar) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = findQuery,
                                onValueChange = { value ->
                                    findQuery = value
                                    execJs("window.KannadaDocEditor.findText(${JSONObject.quote(value)});")
                                },
                                placeholder = {
                                    Text(if (knOnly) "ಹುಡುಕಿ..." else "Find • ಹುಡುಕಿ...")
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = { execJs("window.KannadaDocEditor.findNext();") }
                                ),
                                modifier = Modifier.weight(1f)
                            )

                            Text(
                                text = if (findQuery.isEmpty()) "" else "$currentMatchIdx/$totalMatches",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (totalMatches > 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            IconButton(
                                onClick = { execJs("window.KannadaDocEditor.findPrev();") },
                                enabled = totalMatches > 0
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous match")
                            }

                            IconButton(
                                onClick = { execJs("window.KannadaDocEditor.findNext();") },
                                enabled = totalMatches > 0
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next match")
                            }

                            if (!uiState.isOriginalView) {
                                IconButton(onClick = { showReplaceRow = !showReplaceRow }) {
                                    Icon(
                                        Icons.Default.FindReplace,
                                        contentDescription = "Toggle Replace",
                                        tint = if (showReplaceRow) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            IconButton(onClick = { closeFindBar() }) {
                                Icon(Icons.Default.Close, contentDescription = "Close search")
                            }
                        }

                        AnimatedVisibility(visible = showReplaceRow && !uiState.isOriginalView) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = replaceQuery,
                                    onValueChange = { replaceQuery = it },
                                    placeholder = {
                                        Text(if (knOnly) "ಬದಲಿಸಿ..." else "Replace with • ಬದಲಿಸಿ...")
                                    },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            execJs("window.KannadaDocEditor.replaceCurrent(${JSONObject.quote(replaceQuery)});")
                                        }
                                    ),
                                    modifier = Modifier.weight(1f)
                                )

                                FilledTonalButton(
                                    onClick = {
                                        execJs("window.KannadaDocEditor.replaceCurrent(${JSONObject.quote(replaceQuery)});")
                                    },
                                    enabled = totalMatches > 0,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(if (knOnly) "ಬದಲಿಸಿ" else "Replace")
                                }

                                Button(
                                    onClick = { showConfirmReplaceAll = true },
                                    enabled = findQuery.isNotEmpty() && totalMatches > 0,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(if (knOnly) "ಎಲ್ಲಾ" else "All")
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
            }

            AnimatedVisibility(visible = uiState.conversionBannerText != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = uiState.conversionBannerText ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }
            }

            AnimatedVisibility(visible = uiState.warningMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = uiState.warningMessage ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = viewModel::dismissWarning) {
                            Text(if (knOnly) "ಮುಚ್ಚಿ" else "Dismiss")
                        }
                    }
                }
            }

                val keyboardVisible = showKannadaKeyboard && !uiState.isLoading && !uiState.isOriginalView

                if (isTabletSideMode && keyboardVisible) {
                    // TABLET SIDE-BY-SIDE MODE:
                    // Left = resizable Kannada hover keyboard (not restricted to bottom half)
                    // Right = document preview shifted right, filling 100% of remaining space
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        KannadaKeyboardPane(
                            totalScreenWidth = viewportWidth,
                            totalScreenHeight = viewportHeight,
                            isTabletSideMode = true,
                            knOnly = knOnly,
                            onTopLimitChanged = { limitPx ->
                                execJs("window.KannadaDocEditor && window.KannadaDocEditor.updateKeyboardTopLimit($limitPx);")
                            },
                            onInsertText = { text, isSubscript ->
                                execJs(
                                    "window.KannadaDocEditor && window.KannadaDocEditor.insertTextAtCursor(" +
                                        "${JSONObject.quote(text)}, $isSubscript);"
                                )
                            },
                            onBackspace = {
                                execJs("window.KannadaDocEditor && window.KannadaDocEditor.deleteBackAtCursor();")
                            },
                            onEnter = {
                                execJs("window.KannadaDocEditor && window.KannadaDocEditor.insertNewLineAtCursor();")
                            },
                            onClose = {
                                showKannadaKeyboard = false
                                execJs("window.KannadaDocEditor && window.KannadaDocEditor.setVirtualKeyboardMode(false, false, 0);")
                            }
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            stableWebViewSlot()
                        }
                    }
                } else {
                    // PHONE MODE (or Tablet with keyboard closed):
                    // Top = document WebView; Bottom (on phone when open) = <= 50% docked keyboard
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        stableWebViewSlot()

                        if (!uiState.isLoading && !uiState.isOriginalView && !showKannadaKeyboard) {
                            FloatingKeyboardToggleButton(
                                anchor = keyboardAnchor,
                                onAnchorChange = { keyboardAnchor = it },
                                onOpenKeyboard = {
                                    showKannadaKeyboard = true
                                    softwareKeyboardController?.hide()
                                    webViewRef?.let { wv ->
                                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                        imm?.hideSoftInputFromWindow(wv.windowToken, 0)
                                    }
                                    execJs("window.KannadaDocEditor && window.KannadaDocEditor.setVirtualKeyboardMode(true, $isTabletSideMode, 0);")
                                }
                            )
                        }

                        if (uiState.isLoading) {
                            Surface(
                                color = MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator()
                                        Spacer(Modifier.height(14.dp))
                                        Text(
                                            text = if (knOnly) "ದಾಖಲೆ ತೆರೆಯುತ್ತಿದೆ..."
                                            else "Opening document... • ದಾಖಲೆ ತೆರೆಯುತ್ತಿದೆ...",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (keyboardVisible) {
                        KannadaKeyboardPane(
                            totalScreenWidth = viewportWidth,
                            totalScreenHeight = viewportHeight,
                            isTabletSideMode = false,
                            knOnly = knOnly,
                            onTopLimitChanged = { limitPx ->
                                execJs("window.KannadaDocEditor && window.KannadaDocEditor.updateKeyboardTopLimit($limitPx);")
                            },
                            onInsertText = { text, isSubscript ->
                                execJs(
                                    "window.KannadaDocEditor && window.KannadaDocEditor.insertTextAtCursor(" +
                                        "${JSONObject.quote(text)}, $isSubscript);"
                                )
                            },
                            onBackspace = {
                                execJs("window.KannadaDocEditor && window.KannadaDocEditor.deleteBackAtCursor();")
                            },
                            onEnter = {
                                execJs("window.KannadaDocEditor && window.KannadaDocEditor.insertNewLineAtCursor();")
                            },
                            onClose = {
                                showKannadaKeyboard = false
                                execJs("window.KannadaDocEditor && window.KannadaDocEditor.setVirtualKeyboardMode(false, false, 0);")
                            }
                        )
                    }
                }
        }
        }
    }

    // Two-Step Dialog for "Save a Copy" and "Share Different Format"
    // Step 1: Select Kannada Encoding (Nudi / Baraha / Modern Unicode)
    // Step 2: Select File Format (.doc / .docx / .pdf)
    pendingExportAction?.let { action ->
        val actionTitle = when (action) {
            ExportAction.SHARE_DIFFERENT -> if (knOnly) "ಬೇರೆ ಫಾರ್ಮ್ಯಾಟ್ ಹಂಚಿಕೊಳ್ಳಿ" else "Share Different Format"
            ExportAction.SAVE_COPY -> if (knOnly) "ಪ್ರತಿ ಉಳಿಸಿ" else "Save a Copy"
        }

        if (chosenEncoding == null) {
            // Step 1: Kannada Encoding Picker
            AlertDialog(
                onDismissRequest = { pendingExportAction = null },
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
                                onClick = { chosenEncoding = enc },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
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
                    TextButton(onClick = { pendingExportAction = null }) {
                        Text(if (knOnly) "ರದ್ದು" else "Cancel")
                    }
                }
            )
        } else {
            // Step 2: File Format Picker (.doc / .docx / .pdf)
            val selectedEnc = chosenEncoding!!
            AlertDialog(
                onDismissRequest = {
                    chosenEncoding = null
                    pendingExportAction = null
                },
                title = { Text("$actionTitle (${selectedEnc.labelEn})") },
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
                                onClick = {
                                    val prefix = if (action == ExportAction.SHARE_DIFFERENT) "share" else "savecopy"
                                    val mode = "${prefix}_${selectedEnc.id}_${fileType.id}"
                                    chosenEncoding = null
                                    pendingExportAction = null
                                    execJs("window.KannadaDocEditor.requestExport('$mode', false);")
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                            ) {
                                Text(fileType.label, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    Row {
                        TextButton(onClick = { chosenEncoding = null }) {
                            Text(if (knOnly) "ಹಿಂದೆ" else "Back")
                        }
                        TextButton(onClick = {
                            chosenEncoding = null
                            pendingExportAction = null
                        }) {
                            Text(if (knOnly) "ರದ್ದು" else "Cancel")
                        }
                    }
                }
            )
        }
    }

    // Confirmation Dialog for Replace All (irreversible bulk mutation)
    if (showConfirmReplaceAll) {
        AlertDialog(
            onDismissRequest = { showConfirmReplaceAll = false },
            title = { Text(if (knOnly) "ಎಲ್ಲಾ ಪದಗಳನ್ನು ಬದಲಿಸಬೇಕೇ?" else "Replace All Matches?") },
            text = {
                Text(
                    text = if (knOnly) {
                        "\"$findQuery\" ನ ಎಲ್ಲಾ $totalMatches ಸ್ಥಳಗಳನ್ನು \"$replaceQuery\" ಗೆ ಬದಲಿಸಲಾಗುತ್ತದೆ."
                    } else {
                        "Replace all $totalMatches occurrences of \"$findQuery\" with \"$replaceQuery\"?"
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                Button(onClick = {
                    showConfirmReplaceAll = false
                    execJs(
                        "window.KannadaDocEditor.replaceAll(" +
                            "${JSONObject.quote(findQuery)}, " +
                            "${JSONObject.quote(replaceQuery)});"
                    )
                }) {
                    Text(if (knOnly) "ಎಲ್ಲಾ ಬದಲಿಸಿ" else "Replace All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmReplaceAll = false }) {
                    Text(if (knOnly) "ರದ್ದು" else "Cancel")
                }
            }
        )
    }

    // Settings Dialog (Theme, Default Save Format, Auto-Save Interval, Text Size, Set as Default App)
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text(if (knOnly) "ಸೆಟ್ಟಿಂಗ್‌ಗಳು" else "Settings • ಸೆಟ್ಟಿಂಗ್‌ಗಳು") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = if (knOnly) "ಥೀಮ್ (ಬೆಳಕು / ಕತ್ತಲೆ)" else "Theme • ಥೀಮ್",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = userSettings.themeMode == AppThemeMode.LIGHT,
                            onClick = { onThemeModeChange(AppThemeMode.LIGHT) },
                            label = { Text(if (knOnly) "ಬೆಳಕು" else "Light") }
                        )
                        FilterChip(
                            selected = userSettings.themeMode == AppThemeMode.DARK,
                            onClick = { onThemeModeChange(AppThemeMode.DARK) },
                            label = { Text(if (knOnly) "ಕತ್ತಲೆ" else "Dark") }
                        )
                    }

                    HorizontalDivider()

                    Text(
                        text = if (knOnly) "ಉಳಿಸುವ ವಿಧಾನ" else "Default Save Format • ಉಳಿಸುವ ವಿಧಾನ",
                        style = MaterialTheme.typography.titleMedium
                    )
                    DefaultSaveFormat.entries.forEach { format ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSaveFormatChange(format) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = userSettings.defaultSaveFormat == format,
                                onClick = { onSaveFormatChange(format) }
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (knOnly) format.displayNameKn else format.displayName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = if (knOnly) format.subtitleKn else format.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    Text(
                        text = if (knOnly) {
                            "ಸ್ವಯಂ ಉಳಿಸುವ ಸಮಯ: ${userSettings.autoSaveIntervalMinutes} ನಿಮಿಷ"
                        } else {
                            "Auto-Save Interval: ${userSettings.autoSaveIntervalMinutes} min"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppPreferences.AUTO_SAVE_OPTIONS_MINUTES.forEach { mins ->
                            FilterChip(
                                selected = userSettings.autoSaveIntervalMinutes == mins,
                                onClick = { onAutoSaveIntervalChange(mins) },
                                label = { Text(if (knOnly) "$mins ನಿ" else "${mins}m") }
                            )
                        }
                    }

                    HorizontalDivider()

                    Column {
                        Text(
                            text = if (knOnly) "ಅಕ್ಷರದ ಗಾತ್ರ: ${userSettings.defaultFontSizePt} pt"
                            else "Default Text Size: ${userSettings.defaultFontSizePt} pt",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Slider(
                            value = userSettings.defaultFontSizePt.toFloat(),
                            onValueChange = { onDefaultFontSizeChange(it.toInt()) },
                            valueRange = 14f..28f,
                            steps = 6
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (knOnly) "ಡೀಫಾಲ್ಟ್ ಆಪ್ ಆಗಿ ಹೊಂದಿಸಿ" else "Set as Default App")
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showSettingsDialog = false }) {
                    Text(if (knOnly) "ಮುಗಿಯಿತು" else "Done")
                }
            }
        )
    }

    // Document Information Dialog
    if (showDocInfoDialog) {
        val info = uiState.documentInfo
        AlertDialog(
            onDismissRequest = { showDocInfoDialog = false },
            title = {
                Text(if (knOnly) "ದಾಖಲೆ ಮಾಹಿತಿ" else "Document Information • ದಾಖಲೆ ಮಾಹಿತಿ")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoRow(if (knOnly) "ಫೈಲ್ ಹೆಸರು" else "File Name", info.fileName.ifBlank { uiState.documentTitle })
                    InfoRow(if (knOnly) "ಫೈಲ್ ಪ್ರಕಾರ" else "File Type", "${info.fileFormat.name} (${info.fileFormat.badgeLabel})")
                    InfoRow(if (knOnly) "ಪತ್ತೆಯಾದ ಫಾಂಟ್ / ಫಾರ್ಮ್ಯಾಟ್" else "Detected Font / Format", info.detectedEncodingLabel)
                    if (info.fontFamiliesFound.isNotEmpty()) {
                        InfoRow(if (knOnly) "ಫೈಲ್‌ನಲ್ಲಿರುವ ಫಾಂಟ್‌ಗಳು" else "Fonts Found in File", info.fontFamiliesFound.joinToString(", "))
                    }
                    InfoRow(if (knOnly) "ಸಕ್ರಿಯ ಪರಿವರ್ತಕ" else "Active Converter", uiState.activeConverter.uppercase())
                    HorizontalDivider()
                    InfoRow(
                        if (knOnly) "ಉಳಿಸುವ ವಿಧಾನ" else "Default Save Format",
                        if (knOnly) userSettings.defaultSaveFormat.displayNameKn else userSettings.defaultSaveFormat.displayName
                    )
                    InfoRow(
                        if (knOnly) "ಸ್ವಯಂ ಉಳಿಸುವ ಸಮಯ" else "Auto-Save Interval",
                        "${userSettings.autoSaveIntervalMinutes} min"
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showDocInfoDialog = false }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun FormatButton(
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp)
    ) {
        Icon(icon, contentDescription = description)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EditorWebViewHost(
    onWebViewCreated: (WebView) -> Unit,
    onEditorReady: () -> Unit,
    onDocumentLoaded: (Int, String, Boolean) -> Unit,
    onContentDirty: () -> Unit,
    onDraftChanged: (String, String) -> Unit,
    onExportReady: (String, String, Boolean) -> Unit,
    onSearchResults: (Int, Int) -> Unit,
    onReplaceDone: (Int) -> Unit,
    onEditorError: (String) -> Unit
) {
    val context = LocalContext.current
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val assetLoader = remember(context) {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    builtInZoomControls = false
                    displayZoomControls = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }

                val bridge = object {
                    @JavascriptInterface
                    fun onEditorReady() {
                        post { onEditorReady.invoke() }
                    }

                    @JavascriptInterface
                    fun onDocumentLoaded(convertedCount: Int, detectedSummary: String, restoredFromDraft: Boolean) {
                        post { onDocumentLoaded(convertedCount, detectedSummary, restoredFromDraft) }
                    }

                    @JavascriptInterface
                    fun onContentDirty() {
                        post { onContentDirty.invoke() }
                    }

                    @JavascriptInterface
                    fun onDraftChanged(html: String, idmJson: String) {
                        post { onDraftChanged(html, idmJson) }
                    }

                    @JavascriptInterface
                    fun onExportReady(exportMode: String, idmJson: String, isAutoSave: Boolean) {
                        post { onExportReady(exportMode, idmJson, isAutoSave) }
                    }

                    @JavascriptInterface
                    fun onSearchResults(currentIndex: Int, totalCount: Int) {
                        post { onSearchResults(currentIndex, totalCount) }
                    }

                    @JavascriptInterface
                    fun onReplaceDone(replacedCount: Int) {
                        post { onReplaceDone(replacedCount) }
                    }

                    @JavascriptInterface
                    fun onEditorError(errorMsg: String) {
                        post { onEditorError(errorMsg) }
                    }
                }

                addJavascriptInterface(bridge, "AndroidBridge")

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
                }

                onWebViewCreated(this)
                loadUrl("https://appassets.androidplatform.net/assets/editor/index.html")
            }
        }
    )
}
