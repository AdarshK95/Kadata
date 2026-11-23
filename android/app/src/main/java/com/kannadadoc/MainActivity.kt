package com.kannadadoc

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kannadadoc.data.storage.IngestedDocument
import com.kannadadoc.data.storage.SafStorageManager
import com.kannadadoc.parser.DocFormat
import com.kannadadoc.parser.ParsedDocument
import com.kannadadoc.ui.common.WellnessChimePlayer
import com.kannadadoc.ui.converter.ConverterScreen
import com.kannadadoc.ui.editor.EditorScreen
import com.kannadadoc.ui.editor.EditorViewModel
import com.kannadadoc.ui.home.HomeScreen
import com.kannadadoc.ui.home.HomeViewModel
import com.kannadadoc.ui.pdf.PdfViewerScreen
import com.kannadadoc.ui.theme.KannadaDocTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed class AppScreen {
    data object Home : AppScreen()
    data object Converter : AppScreen()
    data class Editor(val key: Long) : AppScreen()
    data class PdfViewer(val uri: Uri, val fileName: String) : AppScreen()
}

class MainActivity : ComponentActivity() {

    private val app by lazy { application as KannadaDocApp }

    private val homeViewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory(
            applicationContext, app.storageManager, app.recoveryVault,
            app.database.recentFileDao(), app.database.draftDao(), app.preferences,
            app.parserFacade, app.docxWriter, app.rtfDocWriter, app.pdfExporter,
            app.odtWriter, app.txtWriter, app.htmlZipWriter, app.epubWriter
        )
    }

    private val editorViewModel: EditorViewModel by viewModels {
        EditorViewModel.Factory(
            applicationContext, app.parserFacade, app.storageManager, app.recoveryVault,
            app.database.recentFileDao(), app.database.draftDao(), app.preferences,
            app.docxWriter, app.rtfDocWriter, app.pdfExporter,
            app.odtWriter, app.txtWriter, app.htmlZipWriter, app.epubWriter
        )
    }

    private var currentScreen by mutableStateOf<AppScreen>(AppScreen.Home)
    private var showDailyLandingSplash by mutableStateOf(false)

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val readWriteFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            homeViewModel.openUri(uri, readWriteFlags) { ingested -> routeToDocument(ingested) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isDirectDocumentLaunch = intent?.action == Intent.ACTION_VIEW && intent.data != null
        if (savedInstanceState == null && !isDirectDocumentLaunch) {
            showDailyLandingSplash = app.preferences.consumeDailySplashIfDue()
        }
        handleIncomingIntent(intent)

        setContent {
            val userSettings by app.preferences.settings.collectAsState()
            val documentFormatMap by app.preferences.documentFormatMap.collectAsState()
            val isDarkTheme = userSettings.resolveDarkTheme(isSystemInDarkTheme())

            SideEffect {
                val style = if (isDarkTheme) SystemBarStyle.dark(AndroidColor.TRANSPARENT) else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            LaunchedEffect(showDailyLandingSplash) {
                if (showDailyLandingSplash) {
                    delay(1_500)
                    showDailyLandingSplash = false
                }
            }

            // App-Wide Gentle Wellness Break Reminder (triggers for reading OR writing across all screens)
            var showWellnessBanner by remember { mutableStateOf(false) }
            var activeScreenSeconds by remember { mutableIntStateOf(0) }
            var isAppInForeground by remember { mutableStateOf(true) }
            var lastPausedAtMs by remember { mutableLongStateOf(0L) }
            val lifecycleOwner = LocalLifecycleOwner.current

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> {
                            val awayMs = if (lastPausedAtMs > 0L) System.currentTimeMillis() - lastPausedAtMs else 0L
                            if (awayMs >= 120_000L) {
                                // User stepped away or turned off screen for >= 2 min -> natural break taken
                                activeScreenSeconds = 0
                            }
                            isAppInForeground = true
                        }
                        Lifecycle.Event.ON_PAUSE -> {
                            isAppInForeground = false
                            lastPausedAtMs = System.currentTimeMillis()
                        }
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            LaunchedEffect(userSettings.breakReminderMinutes, isAppInForeground) {
                val thresholdMin = userSettings.breakReminderMinutes
                if (thresholdMin <= 0) {
                    activeScreenSeconds = 0
                    showWellnessBanner = false
                    return@LaunchedEffect
                }
                if (!isAppInForeground) return@LaunchedEffect
                val thresholdSec = thresholdMin * 60
                while (isAppInForeground) {
                    delay(10_000L)
                    activeScreenSeconds += 10
                    if (activeScreenSeconds >= thresholdSec) {
                        showWellnessBanner = true
                        activeScreenSeconds = 0
                    }
                }
            }

            LaunchedEffect(showWellnessBanner) {
                if (showWellnessBanner) {
                    WellnessChimePlayer.playGentleChime(this@MainActivity)
                    delay(16_000L)
                    showWellnessBanner = false
                }
            }

            KannadaDocTheme(darkTheme = isDarkTheme) {
                val recentFiles by homeViewModel.recentFiles.collectAsState()
                val selectedFilter by homeViewModel.selectedFilter.collectAsState()
                val homeUiState by homeViewModel.uiState.collectAsState()
                val configuration = LocalConfiguration.current
                val useTabletArt = configuration.screenWidthDp > configuration.screenHeightDp

                BackHandler(enabled = currentScreen != AppScreen.Home && currentScreen !is AppScreen.Editor) {
                    currentScreen = AppScreen.Home
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .navigationBarsPadding()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsTopHeight(WindowInsets.statusBars)
                            .background(MaterialTheme.colorScheme.surface)
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f))

                    androidx.compose.animation.AnimatedVisibility(visible = showWellnessBanner) {
                        val knOnly = userSettings.isKannadaUi
                        val mins = userSettings.breakReminderMinutes
                        val knMsg = "ನೀವು $mins ನಿಮಿಷಗಳಿಂದ ಪರದೆಯನ್ನು ಬಳಸುತ್ತಿದ್ದೀರಿ — 1–2 ನಿಮಿಷ ಚಿಕ್ಕ ವಿರಾಮ ತೆಗೆದುಕೊಳ್ಳಿ! ಕಣ್ಣುಗಳಿಗೆ ವಿಶ್ರಾಂತಿ ನೀಡಿ, ಕುತ್ತಿಗೆಯನ್ನು ಹಗುರವಾಗಿ ಚಾಚಿ, ಕುಳಿತಿದ್ದರೆ ಸ್ವಲ್ಪ ನಡೆದಾಡಿ."
                        val enMsg = "You've been on screen for $mins min — take a quick 1–2 min break! Rest your eyes, gently stretch your neck, and take a light walk or stand for a bit."
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = if (knOnly) knMsg else "$enMsg\n$knMsg",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { showWellnessBanner = false }) {
                                    Text(if (knOnly) "ಸರಿ" else "OK • ಸರಿ")
                                }
                            }
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        when (val screen = currentScreen) {
                            AppScreen.Home -> {
                                HomeScreen(
                                    recentFiles = recentFiles,
                                    documentFormatMap = documentFormatMap,
                                    selectedFilter = selectedFilter,
                                    uiState = homeUiState,
                                    userSettings = userSettings,
                                    onFilterSelected = homeViewModel::setFilter,
                                    onPickFileClick = { openDocumentLauncher.launch(SafStorageManager.SUPPORTED_SAF_MIME_TYPES) },
                                    onCreateNewDocument = { title ->
                                        homeViewModel.createNewBlankDocument(title) { created ->
                                            val blank = app.parserFacade.createBlankDocument(created.entity.uriString, created.entity.displayName)
                                            editorViewModel.loadPreParsedDocument(blank, userSettings.defaultFontSizePt, false, null)
                                            currentScreen = AppScreen.Editor(System.currentTimeMillis())
                                        }
                                    },
                                    onOpenConverterClick = { currentScreen = AppScreen.Converter },
                                    onRecentFileClick = { entity ->
                                        homeViewModel.openRecentCard(entity) { ingested -> routeToDocument(ingested) }
                                    },
                                    onShareRecentFileAsIs = homeViewModel::shareRecentFileAsIs,
                                    onExportRecentFile = homeViewModel::exportRecentFile,
                                    onWritePendingSafCopy = homeViewModel::writePendingSafCopy,
                                    onRenameRecentFile = homeViewModel::renameRecentFile,
                                    onDeleteRecentFile = homeViewModel::removeRecentFile,
                                    onToggleLanguage = app.preferences::toggleLanguage,
                                    onThemeModeChange = app.preferences::setThemeMode,
                                    onSaveFormatChange = app.preferences::setDefaultSaveFormat,
                                    onAutoSaveIntervalChange = app.preferences::setAutoSaveIntervalMinutes,
                                    onBreakReminderChange = app.preferences::setBreakReminderMinutes,
                                    onBlockAutoKeyboardChange = app.preferences::setBlockAutoKeyboard,
                                    onToggleViewMode = app.preferences::toggleHomeViewMode,
                                    onFontSizeChange = app.preferences::setDefaultFontSizePt,
                                    onShowInfo = homeViewModel::showInfoMessage,
                                    onErrorShown = homeViewModel::clearError,
                                    onStatusShown = homeViewModel::clearStatus
                                )
                            }

                            AppScreen.Converter -> {
                                ConverterScreen(
                                    knOnly = userSettings.isKannadaUi,
                                    blockAutoKeyboard = userSettings.blockAutoKeyboard,
                                    onBack = { currentScreen = AppScreen.Home }
                                )
                            }

                            is AppScreen.Editor -> {
                                key(screen.key) {
                                    EditorScreen(
                                        viewModel = editorViewModel,
                                        userSettings = userSettings,
                                        memoryPressureEvents = app.memoryPressureEvents,
                                        onThemeModeChange = app.preferences::setThemeMode,
                                        onSaveFormatChange = app.preferences::setDefaultSaveFormat,
                                        onAutoSaveIntervalChange = app.preferences::setAutoSaveIntervalMinutes,
                                        onBreakReminderChange = app.preferences::setBreakReminderMinutes,
                                        onBlockAutoKeyboardChange = app.preferences::setBlockAutoKeyboard,
                                        onDefaultFontSizeChange = app.preferences::setDefaultFontSizePt,
                                        onBack = { currentScreen = AppScreen.Home }
                                    )
                                }
                            }

                            is AppScreen.PdfViewer -> {
                                PdfViewerScreen(
                                    uri = screen.uri,
                                    fileName = screen.fileName,
                                    parserFacade = app.parserFacade,
                                    knOnly = userSettings.isKannadaUi,
                                    onBack = { currentScreen = AppScreen.Home },
                                    onOpenExtractedDocument = { extracted: ParsedDocument ->
                                        homeViewModel.createEditableDocxFromPdf(extracted) { _, boundDoc ->
                                            editorViewModel.loadPreParsedDocument(boundDoc, userSettings.defaultFontSizePt, false, null)
                                            currentScreen = AppScreen.Editor(System.currentTimeMillis())
                                        }
                                    }
                                )
                            }
                        }

                        homeUiState.crashRecoveryCandidate?.let { candidate ->
                            if (currentScreen == AppScreen.Home) {
                                val knOnly = userSettings.isKannadaUi
                                val timeStr = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(candidate.updatedAt))
                                AlertDialog(
                                    onDismissRequest = { homeViewModel.dismissCrashRecovery(clearMarker = false) },
                                    title = {
                                        Text(
                                            if (knOnly) "ಅನಿರೀಕ್ಷಿತ ಮುಚ್ಚುವಿಕೆಯಿಂದ ದಾಖಲೆ ಮರುಪಡೆಯಲಾಗಿದೆ"
                                            else "Recovered Unsaved Work • ದಾಖಲೆ ಮರುಪಡೆಯಲಾಗಿದೆ"
                                        )
                                    },
                                    text = {
                                        val knText = "ಕಡತ ಅನಿರೀಕ್ಷಿತವಾಗಿ ಮುಚ್ಚಲ್ಪಟ್ಟಿದೆ. \"${candidate.documentTitle}\" ನಲ್ಲಿನ ನಿಮ್ಮ ಇತ್ತೀಚಿನ ಬದಲಾವಣೆಗಳನ್ನು ($timeStr) ಸುರಕ್ಷಿತವಾಗಿ ಮರುಪಡೆಯಲಾಗಿದೆ."
                                        val enText = "Kadata closed unexpectedly. Your latest edits to \"${candidate.documentTitle}\" (saved $timeStr) have been recovered."
                                        Text(
                                            text = if (knOnly) knText else "$enText\n\n$knText",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    },
                                    confirmButton = {
                                        Button(onClick = {
                                            homeViewModel.dismissCrashRecovery(clearMarker = false)
                                            editorViewModel.loadCrashRecoveredDocument(candidate, userSettings.defaultFontSizePt)
                                            currentScreen = AppScreen.Editor(System.currentTimeMillis())
                                        }) {
                                            Text(if (knOnly) "ತೆರೆಯಿರಿ" else "Open • ತೆರೆಯಿರಿ")
                                        }
                                    },
                                    dismissButton = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            OutlinedButton(onClick = {
                                                homeViewModel.saveCrashRecoveryAsCopy(candidate) { ingested -> routeToDocument(ingested) }
                                            }) {
                                                Text(if (knOnly) "ಪ್ರತಿ ಉಳಿಸಿ" else "Save Copy • ಪ್ರತಿ ಉಳಿಸಿ")
                                            }
                                            TextButton(onClick = { homeViewModel.dismissCrashRecovery(clearMarker = true) }) {
                                                Text(if (knOnly) "ತಿರಸ್ಕರಿಸಿ" else "Dismiss • ತಿರಸ್ಕರಿಸಿ")
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        homeUiState.fileRejectionAlert?.let { alert ->
                            val knOnly = userSettings.isKannadaUi
                            AlertDialog(
                                onDismissRequest = homeViewModel::dismissFileRejectionAlert,
                                title = { Text(if (knOnly) alert.titleKn else "${alert.titleEn} • ${alert.titleKn}") },
                                text = {
                                    Text(
                                        text = if (knOnly) alert.messageKn else "${alert.messageEn}\n\n${alert.messageKn}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                confirmButton = {
                                    Button(onClick = homeViewModel::dismissFileRejectionAlert) {
                                        Text(if (knOnly) "ಸರಿ" else "OK • ಸರಿ")
                                    }
                                }
                            )
                        }

                        androidx.compose.animation.AnimatedVisibility(
                            visible = showDailyLandingSplash,
                            exit = fadeOut(animationSpec = tween(durationMillis = 450))
                        ) {
                            val splashRes = if (useTabletArt) R.drawable.kadata_bg_tablet else R.drawable.kadata_bg_mobile
                            Box(modifier = Modifier.fillMaxSize().background(Color(0xFFFAF3E3))) {
                                Image(
                                    painter = painterResource(id = splashRes),
                                    contentDescription = "Kadata",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showDailyLandingSplash = false
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            homeViewModel.openUri(uri, intent.flags, extractTitleHintFromIntent(intent)) { ingested ->
                routeToDocument(ingested)
            }
        }
    }

    private fun extractTitleHintFromIntent(intent: Intent): String? {
        val candidates = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_TITLE),
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
            intent.clipData?.description?.label?.toString(),
            intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        )
        return candidates.firstOrNull { it.isNotBlank() && it.length <= 160 }
    }

    private fun routeToDocument(ingested: IngestedDocument) {
        val settings = app.preferences.settings.value
        val defaultFontSize = settings.defaultFontSizePt
        val duplicateNotice = if (ingested.alreadyExisted) {
            if (settings.isKannadaUi) {
                "ಈ ಫೈಲ್ ಈಗಾಗಲೇ ಕಡತದಲ್ಲಿದೆ — ಅಸ್ತಿತ್ವದಲ್ಲಿರುವ ಫೈಲ್ ತೆರೆಯಲಾಗಿದೆ"
            } else {
                "This file already exists in Kadata — opened existing file"
            }
        } else {
            null
        }
        if (ingested.entity.detectedFormat == DocFormat.PDF) {
            if (duplicateNotice != null) {
                Toast.makeText(this, duplicateNotice, Toast.LENGTH_SHORT).show()
            }
            currentScreen = AppScreen.PdfViewer(ingested.resolvedUri, ingested.entity.displayName)
        } else {
            editorViewModel.loadExistingDocument(
                uri = ingested.resolvedUri,
                fileName = ingested.entity.displayName,
                format = ingested.entity.detectedFormat,
                initialFontSizePt = defaultFontSize,
                accessInfo = ingested.accessInfo,
                initialStatusMessage = duplicateNotice
            )
            currentScreen = AppScreen.Editor(System.currentTimeMillis())
        }
    }
}
