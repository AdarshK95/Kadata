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
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
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
import com.kannadadoc.ui.common.AboutKadataDialog
import com.kannadadoc.data.local.RevisionEntity
import com.kannadadoc.data.storage.FileRenamePolicy
import com.kannadadoc.ui.common.CreateDocumentRequest
import com.kannadadoc.ui.common.CreateDocumentWithMimeContract
import kotlinx.coroutines.flow.SharedFlow
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.kannadadoc.ui.common.TwoStepFormatExportDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private enum class PendingDirtyAction {
    SHARE_AS_IS,
    SHARE_DIFFERENT_FORMAT,
    CHANGE_DOCUMENT_TYPE
}

private data class DirtyActionPromptCopy(
    val titleEn: String,
    val titleKn: String,
    val bodyEn: String,
    val bodyKn: String,
    val btnEn: String,
    val btnKn: String,
    val savingEn: String,
    val savingKn: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    userSettings: UserSettings,
    memoryPressureEvents: SharedFlow<Int>,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onSaveFormatChange: (DefaultSaveFormat) -> Unit,
    onAutoSaveIntervalChange: (Int) -> Unit,
    onBreakReminderChange: (Int) -> Unit,
    onBlockAutoKeyboardChange: (Boolean) -> Unit,
    onDefaultFontSizeChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val isSystemKeyboardOpen = WindowInsets.ime.getBottom(density) > 0
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val knOnly = userSettings.isKannadaUi
    val isDarkTheme = userSettings.resolveDarkTheme(isSystemInDarkTheme())

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isWebReady by remember { mutableStateOf(false) }
    var lastInjectedKey by remember { mutableStateOf<String?>(null) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showDocInfoDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameDraftText by remember { mutableStateOf("") }
    var showChangeDocTypeDialog by remember { mutableStateOf(false) }
    var pendingConvertTarget by remember { mutableStateOf<KannadaEncodingChoice?>(null) }
    var showKannadaKeyboard by remember { mutableStateOf(false) }
    var zoomPercent by remember { mutableIntStateOf(100) }
    var keyboardAnchor by remember { mutableStateOf(KeyboardAnchor.BOTTOM_LEFT) }

    // Two-step export / share different format dialog state
    var pendingExportAction by remember { mutableStateOf<ExportAction?>(null) }
    var chosenEncoding by remember { mutableStateOf<KannadaEncodingChoice?>(null) }

    // Mutation confirmation dialog states
    var showConfirmReplaceAll by remember { mutableStateOf(false) }
    var showUnsavedExitDialog by remember { mutableStateOf(false) }
    var exitAfterSavePending by remember { mutableStateOf(false) }
    var pendingActionAfterSave by remember { mutableStateOf<PendingDirtyAction?>(null) }
    var pendingDirtyActionPrompt by remember { mutableStateOf<PendingDirtyAction?>(null) }
    var showExternalReadOnlySaveDialog by remember { mutableStateOf(false) }
    var showVersionsDialog by remember { mutableStateOf(false) }
    var versionSaveNameDraft by remember { mutableStateOf("") }
    var webViewInstanceGeneration by remember { mutableIntStateOf(0) }
    var webViewCrashCount by remember { mutableIntStateOf(0) }

    // Find & Replace UI state
    var showFindBar by remember { mutableStateOf(false) }
    val findFocusRequester = remember { FocusRequester() }
    var showReplaceRow by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var currentMatchIdx by remember { mutableIntStateOf(0) }
    var totalMatches by remember { mutableIntStateOf(0) }

    // Unified SAF CreateDocument launcher supporting all 9 export formats & MIME types
    val createDocumentLauncher = rememberLauncherForActivityResult(
        CreateDocumentWithMimeContract()
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
    LaunchedEffect(isWebReady, isDarkTheme) {
        if (isWebReady) {
            webViewRef?.evaluateJavascript(
                "window.KannadaDocEditor && window.KannadaDocEditor.setDarkTheme($isDarkTheme);",
                null
            )
        }
    }

    // Sync optional Manual Keyboard Block mode to WebView
    LaunchedEffect(isWebReady, userSettings.blockAutoKeyboard) {
        if (isWebReady) {
            webViewRef?.evaluateJavascript(
                "window.KannadaDocEditor && window.KannadaDocEditor.setManualKeyboardBlock(${userSettings.blockAutoKeyboard});",
                null
            )
        }
    }

    // Auto-focus & open keyboard when Find & Replace bar opens (unless Manual Block Mode is ON)
    LaunchedEffect(showFindBar) {
        if (showFindBar) {
            delay(120)
            if (!userSettings.blockAutoKeyboard) {
                runCatching { findFocusRequester.requestFocus() }
                softwareKeyboardController?.show()
            } else {
                softwareKeyboardController?.hide()
            }
        }
    }

    val latestIsDirty by rememberUpdatedState(uiState.isDirty)
    val latestIsSaving by rememberUpdatedState(uiState.isSaving)
    val latestIsExternalReadOnly by rememberUpdatedState(uiState.isExternalReadOnly)
    val latestDefaultSaveMode by rememberUpdatedState(userSettings.defaultSaveFormat.jsExportMode)

    // System Back & Top-Bar Back share the exact same unsaved-changes guard
    fun requestNavigateBack() {
        if (latestIsSaving) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    if (knOnly) "ಉಳಿಸಲಾಗುತ್ತಿದೆ, ದಯವಿಟ್ಟು ನಿರೀಕ್ಷಿಸಿ..." else "Saving in progress, please wait..."
                )
            }
            return
        }
        if (latestIsDirty && !latestIsExternalReadOnly) {
            showUnsavedExitDialog = true
            return
        }
        webViewRef?.evaluateJavascript("window.KannadaDocEditor && window.KannadaDocEditor.setVirtualKeyboardMode(false, false, 0);", null)
        webViewRef?.evaluateJavascript("window.KannadaDocEditor && window.KannadaDocEditor.clearSearch();", null)
        onBack()
    }

    BackHandler(enabled = true) {
        requestNavigateBack()
    }

    // Sync External Read-Only lock state to WebView
    LaunchedEffect(isWebReady, uiState.isExternalReadOnly) {
        if (isWebReady) {
            webViewRef?.evaluateJavascript(
                "window.KannadaDocEditor && window.KannadaDocEditor.setExternalReadOnly(${uiState.isExternalReadOnly});",
                null
            )
        }
    }

    // Notify WebView whenever a canonical save succeeds so JS resets dirty tracking cleanly
    LaunchedEffect(isWebReady, uiState.saveGeneration) {
        if (isWebReady && uiState.saveGeneration > 0) {
            webViewRef?.evaluateJavascript(
                "window.KannadaDocEditor && window.KannadaDocEditor.markSavedClean();",
                null
            )
        }
    }

    // Sliding Time-Based Auto-Save (default 5 minutes; resets on every saveGeneration!)
    LaunchedEffect(
        isWebReady,
        uiState.isDirty,
        uiState.isOriginalView,
        uiState.isExternalReadOnly,
        uiState.saveGeneration,
        userSettings.autoSaveIntervalMinutes,
        userSettings.defaultSaveFormat
    ) {
        if (isWebReady && uiState.isDirty && !uiState.isOriginalView && !uiState.isExternalReadOnly) {
            val intervalMs = userSettings.autoSaveIntervalMinutes.coerceAtLeast(1) * 60_000L
            delay(intervalMs)
            webViewRef?.evaluateJavascript(
                "window.KannadaDocEditor && window.KannadaDocEditor.requestExport('${userSettings.defaultSaveFormat.jsExportMode}', true);",
                null
            )
        }
    }

    // Change-Magnitude Auto-Save (>= 500 cumulative chars or >= 300-char paste -> wait 5s idle -> save & reset timer)
    LaunchedEffect(uiState.magnitudeAutoSaveSignal) {
        if (uiState.magnitudeAutoSaveSignal > 0 && isWebReady && uiState.isDirty && !uiState.isOriginalView && !uiState.isExternalReadOnly) {
            delay(5_000L)
            if (latestIsDirty && !latestIsSaving && !latestIsExternalReadOnly) {
                webViewRef?.evaluateJavascript(
                    "window.KannadaDocEditor && window.KannadaDocEditor.requestExport('$latestDefaultSaveMode', true);",
                    null
                )
            }
        }
    }


    // OS Memory Pressure Hook: flush AutoRecover checkpoint & trim JS undo stack immediately
    LaunchedEffect(memoryPressureEvents, isWebReady) {
        memoryPressureEvents.collect {
            if (isWebReady) {
                if (latestIsDirty && !latestIsExternalReadOnly) {
                    webViewRef?.evaluateJavascript("window.KannadaDocEditor && window.KannadaDocEditor.flushDraft(true);", null)
                }
                webViewRef?.evaluateJavascript("window.KannadaDocEditor && window.KannadaDocEditor.trimHistoryForMemoryPressure();", null)
            }
        }
    }

    // Background / Lock-Screen Protection: ONLY flush & auto-save when actually dirty
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if ((event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) &&
                latestIsDirty && !latestIsExternalReadOnly
            ) {
                webViewRef?.evaluateJavascript("window.KannadaDocEditor && window.KannadaDocEditor.flushDraft(true);", null)
                if (event == Lifecycle.Event.ON_STOP && !latestIsSaving) {
                    webViewRef?.evaluateJavascript(
                        "window.KannadaDocEditor && window.KannadaDocEditor.requestExport('$latestDefaultSaveMode', true);",
                        null
                    )
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Document Injection: uses direct JS call for normal docs, and local asset URL streaming for >256 KB docs (OOM-safe)
    LaunchedEffect(isWebReady, uiState.docPayloadGeneration, webViewInstanceGeneration) {
        val json = uiState.parsedDocJson
        val docKey = "${uiState.docPayloadGeneration}:$webViewInstanceGeneration"
        if (isWebReady && json != null && lastInjectedKey != docKey) {
            lastInjectedKey = docKey
            val safePasteLimit = viewModel.computeSafeMaxPasteChars()
            val jsSummaryArg = JSONObject.quote(uiState.initialDetectedSummary)
            val initSettingsScript =
                "window.KannadaDocEditor.setDarkTheme($isDarkTheme);" +
                    "window.KannadaDocEditor.setManualKeyboardBlock(${userSettings.blockAutoKeyboard});" +
                    "window.KannadaDocEditor.setFontSize(${uiState.fontSizePt});" +
                    "window.KannadaDocEditor.setExternalReadOnly(${uiState.isExternalReadOnly});" +
                    "window.KannadaDocEditor.setMaxPasteChars($safePasteLimit);"

            if (uiState.useUrlPayloadTransfer && viewModel.getPendingWebPayloadBytes() != null) {
                val payloadUrl = "https://appassets.androidplatform.net/kadata-doc/payload.json?gen=${uiState.docPayloadGeneration}"
                webViewRef?.evaluateJavascript(
                    initSettingsScript +
                        "window.KannadaDocEditor.loadDocumentFromUrl(${JSONObject.quote(payloadUrl)}, ${uiState.initialConvertedRuns}, $jsSummaryArg, ${uiState.restoredFromRecovery});",
                    null
                )
            } else {
                val jsUnicodeArg = JSONObject.quote(json)
                val jsOriginalArg = uiState.originalDocJson?.let { JSONObject.quote(it) } ?: "null"
                webViewRef?.evaluateJavascript(
                    initSettingsScript +
                        "window.KannadaDocEditor.loadDocument($jsUnicodeArg, $jsOriginalArg, '', ${uiState.initialConvertedRuns}, $jsSummaryArg);",
                    null
                )
            }
        }
    }

    fun execJs(script: String) {
        webViewRef?.evaluateJavascript(script, null)
    }

    fun closeFindBar() {
        showFindBar = false
        showReplaceRow = false
        findQuery = ""
        currentMatchIdx = 0
        totalMatches = 0
        execJs("window.KannadaDocEditor && window.KannadaDocEditor.clearSearch();")
    }

    val savedGreen = if (isDarkTheme) Color(0xFF66BB6A) else Color(0xFF2E7D32)
    val unsavedGrey = if (isDarkTheme) Color(0xFF8E968E) else Color(0xFF9E9E9E)

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = if (showKannadaKeyboard) 0.dp else 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
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
                        onClick = { requestNavigateBack() },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(22.dp))
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiState.documentTitle.ifBlank { "Untitled" },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        val canRenameCurrent = FileRenamePolicy.isKadataOwnedRenamableUri(
                            uiState.uriString,
                            context.packageName
                        ) && !uiState.isExternalReadOnly && !uiState.isLoading && uiState.loadErrorMessage == null
                        if (canRenameCurrent) {
                            IconButton(
                                onClick = {
                                    renameDraftText = uiState.documentTitle.ifBlank { "Untitled.docx" }
                                    showRenameDialog = true
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = if (knOnly) "ಹೆಸರು ಬದಲಿಸಿ" else "Rename file",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                    // Icon-only Save button (green when saved/clean, grey when edited/dirty)
                    // + green tick mark shown for 2.5 seconds after manual or auto-save
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                if (uiState.isExternalReadOnly) {
                                    showExternalReadOnlySaveDialog = true
                                } else {
                                    execJs(
                                        "window.KannadaDocEditor.requestExport('${userSettings.defaultSaveFormat.jsExportMode}', false);"
                                    )
                                }
                            },
                            enabled = !uiState.isSaving && !uiState.isOriginalView && uiState.loadErrorMessage == null
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
                        val compactMenuPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)
                        val compactItemModifier = Modifier.heightIn(min = 40.dp)
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                            containerColor = if (isDarkTheme) Color.Black else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                            shadowElevation = 8.dp
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (knOnly) "ಹುಡುಕಿ / ಬದಲಿಸಿ" else "Find & Replace • ಹುಡುಕಿ / ಬದಲಿಸಿ",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.FindReplace, null, modifier = Modifier.size(20.dp)) },
                                contentPadding = compactMenuPadding,
                                modifier = compactItemModifier,
                                onClick = {
                                    showOverflowMenu = false
                                    showFindBar = true
                                    showReplaceRow = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (knOnly) "ಹಂಚಿಕೊಳ್ಳಿ" else "Share • ಹಂಚಿಕೊಳ್ಳಿ",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.Share, null, modifier = Modifier.size(20.dp)) },
                                contentPadding = compactMenuPadding,
                                modifier = compactItemModifier,
                                onClick = {
                                    showOverflowMenu = false
                                    if (uiState.isDirty && !uiState.isExternalReadOnly && !uiState.isOriginalView) {
                                        pendingDirtyActionPrompt = PendingDirtyAction.SHARE_AS_IS
                                    } else {
                                        viewModel.shareExistingFileAsIs()
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (knOnly) "ಬೇರೆ ಫಾರ್ಮ್ಯಾಟ್ ಹಂಚಿಕೊಳ್ಳಿ" else "Share Different Format",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.Share, null, modifier = Modifier.size(20.dp)) },
                                contentPadding = compactMenuPadding,
                                modifier = compactItemModifier,
                                onClick = {
                                    showOverflowMenu = false
                                    if (uiState.isDirty && !uiState.isExternalReadOnly && !uiState.isOriginalView) {
                                        pendingDirtyActionPrompt = PendingDirtyAction.SHARE_DIFFERENT_FORMAT
                                    } else {
                                        chosenEncoding = null
                                        pendingExportAction = ExportAction.SHARE_DIFFERENT
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (knOnly) "ಪ್ರತಿ ಉಳಿಸಿ" else "Save a Copy • ಪ್ರತಿ ಉಳಿಸಿ",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.Save, null, modifier = Modifier.size(20.dp)) },
                                contentPadding = compactMenuPadding,
                                modifier = compactItemModifier,
                                onClick = {
                                    showOverflowMenu = false
                                    chosenEncoding = null
                                    pendingExportAction = ExportAction.SAVE_COPY
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (knOnly) "ದಾಖಲೆ ಫಾರ್ಮ್ಯಾಟ್ ಬದಲಿಸಿ" else "Change Document Type",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(20.dp)) },
                                contentPadding = compactMenuPadding,
                                modifier = compactItemModifier,
                                onClick = {
                                    showOverflowMenu = false
                                    if (uiState.isDirty && !uiState.isExternalReadOnly && !uiState.isOriginalView) {
                                        pendingDirtyActionPrompt = PendingDirtyAction.CHANGE_DOCUMENT_TYPE
                                    } else {
                                        showChangeDocTypeDialog = true
                                    }
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (uiState.showFormattingStrip) {
                                            if (knOnly) "ಫಾರ್ಮ್ಯಾಟ್ ಪಟ್ಟಿ ಮರೆಮಾಡಿ" else "Hide Formatting Bar"
                                        } else {
                                            if (knOnly) "ಫಾರ್ಮ್ಯಾಟ್ ಪಟ್ಟಿ ತೋರಿಸಿ" else "Show Formatting Bar"
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.FormatSize, null, modifier = Modifier.size(20.dp)) },
                                contentPadding = compactMenuPadding,
                                modifier = compactItemModifier,
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.toggleFormattingStrip()
                                }
                            )
                            if (uiState.initialConvertedRuns > 0 || uiState.isOriginalView) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = if (uiState.isOriginalView) {
                                                if (knOnly) "ತಿದ್ದುಪಡಿ ಮೋಡ್‌ಗೆ ಹಿಂತಿರುಗಿ" else "Back to Editable Kannada"
                                            } else {
                                                if (knOnly) "ಪರಿವರ್ತನೆ ಪೂರ್ವ ಮೂಲ ಪಠ್ಯ (ಓದಲು ಮಾತ್ರ)" else "View Raw Pre-Conversion (Read-Only)"
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Default.Visibility, null, modifier = Modifier.size(20.dp)) },
                                    contentPadding = compactMenuPadding,
                                    modifier = compactItemModifier,
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
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (knOnly) "ಫೈಲ್ ಆವೃತ್ತಿಗಳು" else "File Versions • ಆವೃತ್ತಿಗಳು",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.History, null, modifier = Modifier.size(20.dp)) },
                                contentPadding = compactMenuPadding,
                                modifier = compactItemModifier,
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.loadDocumentVersions()
                                    showVersionsDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (knOnly) "ಸೆಟ್ಟಿಂಗ್‌ಗಳು" else "Settings • ಸೆಟ್ಟಿಂಗ್‌ಗಳು",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.Settings, null, modifier = Modifier.size(20.dp)) },
                                contentPadding = compactMenuPadding,
                                modifier = compactItemModifier,
                                onClick = {
                                    showOverflowMenu = false
                                    showSettingsDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (knOnly) "ಪ್ರಿಂಟ್ ಮಾಡಿ" else "Print Document",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.Print, null, modifier = Modifier.size(20.dp)) },
                                contentPadding = compactMenuPadding,
                                modifier = compactItemModifier,
                                onClick = {
                                    showOverflowMenu = false
                                    webViewRef?.let { wv ->
                                        ShareHelper.printWebView(context, wv, uiState.documentTitle)
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (knOnly) "ಆಪ್ ಬಗ್ಗೆ" else "About App • ಆಪ್ ಬಗ್ಗೆ",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.Info, null, modifier = Modifier.size(20.dp)) },
                                contentPadding = compactMenuPadding,
                                modifier = compactItemModifier,
                                onClick = {
                                    showOverflowMenu = false
                                    showAboutDialog = true
                                }
                            )
                            HorizontalDivider()
                            // Document Information stays cleanly visible at the bottom with compact height
                            DropdownMenuItem(
                                text = {
                                    Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                        Text(
                                            text = if (knOnly) "ದಾಖಲೆ ಮಾಹಿತಿ" else "Document Info • ದಾಖಲೆ ಮಾಹಿತಿ",
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
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
                                leadingIcon = { Icon(Icons.Default.Info, null, modifier = Modifier.size(20.dp)) },
                                contentPadding = compactMenuPadding,
                                modifier = Modifier.heightIn(min = 46.dp),
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
        val latestOnExportReady by rememberUpdatedState({ mode: String, idmJson: String, isAutoSave: Boolean, charCount: Int ->
            val shouldExitAfter = exitAfterSavePending && !isAutoSave
            val followUpAction = if (!isAutoSave) pendingActionAfterSave else null
            if (!isAutoSave) {
                exitAfterSavePending = false
                pendingActionAfterSave = null
            }
            viewModel.handleExportPayload(
                exportMode = mode,
                idmJson = idmJson,
                isAutoSave = isAutoSave,
                charCount = charCount,
                onRequestSafCreateFile = { req ->
                    createDocumentLauncher.launch(req)
                },
                onSaveCompleteCallback = when {
                    shouldExitAfter -> { { onBack() } }
                    followUpAction == PendingDirtyAction.SHARE_AS_IS -> {
                        { viewModel.shareExistingFileAsIs() }
                    }
                    followUpAction == PendingDirtyAction.SHARE_DIFFERENT_FORMAT -> {
                        {
                            chosenEncoding = null
                            pendingExportAction = ExportAction.SHARE_DIFFERENT
                        }
                    }
                    followUpAction == PendingDirtyAction.CHANGE_DOCUMENT_TYPE -> {
                        { showChangeDocTypeDialog = true }
                    }
                    else -> null
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
                key(webViewInstanceGeneration) {
                    EditorWebViewHost(
                        payloadProvider = viewModel::getPendingWebPayloadBytes,
                        onWebViewCreated = { webViewRef = it },
                        onEditorReady = { isWebReady = true },
                        onDocumentLoaded = viewModel::onWebDocumentLoaded,
                        onContentDirty = viewModel::markDirty,
                        onEditDelta = viewModel::onEditDelta,
                        onCheckpointReady = viewModel::onCheckpointReady,
                        onExportReady = { mode, idmJson, isAutoSave, charCount ->
                            latestOnExportReady(mode, idmJson, isAutoSave, charCount)
                        },
                        onSearchResults = { current, total ->
                            currentMatchIdx = current
                            totalMatches = total
                        },
                        onReplaceDone = { count ->
                            latestOnReplaceDone(count)
                        },
                        onZoomChanged = { pct ->
                            zoomPercent = pct.coerceIn(50, 300)
                        },
                        onEditorWarning = { warn ->
                            viewModel.reportEditorWarning(warn)
                        },
                        onEditorError = { err ->
                            exitAfterSavePending = false
                            pendingActionAfterSave = null
                            viewModel.reportEditorError(err)
                        },
                        onRenderProcessCrashed = {
                            if (webViewCrashCount < 2) {
                                webViewCrashCount++
                                isWebReady = false
                                webViewRef = null
                                webViewInstanceGeneration++
                                viewModel.reloadAfterWebViewCrash()
                            } else {
                                viewModel.reportEditorError(
                                    "The editor ran low on device memory repeatedly. Your latest edits are safely backed up; please reopen Kadata."
                                )
                            }
                        }
                    )
                }
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            val viewportWidth = maxWidth
            val viewportHeight = maxHeight
            val isTabletSideMode = viewportWidth >= 600.dp

            Column(modifier = Modifier.fillMaxSize()) {
            if (uiState.isSaving) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Collapsible Formatting Strip (Table button removed as requested)
            AnimatedVisibility(visible = uiState.showFormattingStrip && !uiState.isOriginalView && !uiState.isExternalReadOnly && !showKannadaKeyboard) {
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
                                showReplaceRow = false
                            }
                        }
                        FormatButton("Undo", Icons.AutoMirrored.Filled.Undo) {
                            execJs("window.KannadaDocEditor.execFormat('undo');")
                        }
                        FormatButton("Redo", Icons.AutoMirrored.Filled.Redo) {
                            execJs("window.KannadaDocEditor.execFormat('redo');")
                        }
                        // Always-accessible Document Zoom (- | 100% | +) for ALL file formats
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        val next = (zoomPercent - 15).coerceIn(50, 300)
                                        zoomPercent = next
                                        execJs("window.KannadaDocEditor && window.KannadaDocEditor.setZoomPercent($next);")
                                    },
                                    enabled = zoomPercent > 50,
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out", modifier = Modifier.size(18.dp))
                                }
                                Text(
                                    text = "$zoomPercent%",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (zoomPercent != 100) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            zoomPercent = 100
                                            execJs("window.KannadaDocEditor && window.KannadaDocEditor.setZoomPercent(100);")
                                        }
                                        .padding(horizontal = 4.dp, vertical = 4.dp)
                                )
                                IconButton(
                                    onClick = {
                                        val next = (zoomPercent + 15).coerceIn(50, 300)
                                        zoomPercent = next
                                        execJs("window.KannadaDocEditor && window.KannadaDocEditor.setZoomPercent($next);")
                                    },
                                    enabled = zoomPercent < 300,
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in", modifier = Modifier.size(18.dp))
                                }
                            }
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
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(findFocusRequester)
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

            // External Service Read-Only Banner (WhatsApp / Gmail / Telegram / Drive) with 1-tap Edit -> Save First
            AnimatedVisibility(visible = uiState.isExternalReadOnly && !uiState.isLoading && uiState.loadErrorMessage == null) {
                val srcName = uiState.sourceAppLabel ?: "External App"
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (knOnly) {
                                "$srcName ನಿಂದ ತೆರೆಯಲಾಗಿದೆ (ಓದಲು ಮಾತ್ರ). ತಿದ್ದುಪಡಿ ಮಾಡಲು ಮೊದಲು ಉಳಿಸಿ."
                            } else {
                                "Opened from $srcName (Read-Only) • Save to Kadata Documents to edit."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { showExternalReadOnlySaveDialog = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (knOnly) "ತಿದ್ದುಪಡಿ" else "Edit", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
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

                val keyboardVisible = showKannadaKeyboard && !uiState.isLoading && !uiState.isOriginalView && !uiState.isExternalReadOnly && uiState.loadErrorMessage == null

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

                        if (!uiState.isLoading && !uiState.isOriginalView && !uiState.isExternalReadOnly && !showKannadaKeyboard && uiState.loadErrorMessage == null) {
                            FloatingKeyboardActionButtons(
                                anchor = keyboardAnchor,
                                isSystemKeyboardOpen = isSystemKeyboardOpen,
                                onAnchorChange = { keyboardAnchor = it },
                                onOpenKannadaKeyboard = {
                                    showKannadaKeyboard = true
                                    softwareKeyboardController?.hide()
                                    webViewRef?.let { wv ->
                                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                        imm?.hideSoftInputFromWindow(wv.windowToken, 0)
                                    }
                                    execJs("window.KannadaDocEditor && window.KannadaDocEditor.setVirtualKeyboardMode(true, $isTabletSideMode, 0);")
                                },
                                onToggleSystemKeyboard = {
                                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                    if (isSystemKeyboardOpen) {
                                        // Tap again -> Close the default OS keyboard cleanly
                                        softwareKeyboardController?.hide()
                                        webViewRef?.let { wv ->
                                            imm?.hideSoftInputFromWindow(wv.windowToken, 0)
                                            wv.clearFocus()
                                        }
                                        execJs("window.KannadaDocEditor && window.KannadaDocEditor.dismissSystemKeyboard();")
                                    } else {
                                        // Open the default OS keyboard at the cursor
                                        showKannadaKeyboard = false
                                        execJs("window.KannadaDocEditor && window.KannadaDocEditor.focusForSystemKeyboard();")
                                        webViewRef?.let { wv ->
                                            wv.requestFocus()
                                            wv.postDelayed({
                                                imm?.showSoftInput(wv, InputMethodManager.SHOW_IMPLICIT)
                                            }, 90L)
                                        }
                                        softwareKeyboardController?.show()
                                    }
                                },
                                onPickSystemKeyboard = {
                                    // Long-press -> Show Android's system Input Method Picker (switch keyboards)
                                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                    imm?.showInputMethodPicker()
                                }
                            )
                        }

                        // Floating Zoom Pill shown in Read-Only Original View, when Formatting Bar is hidden,
                        // or whenever zoomed (gives quick 1-tap zoom/reset in every view without blocking keyboard buttons)
                        val showFloatingZoomPill = !uiState.isLoading && !showKannadaKeyboard && uiState.loadErrorMessage == null && (
                            uiState.isOriginalView || !uiState.showFormattingStrip || zoomPercent != 100
                        )
                        if (showFloatingZoomPill) {
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                tonalElevation = 4.dp,
                                shadowElevation = 6.dp,
                                border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            val next = (zoomPercent - 15).coerceIn(50, 300)
                                            zoomPercent = next
                                            execJs("window.KannadaDocEditor && window.KannadaDocEditor.setZoomPercent($next);")
                                        },
                                        enabled = zoomPercent > 50,
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out", modifier = Modifier.size(18.dp))
                                    }
                                    Text(
                                        text = "$zoomPercent%",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (zoomPercent != 100) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable {
                                                zoomPercent = 100
                                                execJs("window.KannadaDocEditor && window.KannadaDocEditor.setZoomPercent(100);")
                                            }
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                    IconButton(
                                        onClick = {
                                            val next = (zoomPercent + 15).coerceIn(50, 300)
                                            zoomPercent = next
                                            execJs("window.KannadaDocEditor && window.KannadaDocEditor.setZoomPercent($next);")
                                        },
                                        enabled = zoomPercent < 300,
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }

                        if (uiState.loadErrorMessage != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.background,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
                                        tonalElevation = 4.dp,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .padding(20.dp)
                                                .verticalScroll(rememberScrollState()),
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Info,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(36.dp)
                                            )
                                            Text(
                                                text = uiState.loadErrorTitle ?: "Unable to Open Document",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                            Text(
                                                text = uiState.loadErrorMessage ?: "",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Button(onClick = onBack) {
                                                Text(if (knOnly) "ಮುಖಪುಟಕ್ಕೆ ಹಿಂತಿರುಗಿ" else "Back to Home • ಮುಖಪುಟಕ್ಕೆ ಹಿಂತಿರುಗಿ")
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (uiState.isLoading) {
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

    // Two-Step Dialog for "Save a Copy" and "Share Different Format" (shared bilingual format cards)
    pendingExportAction?.let { action ->
        TwoStepFormatExportDialog(
            action = action,
            chosenEncoding = chosenEncoding,
            knOnly = knOnly,
            onSelectEncoding = { chosenEncoding = it },
            onBackToEncoding = { chosenEncoding = null },
            onSelectFileType = { selectedEnc, fileType ->
                val prefix = if (action == ExportAction.SHARE_DIFFERENT) "share" else "savecopy"
                val mode = "${prefix}_${selectedEnc.id}_${fileType.id}"
                chosenEncoding = null
                pendingExportAction = null
                execJs("window.KannadaDocEditor.requestExport('$mode', false);")
            },
            onDismiss = {
                chosenEncoding = null
                pendingExportAction = null
            }
        )
    }

    // Rename Current File Dialog (triggered from the Edit icon next to filename in top bar)
    if (showRenameDialog) {
        val renameFocusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            if (!userSettings.blockAutoKeyboard) {
                delay(140)
                runCatching { renameFocusRequester.requestFocus() }
                softwareKeyboardController?.show()
            }
        }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(if (knOnly) "ಹೆಸರು ಬದಲಿಸಿ" else "Rename Document • ಹೆಸರು ಬದಲಿಸಿ") },
            text = {
                OutlinedTextField(
                    value = renameDraftText,
                    onValueChange = { renameDraftText = it },
                    label = { Text(if (knOnly) "ಹೊಸ ಹೆಸರು" else "New Name • ಹೊಸ ಹೆಸರು") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(renameFocusRequester)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updated = renameDraftText.trim()
                        if (updated.isNotEmpty()) {
                            viewModel.renameCurrentDocument(updated)
                        }
                        showRenameDialog = false
                    },
                    enabled = renameDraftText.isNotBlank()
                ) {
                    Text(if (knOnly) "ಬದಲಿಸಿ" else "Rename • ಬದಲಿಸಿ")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(if (knOnly) "ರದ್ದು" else "Cancel • ರದ್ದು")
                }
            }
        )
    }

    // Confirmation Dialog for Replace All (irreversible bulk mutation)
    if (showConfirmReplaceAll) {
        AlertDialog(
            onDismissRequest = { showConfirmReplaceAll = false },
            title = {
                Text(if (knOnly) "ಎಲ್ಲಾ ಪದಗಳನ್ನು ಬದಲಿಸಬೇಕೇ?" else "Replace All Matches? • ಎಲ್ಲಾ ಪದಗಳನ್ನು ಬದಲಿಸಬೇಕೇ?")
            },
            text = {
                val knText = "\"$findQuery\" ನ ಎಲ್ಲಾ $totalMatches ಸ್ಥಳಗಳನ್ನು \"$replaceQuery\" ಗೆ ಬದಲಿಸಲಾಗುತ್ತದೆ."
                val enText = "Replace all $totalMatches occurrences of \"$findQuery\" with \"$replaceQuery\"?"
                Text(
                    text = if (knOnly) knText else "$enText\n\n$knText",
                    style = MaterialTheme.typography.bodyMedium
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
                    Text(if (knOnly) "ಎಲ್ಲಾ ಬದಲಿಸಿ" else "Replace All • ಎಲ್ಲಾ ಬದಲಿಸಿ")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmReplaceAll = false }) {
                    Text(if (knOnly) "ರದ್ದು" else "Cancel • ರದ್ದು")
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
                    Surface(
                        onClick = {
                            showSettingsDialog = false
                            showAboutDialog = true
                        },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (knOnly) "ಆಪ್ ಬಗ್ಗೆ (ಕಡತ)" else "About App • ಆಪ್ ಬಗ್ಗೆ",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = if (knOnly) "ಡೆವಲಪರ್: Adarsh Katagihallimath" else "Developer: Adarsh Katagihallimath",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    Text(
                        text = if (knOnly) "ಥೀಮ್ (ಸಿಸ್ಟಮ್ / ಬೆಳಕು / ಕತ್ತಲೆ)" else "Theme • ಥೀಮ್",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = userSettings.themeMode == AppThemeMode.SYSTEM,
                            onClick = { onThemeModeChange(AppThemeMode.SYSTEM) },
                            label = { Text(if (knOnly) "ಸಿಸ್ಟಮ್" else "System") }
                        )
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
                                .clickable { onSaveFormatChange(format) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            RadioButton(
                                selected = userSettings.defaultSaveFormat == format,
                                onClick = { onSaveFormatChange(format) }
                            )
                            Spacer(Modifier.width(4.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = format.bilingualTitle,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = format.bilingualSubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

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

                    Text(
                        text = if (knOnly) {
                            if (userSettings.breakReminderMinutes == 0) "ಆರೋಗ್ಯ ವಿರಾಮ ಸೂಚನೆ: ಆಫ್"
                            else "ಆರೋಗ್ಯ ವಿರಾಮ ಸೂಚನೆ: ${userSettings.breakReminderMinutes} ನಿಮಿಷ"
                        } else {
                            if (userSettings.breakReminderMinutes == 0) "Wellness Break Reminder: Off"
                            else "Wellness Break Reminder: ${userSettings.breakReminderMinutes} min"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppPreferences.BREAK_REMINDER_OPTIONS_MINUTES.forEach { mins ->
                            FilterChip(
                                selected = userSettings.breakReminderMinutes == mins,
                                onClick = { onBreakReminderChange(mins) },
                                label = {
                                    Text(
                                        when {
                                            mins == 0 && knOnly -> "ಆಫ್"
                                            mins == 0 -> "Off"
                                            knOnly -> "$mins ನಿ"
                                            else -> "${mins}m"
                                        }
                                    )
                                }
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

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = if (knOnly) "ಸ್ವಯಂ ಕೀಬೋರ್ಡ್ ನಿರ್ಬಂಧಿಸಿ" else "Block Auto-Opening Keyboards",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = if (knOnly) "ಬಳಕೆದಾರರು ಕೀಬೋರ್ಡ್ ಬಟನ್ ಒತ್ತಿದಾಗ ಮಾತ್ರ ತೆರೆಯುತ್ತದೆ"
                                else "Prevent keyboards from popping up automatically; use the manual trigger buttons instead",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = userSettings.blockAutoKeyboard,
                            onCheckedChange = onBlockAutoKeyboardChange
                        )
                    }

                    HorizontalDivider()

                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (knOnly) "ಆಪ್ ಮಾಹಿತಿ" else "App Info")
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

    // Change Document Type Dialog (disables the format the document is already in, bilingual + compatibility notes)
    if (showChangeDocTypeDialog) {
        val currentEnc = uiState.documentInfo.currentEncoding
        AlertDialog(
            onDismissRequest = { showChangeDocTypeDialog = false },
            title = {
                Text(if (knOnly) "ದಾಖಲೆ ಫಾರ್ಮ್ಯಾಟ್ ಬದಲಿಸಿ" else "Change Document Type • ದಾಖಲೆ ಫಾರ್ಮ್ಯಾಟ್ ಬದಲಿಸಿ")
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Convert this document into another Kannada format • ಈ ದಾಖಲೆಯನ್ನು ಬೇರೆ ಕನ್ನಡ ಫಾರ್ಮ್ಯಾಟ್‌ಗೆ ಪರಿವರ್ತಿಸಿ:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    KannadaEncodingChoice.entries.forEach { enc ->
                        val isAlreadyInFormat = (currentEnc == enc)
                        Surface(
                            onClick = {
                                if (!isAlreadyInFormat) {
                                    showChangeDocTypeDialog = false
                                    pendingConvertTarget = enc
                                }
                            },
                            enabled = !isAlreadyInFormat,
                            shape = MaterialTheme.shapes.medium,
                            color = if (isAlreadyInFormat) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = enc.bilingualTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isAlreadyInFormat) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                Text(
                                    text = enc.bilingualSubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (isAlreadyInFormat) {
                                    Text(
                                        text = "Document is already in this format • ದಾಖಲೆ ಈಗಾಗಲೇ ಈ ಫಾರ್ಮ್ಯಾಟ್‌ನಲ್ಲಿದೆ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
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
                TextButton(onClick = { showChangeDocTypeDialog = false }) {
                    Text(if (knOnly) "ರದ್ದು" else "Cancel")
                }
            }
        )
    }

    // Confirmation Dialog for Change Document Type (mutation confirmation)
    pendingConvertTarget?.let { targetEnc ->
        AlertDialog(
            onDismissRequest = { pendingConvertTarget = null },
            title = {
                Text(
                    if (knOnly) "ಫಾರ್ಮ್ಯಾಟ್ ಬದಲಿಸಬೇಕೇ?"
                    else "Convert to ${targetEnc.labelEn}? • ಫಾರ್ಮ್ಯಾಟ್ ಬದಲಿಸಬೇಕೇ?"
                )
            },
            text = {
                val knText = "ಈ ದಾಖಲೆಯನ್ನು ${targetEnc.labelKn} (.docx) ಫಾರ್ಮ್ಯಾಟ್‌ಗೆ ಪರಿವರ್ತಿಸಿ ಉಳಿಸಲಾಗುತ್ತದೆ ಮತ್ತು ಇತ್ತೀಚಿನ ಫೈಲ್‌ಗಳಲ್ಲಿ ತೋರಿಸಲಾಗುತ್ತದೆ."
                val enText = "Convert and save this document in ${targetEnc.labelEn} (.docx) format? The converted file will also appear in Recent Files."
                Text(
                    text = if (knOnly) knText else "$enText\n\n$knText",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(onClick = {
                    val mode = "convert_doc_${targetEnc.id}"
                    pendingConvertTarget = null
                    execJs("window.KannadaDocEditor.requestExport('$mode', false);")
                }) {
                    Text(if (knOnly) "ಪರಿವರ್ತಿಸಿ" else "Convert • ಪರಿವರ್ತಿಸಿ")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConvertTarget = null }) {
                    Text(if (knOnly) "ರದ್ದು" else "Cancel • ರದ್ದು")
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
                        userSettings.defaultSaveFormat.bilingualTitle
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

    // 1. External Service Read-Only -> Save First to Edit Dialog (WhatsApp / Gmail / Drive)
    if (showExternalReadOnlySaveDialog) {
        val srcName = uiState.sourceAppLabel ?: "External App"
        AlertDialog(
            onDismissRequest = { showExternalReadOnlySaveDialog = false },
            title = {
                Text(
                    if (knOnly) "ತಿದ್ದುಪಡಿ ಮಾಡಲು ಮೊದಲು ಉಳಿಸಿ"
                    else "Save File to Start Editing • ಮೊದಲು ಉಳಿಸಿ"
                )
            },
            text = {
                val knText = "ಈ ದಾಖಲೆ $srcName ನಿಂದ ಬಂದಿದ್ದು ಓದಲು ಮಾತ್ರ ಲಭ್ಯವಿದೆ. ತಿದ್ದುಪಡಿ ಮಾಡಲು ಇದನ್ನು ಮೊದಲು \"Documents/Kadata Documents\" ಫೋಲ್ಡರ್‌ನಲ್ಲಿ ಉಳಿಸಬೇಕು."
                val enText = "This document was opened from $srcName and is read-only. To edit it, Kadata will save an editable copy inside \"Documents/Kadata Documents\" and unlock editing immediately."
                Text(
                    text = if (knOnly) knText else "$enText\n\n$knText",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExternalReadOnlySaveDialog = false
                        viewModel.saveExternalReadOnlyToKadataAndUnlock {
                            execJs("window.KannadaDocEditor && window.KannadaDocEditor.setExternalReadOnly(false);")
                        }
                    },
                    enabled = !uiState.isSaving
                ) {
                    Text(if (knOnly) "ಉಳಿಸಿ ಮತ್ತು ತಿದ್ದುಪಡಿ ಮಾಡಿ" else "Save & Edit • ಉಳಿಸಿ")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExternalReadOnlySaveDialog = false }) {
                    Text(if (knOnly) "ರದ್ದು" else "Cancel • ರದ್ದು")
                }
            }
        )
    }

    // 2. Unsaved Changes Exit Guard Dialog (Back Arrow & System Back)
    if (showUnsavedExitDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedExitDialog = false },
            title = {
                Text(if (knOnly) "ಉಳಿಸದ ಬದಲಾವಣೆಗಳಿವೆ" else "Save Changes Before Closing? • ಉಳಿಸದ ಬದಲಾವಣೆಗಳಿವೆ")
            },
            text = {
                val knText = "\"${uiState.documentTitle}\" ನಲ್ಲಿ ಉಳಿಸದ ಬದಲಾವಣೆಗಳಿವೆ. ಮುಚ್ಚುವ ಮೊದಲು ಉಳಿಸಬೇಕೇ?"
                val enText = "You have unsaved changes in \"${uiState.documentTitle}\". Save before returning to Home?"
                Text(
                    text = if (knOnly) knText else "$enText\n\n$knText",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(onClick = {
                    showUnsavedExitDialog = false
                    exitAfterSavePending = true
                    execJs(
                        "window.KannadaDocEditor.requestExport('${userSettings.defaultSaveFormat.jsExportMode}', false);"
                    )
                }) {
                    Text(if (knOnly) "ಉಳಿಸಿ ಮತ್ತು ಮುಚ್ಚಿ" else "Save & Close • ಉಳಿಸಿ")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        showUnsavedExitDialog = false
                        viewModel.discardUnsavedAndMarkClean()
                        execJs("window.KannadaDocEditor && window.KannadaDocEditor.setVirtualKeyboardMode(false, false, 0);")
                        execJs("window.KannadaDocEditor && window.KannadaDocEditor.clearSearch();")
                        onBack()
                    }) {
                        Text(if (knOnly) "ತ್ಯಜಿಸಿ" else "Discard • ತ್ಯಜಿಸಿ", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { showUnsavedExitDialog = false }) {
                        Text(if (knOnly) "ರದ್ದು" else "Cancel • ರದ್ದು")
                    }
                }
            }
        )
    }

    // 2b. Unified Save-Before-Action Confirmation Dialog (Share, Share Different Format, Change Document Type)
    pendingDirtyActionPrompt?.let { dirtyAction ->
        val copy = when (dirtyAction) {
            PendingDirtyAction.SHARE_AS_IS -> DirtyActionPromptCopy(
                titleEn = "Save Changes Before Sharing?",
                titleKn = "ಹಂಚಿಕೊಳ್ಳುವ ಮೊದಲು ಉಳಿಸಬೇಕೇ?",
                bodyEn = "You have unsaved changes in \"${uiState.documentTitle}\". Save them now so the latest version is shared.",
                bodyKn = "\"${uiState.documentTitle}\" ನಲ್ಲಿ ಉಳಿಸದ ಬದಲಾವಣೆಗಳಿವೆ. ಇತ್ತೀಚಿನ ಆವೃತ್ತಿಯನ್ನು ಹಂಚಿಕೊಳ್ಳಲು ಈಗ ಉಳಿಸಿ ಮತ್ತು ಹಂಚಿಕೊಳ್ಳಿ.",
                btnEn = "Save & Share",
                btnKn = "ಉಳಿಸಿ ಮತ್ತು ಹಂಚಿಕೊಳ್ಳಿ",
                savingEn = "Saving before sharing...",
                savingKn = "ಹಂಚಿಕೊಳ್ಳುವ ಮೊದಲು ಉಳಿಸಲಾಗುತ್ತಿದೆ..."
            )
            PendingDirtyAction.SHARE_DIFFERENT_FORMAT -> DirtyActionPromptCopy(
                titleEn = "Save Changes Before Sharing?",
                titleKn = "ಬೇರೆ ಫಾರ್ಮ್ಯಾಟ್ ಹಂಚಿಕೊಳ್ಳುವ ಮೊದಲು ಉಳಿಸಬೇಕೇ?",
                bodyEn = "You have unsaved changes in \"${uiState.documentTitle}\". Save them to this file first before sharing in a different format.",
                bodyKn = "\"${uiState.documentTitle}\" ನಲ್ಲಿ ಉಳಿಸದ ಬದಲಾವಣೆಗಳಿವೆ. ಬೇರೆ ಫಾರ್ಮ್ಯಾಟ್‌ನಲ್ಲಿ ಹಂಚಿಕೊಳ್ಳುವ ಮೊದಲು ಈ ಫೈಲ್ ಅನ್ನು ಉಳಿಸಿ.",
                btnEn = "Save & Continue",
                btnKn = "ಉಳಿಸಿ ಮತ್ತು ಮುಂದುವರಿಸಿ",
                savingEn = "Saving before sharing...",
                savingKn = "ಹಂಚಿಕೊಳ್ಳುವ ಮೊದಲು ಉಳಿಸಲಾಗುತ್ತಿದೆ..."
            )
            PendingDirtyAction.CHANGE_DOCUMENT_TYPE -> DirtyActionPromptCopy(
                titleEn = "Save Changes Before Converting?",
                titleKn = "ಫಾರ್ಮ್ಯಾಟ್ ಬದಲಿಸುವ ಮೊದಲು ಉಳಿಸಬೇಕೇ?",
                bodyEn = "You have unsaved changes in \"${uiState.documentTitle}\". Save them to the current file first before changing the document type.",
                bodyKn = "\"${uiState.documentTitle}\" ನಲ್ಲಿ ಉಳಿಸದ ಬದಲಾವಣೆಗಳಿವೆ. ದಾಖಲೆ ಫಾರ್ಮ್ಯಾಟ್ ಬದಲಿಸುವ ಮೊದಲು ಪ್ರಸ್ತುತ ಫೈಲ್ ಅನ್ನು ಉಳಿಸಿ.",
                btnEn = "Save & Continue",
                btnKn = "ಉಳಿಸಿ ಮತ್ತು ಮುಂದುವರಿಸಿ",
                savingEn = "Saving before converting...",
                savingKn = "ಪರಿವರ್ತಿಸುವ ಮೊದಲು ಉಳಿಸಲಾಗುತ್ತಿದೆ..."
            )
        }
        AlertDialog(
            onDismissRequest = { pendingDirtyActionPrompt = null },
            title = {
                Text(if (knOnly) copy.titleKn else "${copy.titleEn} • ${copy.titleKn}")
            },
            text = {
                Text(
                    text = if (knOnly) copy.bodyKn else "${copy.bodyEn}\n\n${copy.bodyKn}",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDirtyActionPrompt = null
                        pendingActionAfterSave = dirtyAction
                        viewModel.showStatusMessage(if (knOnly) copy.savingKn else copy.savingEn)
                        execJs(
                            "window.KannadaDocEditor.requestExport('${userSettings.defaultSaveFormat.jsExportMode}', false);"
                        )
                    },
                    enabled = !uiState.isSaving
                ) {
                    Text(if (knOnly) copy.btnKn else "${copy.btnEn} • ${copy.btnKn}")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDirtyActionPrompt = null }) {
                    Text(if (knOnly) "ರದ್ದು" else "Cancel • ರದ್ದು")
                }
            }
        )
    }

    // 3. File Versions List Dialog (3-dot menu -> read-only snapshots)
    if (showVersionsDialog) {
        AlertDialog(
            onDismissRequest = { showVersionsDialog = false },
            title = { Text(if (knOnly) "ಫೈಲ್ ಆವೃತ್ತಿಗಳು" else "File Versions • ಆವೃತ್ತಿಗಳು") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (uiState.isLoadingVersions) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    } else if (uiState.versionsList.isEmpty()) {
                        val knEmpty = "ಇನ್ನೂ ಯಾವುದೇ ಬ್ಯಾಕಪ್ ಆವೃತ್ತಿಗಳು ದಾಖಲಾಗಿಲ್ಲ. ನೀವು ತಿದ್ದುಪಡಿ ಮಾಡಿ ಉಳಿಸಿದಾಗ ಆವೃತ್ತಿಗಳು ಸ್ವಯಂಚಾಲಿತವಾಗಿ ಇಲ್ಲಿ ಸಂಗ್ರಹವಾಗುತ್ತವೆ."
                        val enEmpty = "No saved versions yet for this document. As you edit and save, Kadata automatically keeps recovery versions here."
                        Text(
                            text = if (knOnly) knEmpty else "$enEmpty\n\n$knEmpty",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val fmt = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }
                        uiState.versionsList.forEach { rev: RevisionEntity ->
                            val stamp = fmt.format(Date(rev.createdAt))
                            val deltaStr = when {
                                rev.charDelta > 0 -> "+${rev.charDelta} chars"
                                rev.charDelta < 0 -> "${rev.charDelta} chars"
                                else -> "${rev.charCount} chars"
                            }
                            Surface(
                                onClick = {
                                    showVersionsDialog = false
                                    viewModel.openVersionReadOnlyPreview(rev)
                                },
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = stamp,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${rev.triggerReason}  •  $deltaStr",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVersionsDialog = false }) {
                    Text(if (knOnly) "ಮುಚ್ಚಿ" else "Close")
                }
            }
        )
    }

    // 4. File Version Read-Only Preview + Save as New File Dialog (zero overwrite / zero duplication)
    uiState.versionPreview?.let { preview ->
        LaunchedEffect(preview.revision.id) {
            versionSaveNameDraft = preview.suggestedFileName
        }
        val stamp = remember(preview.revision.createdAt) {
            SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(preview.revision.createdAt))
        }
        AlertDialog(
            onDismissRequest = viewModel::dismissVersionPreview,
            title = {
                Column {
                    Text(if (knOnly) "ಆವೃತ್ತಿ ಮುನ್ನೋಟ (ಓದಲು ಮಾತ್ರ)" else "Version Preview (Read-Only) • ಆವೃತ್ತಿ ಮುನ್ನೋಟ")
                    Text(
                        text = "$stamp • ${preview.revision.triggerReason}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 260.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp)
                        ) {
                            Text(
                                text = preview.previewText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    OutlinedTextField(
                        value = versionSaveNameDraft,
                        onValueChange = { versionSaveNameDraft = it },
                        label = { Text(if (knOnly) "ಹೊಸ ಫೈಲ್ ಹೆಸರು" else "Save as New File Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.savePreviewedVersionAsNewFile(
                            revision = preview.revision,
                            desiredFileName = versionSaveNameDraft.ifBlank { preview.suggestedFileName }
                        )
                    },
                    enabled = versionSaveNameDraft.isNotBlank() && !uiState.isSaving
                ) {
                    Text(if (knOnly) "ಹೊಸ ಫೈಲ್ ಆಗಿ ಉಳಿಸಿ" else "Save as New File")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissVersionPreview) {
                    Text(if (knOnly) "ಮುಚ್ಚಿ" else "Close")
                }
            }
        )
    }

    if (showAboutDialog) {
        AboutKadataDialog(
            knOnly = knOnly,
            onDismiss = { showAboutDialog = false }
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
    payloadProvider: () -> ByteArray?,
    onWebViewCreated: (WebView) -> Unit,
    onEditorReady: () -> Unit,
    onDocumentLoaded: (Int, String, Boolean) -> Unit,
    onContentDirty: () -> Unit,
    onEditDelta: (Int, Boolean, Int) -> Unit,
    onCheckpointReady: (String, Int) -> Unit,
    onExportReady: (String, String, Boolean, Int) -> Unit,
    onSearchResults: (Int, Int) -> Unit,
    onReplaceDone: (Int) -> Unit,
    onZoomChanged: (Int) -> Unit,
    onEditorWarning: (String) -> Unit,
    onEditorError: (String) -> Unit,
    onRenderProcessCrashed: () -> Unit
) {
    val context = LocalContext.current
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
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    useWideViewPort = true
                    loadWithOverviewMode = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }

                val bridge = object {
                    @JavascriptInterface
                    fun onEditorReady() { post { onEditorReady.invoke() } }

                    @JavascriptInterface
                    fun onDocumentLoaded(convertedCount: Int, detectedSummary: String, restoredFromDraft: Boolean) {
                        post { onDocumentLoaded(convertedCount, detectedSummary, restoredFromDraft) }
                    }

                    @JavascriptInterface
                    fun onContentDirty() { post { onContentDirty.invoke() } }

                    @JavascriptInterface
                    fun onEditDelta(deltaChars: Int, isLargePaste: Boolean, currentCharCount: Int) {
                        post { onEditDelta(deltaChars, isLargePaste, currentCharCount) }
                    }

                    @JavascriptInterface
                    fun onCheckpointReady(idmJson: String, charCount: Int) {
                        post { onCheckpointReady(idmJson, charCount) }
                    }

                    @JavascriptInterface
                    fun onExportReady(exportMode: String, idmJson: String, isAutoSave: Boolean, charCount: Int) {
                        post { onExportReady(exportMode, idmJson, isAutoSave, charCount) }
                    }

                    @JavascriptInterface
                    fun onSearchResults(currentIndex: Int, totalCount: Int) {
                        post { onSearchResults(currentIndex, totalCount) }
                    }

                    @JavascriptInterface
                    fun onReplaceDone(replacedCount: Int) { post { onReplaceDone(replacedCount) } }

                    @JavascriptInterface
                    fun onZoomChanged(percent: Int) { post { onZoomChanged(percent) } }

                    @JavascriptInterface
                    fun onEditorWarning(warningMsg: String) { post { onEditorWarning(warningMsg) } }

                    @JavascriptInterface
                    fun onEditorError(errorMsg: String) { post { onEditorError(errorMsg) } }
                }

                addJavascriptInterface(bridge, "AndroidBridge")

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val url = request.url
                        if (url.host == "appassets.androidplatform.net" && url.path == "/kadata-doc/payload.json") {
                            val bytes = payloadProvider() ?: "{}".toByteArray(Charsets.UTF_8)
                            return WebResourceResponse(
                                "application/json",
                                "UTF-8",
                                ByteArrayInputStream(bytes)
                            )
                        }
                        return assetLoader.shouldInterceptRequest(url)
                    }

                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: RenderProcessGoneDetail
                    ): Boolean {
                        runCatching {
                            (view.parent as? android.view.ViewGroup)?.removeView(view)
                            view.destroy()
                        }
                        post { onRenderProcessCrashed.invoke() }
                        return true
                    }
                }

                onWebViewCreated(this)
                loadUrl("https://appassets.androidplatform.net/assets/editor/index.html")
            }
        }
    )
}
