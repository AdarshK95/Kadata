package com.kannadadoc

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import com.kannadadoc.data.storage.IngestedDocument
import com.kannadadoc.data.storage.SafStorageManager
import com.kannadadoc.parser.DocFormat
import com.kannadadoc.parser.ParsedDocument
import com.kannadadoc.ui.converter.ConverterScreen
import com.kannadadoc.ui.editor.EditorScreen
import com.kannadadoc.ui.editor.EditorViewModel
import com.kannadadoc.ui.home.HomeScreen
import com.kannadadoc.ui.home.HomeViewModel
import com.kannadadoc.ui.pdf.PdfViewerScreen
import com.kannadadoc.ui.theme.KannadaDocTheme
import kotlinx.coroutines.delay

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
            appContext = applicationContext,
            storageManager = app.storageManager,
            recentFileDao = app.database.recentFileDao(),
            draftDao = app.database.draftDao(),
            parserFacade = app.parserFacade,
            docxWriter = app.docxWriter,
            rtfDocWriter = app.rtfDocWriter,
            pdfExporter = app.pdfExporter
        )
    }

    private val editorViewModel: EditorViewModel by viewModels {
        EditorViewModel.Factory(
            appContext = applicationContext,
            parserFacade = app.parserFacade,
            storageManager = app.storageManager,
            recentFileDao = app.database.recentFileDao(),
            draftDao = app.database.draftDao(),
            docxWriter = app.docxWriter,
            rtfDocWriter = app.rtfDocWriter,
            pdfExporter = app.pdfExporter
        )
    }

    private var currentScreen by mutableStateOf<AppScreen>(AppScreen.Home)
    private var showDailyLandingSplash by mutableStateOf(false)

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val readWriteFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            homeViewModel.openUri(uri, readWriteFlags) { ingested ->
                routeToDocument(ingested)
            }
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

            SideEffect {
                val isDark = userSettings.isDarkTheme
                enableEdgeToEdge(
                    statusBarStyle = if (isDark) {
                        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    },
                    navigationBarStyle = if (isDark) {
                        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    }
                )
            }

            LaunchedEffect(showDailyLandingSplash) {
                if (showDailyLandingSplash) {
                    delay(1_500)
                    showDailyLandingSplash = false
                }
            }

            KannadaDocTheme(darkTheme = userSettings.isDarkTheme) {
                val recentFiles by homeViewModel.recentFiles.collectAsState()
                val selectedFilter by homeViewModel.selectedFilter.collectAsState()
                val homeUiState by homeViewModel.uiState.collectAsState()
                val configuration = LocalConfiguration.current
                val useTabletArt = configuration.screenWidthDp > configuration.screenHeightDp

                BackHandler(enabled = currentScreen != AppScreen.Home) {
                    currentScreen = AppScreen.Home
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    when (val screen = currentScreen) {
                        AppScreen.Home -> {
                            HomeScreen(
                                recentFiles = recentFiles,
                                selectedFilter = selectedFilter,
                                uiState = homeUiState,
                                userSettings = userSettings,
                                onFilterSelected = homeViewModel::setFilter,
                                onPickFileClick = {
                                    openDocumentLauncher.launch(SafStorageManager.SUPPORTED_SAF_MIME_TYPES)
                                },
                                onCreateNewDocument = { title ->
                                    homeViewModel.createNewBlankDocument(title) { created ->
                                        val blank = app.parserFacade.createBlankDocument(
                                            uriString = created.entity.uriString,
                                            fileName = created.entity.displayName
                                        )
                                        editorViewModel.loadPreParsedDocument(
                                            parsed = blank,
                                            initialFontSizePt = userSettings.defaultFontSizePt
                                        )
                                        currentScreen = AppScreen.Editor(System.currentTimeMillis())
                                    }
                                },
                                onOpenConverterClick = {
                                    currentScreen = AppScreen.Converter
                                },
                                onRecentFileClick = { entity ->
                                    val uri = Uri.parse(entity.uriString)
                                    homeViewModel.openUri(uri) { ingested ->
                                        routeToDocument(ingested)
                                    }
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
                                onFontSizeChange = app.preferences::setDefaultFontSizePt,
                                onShowInfo = homeViewModel::showInfoMessage,
                                onErrorShown = homeViewModel::clearError,
                                onStatusShown = homeViewModel::clearStatus,
                                onDismissUnsupportedAlert = homeViewModel::dismissUnsupportedFormatAlert
                            )
                        }

                        AppScreen.Converter -> {
                            ConverterScreen(
                                knOnly = userSettings.isKannadaUi,
                                onBack = { currentScreen = AppScreen.Home }
                            )
                        }

                        is AppScreen.Editor -> {
                            key(screen.key) {
                                EditorScreen(
                                    viewModel = editorViewModel,
                                    userSettings = userSettings,
                                    onThemeModeChange = app.preferences::setThemeMode,
                                    onSaveFormatChange = app.preferences::setDefaultSaveFormat,
                                    onAutoSaveIntervalChange = app.preferences::setAutoSaveIntervalMinutes,
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
                                bilingualLabels = !userSettings.isKannadaUi,
                                onBack = { currentScreen = AppScreen.Home },
                                onOpenExtractedDocument = { extracted: ParsedDocument ->
                                    editorViewModel.loadPreParsedDocument(
                                        parsed = extracted,
                                        initialFontSizePt = userSettings.defaultFontSizePt
                                    )
                                    currentScreen = AppScreen.Editor(System.currentTimeMillis())
                                }
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = showDailyLandingSplash,
                        exit = fadeOut(animationSpec = tween(durationMillis = 450))
                    ) {
                        val splashRes = if (useTabletArt) {
                            R.drawable.kadata_bg_tablet
                        } else {
                            R.drawable.kadata_bg_mobile
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFFFAF3E3))
                        ) {
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showDailyLandingSplash = false
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            val titleHint = extractTitleHintFromIntent(intent)
            homeViewModel.openUri(uri, intent.flags, titleHint) { ingested ->
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
        val defaultFontSize = app.preferences.settings.value.defaultFontSizePt
        if (ingested.entity.detectedFormat == DocFormat.PDF) {
            currentScreen = AppScreen.PdfViewer(
                uri = ingested.resolvedUri,
                fileName = ingested.entity.displayName
            )
        } else {
            editorViewModel.loadExistingDocument(
                uri = ingested.resolvedUri,
                fileName = ingested.entity.displayName,
                format = ingested.entity.detectedFormat,
                initialFontSizePt = defaultFontSize
            )
            currentScreen = AppScreen.Editor(System.currentTimeMillis())
        }
    }
}
