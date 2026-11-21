package com.kannadadoc.ui.home

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kannadadoc.R
import com.kannadadoc.data.local.AppPreferences
import com.kannadadoc.data.local.AppThemeMode
import com.kannadadoc.data.local.DefaultSaveFormat
import com.kannadadoc.data.local.RecentFileEntity
import com.kannadadoc.data.local.UserSettings
import com.kannadadoc.data.storage.FileLocationHelper
import com.kannadadoc.data.storage.FileLocationResult
import com.kannadadoc.parser.DocumentRouter
import com.kannadadoc.ui.common.FileTypeIcon
import com.kannadadoc.ui.common.TwoStepFormatExportDialog
import com.kannadadoc.ui.editor.ExportAction
import com.kannadadoc.ui.editor.KannadaEncodingChoice
import com.kannadadoc.ui.editor.TargetFileType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    recentFiles: List<RecentFileEntity>,
    selectedFilter: LibraryFilter,
    uiState: HomeUiState,
    userSettings: UserSettings,
    onFilterSelected: (LibraryFilter) -> Unit,
    onPickFileClick: () -> Unit,
    onCreateNewDocument: (String) -> Unit,
    onOpenConverterClick: () -> Unit,
    onRecentFileClick: (RecentFileEntity) -> Unit,
    onShareRecentFileAsIs: (RecentFileEntity) -> Unit,
    onExportRecentFile: (
        entity: RecentFileEntity,
        action: ExportAction,
        encoding: KannadaEncodingChoice,
        fileType: TargetFileType,
        onRequestSafCreate: (String, String) -> Unit
    ) -> Unit,
    onWritePendingSafCopy: (Uri) -> Unit,
    onRenameRecentFile: (RecentFileEntity, String) -> Unit,
    onDeleteRecentFile: (String) -> Unit,
    onToggleLanguage: () -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onSaveFormatChange: (DefaultSaveFormat) -> Unit,
    onAutoSaveIntervalChange: (Int) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onShowInfo: (String) -> Unit,
    onErrorShown: () -> Unit,
    onStatusShown: () -> Unit,
    onDismissUnsupportedAlert: () -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val knOnly = userSettings.isKannadaUi

    var showNewDocDialog by remember { mutableStateOf(false) }
    var newDocTitle by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }

    var filePendingDelete by remember { mutableStateOf<RecentFileEntity?>(null) }
    var filePendingRename by remember { mutableStateOf<RecentFileEntity?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Two-step Share Different Format / Save As dialog state for Home recent file cards
    var cardExportTarget by remember { mutableStateOf<RecentFileEntity?>(null) }
    var cardExportAction by remember { mutableStateOf<ExportAction?>(null) }
    var cardChosenEncoding by remember { mutableStateOf<KannadaEncodingChoice?>(null) }

    val createDocxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(TargetFileType.DOCX.mimeType)
    ) { uri -> uri?.let { onWritePendingSafCopy(it) } }

    val createDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(TargetFileType.DOC.mimeType)
    ) { uri -> uri?.let { onWritePendingSafCopy(it) } }

    val createPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(TargetFileType.PDF.mimeType)
    ) { uri -> uri?.let { onWritePendingSafCopy(it) } }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            onErrorShown()
        }
    }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            onStatusShown()
        }
    }

    val configuration = LocalConfiguration.current
    val useTabletArt = configuration.screenWidthDp > configuration.screenHeightDp
    val blurredBgRes = if (useTabletArt) R.drawable.kadata_bg_tablet_blur else R.drawable.kadata_bg_mobile_blur
    val bgScrimColor = if (userSettings.isDarkTheme) {
        Color(0xA80F161E)
    } else {
        Color(0x88FAF3E3)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = blurredBgRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgScrimColor)
        )

        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .height(46.dp)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.kadata_logo_icon),
                            contentDescription = "Kadata logo",
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (knOnly) "ಕಡತ" else "Kadata • ಕಡತ",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = onToggleLanguage,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text(
                                text = if (knOnly) "ಕನ್ನಡ" else "Eng",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(
                            onClick = { showSettingsDialog = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(top = 8.dp, bottom = 6.dp)
            ) {
                // Compact horizontally scrollable action cards:
                // Open File + New sit cleanly in view while Converter peeks on the right edge.
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val horizontalPad = 14.dp
                    val gap = 10.dp
                    val peekWidth = 30.dp
                    val availableWidth = maxWidth - horizontalPad
                    val cardWidth = ((availableWidth - gap * 2 - peekWidth) / 2f).coerceAtLeast(130.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = horizontalPad),
                        horizontalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        PrimaryActionCard(
                            primaryLabel = if (knOnly) "ಫೈಲ್ ತೆರೆಯಿರಿ" else "Open File",
                            secondaryLabel = if (knOnly) null else "ಫೈಲ್ ತೆರೆಯಿರಿ",
                            icon = Icons.Default.FolderOpen,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            onClick = onPickFileClick,
                            modifier = Modifier.width(cardWidth)
                        )

                        PrimaryActionCard(
                            primaryLabel = if (knOnly) "ಹೊಸದು" else "New",
                            secondaryLabel = if (knOnly) null else "ಹೊಸದು",
                            icon = Icons.Default.AddCircle,
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary,
                            onClick = {
                                newDocTitle = "Note_${SimpleDateFormat("ddMMM", Locale.getDefault()).format(Date())}"
                                showNewDocDialog = true
                            },
                            modifier = Modifier.width(cardWidth)
                        )

                        PrimaryActionCard(
                            primaryLabel = if (knOnly) "ಪರಿವರ್ತಕ" else "Converter",
                            secondaryLabel = if (knOnly) null else "ಪರಿವರ್ತಕ",
                            icon = Icons.Default.Translate,
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                            onClick = onOpenConverterClick,
                            modifier = Modifier.width(cardWidth)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Quick Filter Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LibraryFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { onFilterSelected(filter) },
                            label = {
                                Text(
                                    if (knOnly) filter.labelKn else "${filter.labelEn} • ${filter.labelKn}"
                                )
                            }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    text = if (knOnly) "ಇತ್ತೀಚಿನ ಫೈಲ್‌ಗಳು" else "Recent Files • ಇತ್ತೀಚಿನ ಫೈಲ್‌ಗಳು",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 14.dp)
                )
                Spacer(Modifier.height(8.dp))

                if (uiState.isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (recentFiles.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (knOnly) {
                                "ಇಲ್ಲಿ ಯಾವುದೇ ಫೈಲ್ ಇಲ್ಲ. \"ಫೈಲ್ ತೆರೆಯಿರಿ\" ಒತ್ತಿರಿ."
                            } else {
                                "No files here yet. Tap \"Open File\" or open any document from WhatsApp.\nಇಲ್ಲಿ ಯಾವುದೇ ಫೈಲ್ ಇಲ್ಲ. \"ಫೈಲ್ ತೆರೆಯಿರಿ\" ಒತ್ತಿರಿ."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 320.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp)
                    ) {
                        items(recentFiles, key = { it.uriString }) { file ->
                            RecentFileCard(
                                entity = file,
                                knOnly = knOnly,
                                isDarkTheme = userSettings.isDarkTheme,
                                onClick = { onRecentFileClick(file) },
                                onOpenLocationClick = {
                                    when (val res = FileLocationHelper.openFileLocation(context, file)) {
                                        FileLocationResult.Opened -> Unit
                                        is FileLocationResult.InfoMessage -> {
                                            onShowInfo(if (knOnly) res.messageKn else res.messageEn)
                                        }
                                    }
                                },
                                onShareAsIsClick = { onShareRecentFileAsIs(file) },
                                onShareDifferentFormatClick = {
                                    cardExportTarget = file
                                    cardChosenEncoding = null
                                    cardExportAction = ExportAction.SHARE_DIFFERENT
                                },
                                onSaveAsClick = {
                                    cardExportTarget = file
                                    cardChosenEncoding = null
                                    cardExportAction = ExportAction.SAVE_COPY
                                },
                                onRenameClick = {
                                    renameText = file.displayName
                                    filePendingRename = file
                                },
                                onDeleteClick = { filePendingDelete = file }
                            )
                        }
                    }
                }
            }
        }
    }

    // Unsupported File Format Dialog (e.g. PPT, PPTX, XLSX, PNG)
    uiState.unsupportedFormatName?.let { badFormat ->
        AlertDialog(
            onDismissRequest = onDismissUnsupportedAlert,
            title = {
                Text(if (knOnly) "ಬೆಂಬಲಿತವಲ್ಲದ ಫೈಲ್" else "Unsupported File Format")
            },
            text = {
                Text(
                    text = if (knOnly) {
                        "ಈ $badFormat ಫಾರ್ಮ್ಯಾಟ್ ಬೆಂಬಲಿತವಾಗಿಲ್ಲ.\n\nಬೆಂಬಲಿತ ಫಾರ್ಮ್ಯಾಟ್‌ಗಳು: ${DocumentRouter.SUPPORTED_FORMATS_LABEL}"
                    } else {
                        "This $badFormat format is not supported.\n\nSupported formats: ${DocumentRouter.SUPPORTED_FORMATS_LABEL}"
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                Button(onClick = onDismissUnsupportedAlert) {
                    Text("OK")
                }
            }
        )
    }

    // Two-Step Format Dialog for Home Card "Share Different Format" & "Save As"
    if (cardExportTarget != null && cardExportAction != null) {
        val target = cardExportTarget!!
        val action = cardExportAction!!
        TwoStepFormatExportDialog(
            action = action,
            chosenEncoding = cardChosenEncoding,
            knOnly = knOnly,
            onSelectEncoding = { cardChosenEncoding = it },
            onBackToEncoding = { cardChosenEncoding = null },
            onSelectFileType = { enc, fileType ->
                cardExportTarget = null
                cardExportAction = null
                cardChosenEncoding = null
                onExportRecentFile(target, action, enc, fileType) { suggested, mime ->
                    when (mime) {
                        TargetFileType.DOC.mimeType -> createDocLauncher.launch(suggested)
                        TargetFileType.PDF.mimeType -> createPdfLauncher.launch(suggested)
                        else -> createDocxLauncher.launch(suggested)
                    }
                }
            },
            onDismiss = {
                cardExportTarget = null
                cardExportAction = null
                cardChosenEncoding = null
            }
        )
    }

    // New Blank Document Dialog
    if (showNewDocDialog) {
        AlertDialog(
            onDismissRequest = { showNewDocDialog = false },
            title = { Text(if (knOnly) "ಹೊಸ ದಾಖಲೆ" else "New Document • ಹೊಸ ದಾಖಲೆ") },
            text = {
                OutlinedTextField(
                    value = newDocTitle,
                    onValueChange = { newDocTitle = it },
                    label = { Text(if (knOnly) "ದಾಖಲೆಯ ಹೆಸರು" else "Document Name • ಹೆಸರು") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    showNewDocDialog = false
                    onCreateNewDocument(newDocTitle.ifBlank { "Untitled" })
                }) {
                    Text(if (knOnly) "ಪ್ರಾರಂಭಿಸಿ" else "Create • ಪ್ರಾರಂಭಿಸಿ")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewDocDialog = false }) {
                    Text(if (knOnly) "ರದ್ದು" else "Cancel")
                }
            }
        )
    }

    // Rename Document Dialog
    filePendingRename?.let { target ->
        AlertDialog(
            onDismissRequest = { filePendingRename = null },
            title = { Text(if (knOnly) "ಹೆಸರು ಬದಲಿಸಿ" else "Rename Document • ಹೆಸರು ಬದಲಿಸಿ") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(if (knOnly) "ಹೊಸ ಹೆಸರು" else "New Name • ಹೊಸ ಹೆಸರು") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updated = renameText.trim()
                        if (updated.isNotEmpty()) {
                            onRenameRecentFile(target, updated)
                        }
                        filePendingRename = null
                    },
                    enabled = renameText.isNotBlank()
                ) {
                    Text(if (knOnly) "ಬದಲಿಸಿ" else "Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { filePendingRename = null }) {
                    Text(if (knOnly) "ರದ್ದು" else "Cancel")
                }
            }
        )
    }

    // Delete Confirmation Dialog
    filePendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { filePendingDelete = null },
            title = { Text(if (knOnly) "ಫೈಲ್ ತೆಗೆದುಹಾಕಬೇಕೇ?" else "Delete Document? • ತೆಗೆದುಹಾಕಬೇಕೇ?") },
            text = {
                Text(
                    text = if (knOnly) {
                        "\"${target.displayName}\" ಅನ್ನು ಇತ್ತೀಚಿನ ಫೈಲ್‌ಗಳ ಪಟ್ಟಿಯಿಂದ ಮತ್ತು ಡ್ರಾಫ್ಟ್‌ಗಳಿಂದ ತೆಗೆದುಹಾಕಲಾಗುತ್ತದೆ."
                    } else {
                        "Remove \"${target.displayName}\" from recent files and clear its unsaved draft? This cannot be undone."
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteRecentFile(target.uriString)
                        filePendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(if (knOnly) "ತೆಗೆದುಹಾಕಿ" else "Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { filePendingDelete = null }) {
                    Text(if (knOnly) "ರದ್ದು" else "Cancel")
                }
            }
        )
    }

    // Settings Dialog
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
                            text = if (knOnly) {
                                "ಅಕ್ಷರದ ಗಾತ್ರ: ${userSettings.defaultFontSizePt} pt"
                            } else {
                                "Default Text Size: ${userSettings.defaultFontSizePt} pt"
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Slider(
                            value = userSettings.defaultFontSizePt.toFloat(),
                            onValueChange = { onFontSizeChange(it.toInt()) },
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
                    Text(
                        text = if (knOnly) {
                            "ಸೂಚನೆ: ವಾಟ್ಸಾಪ್‌ನಲ್ಲಿ ಫೈಲ್ ಒತ್ತಿದಾಗ Kadata → Always ಆಯ್ಕೆ ಮಾಡಿ."
                        } else {
                            "Tip: Tap any Word file in WhatsApp and choose Kadata → Always."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showSettingsDialog = false }) {
                    Text(if (knOnly) "ಮುಗಿಯಿತು" else "Done")
                }
            }
        )
    }
}

@Composable
private fun PrimaryActionCard(
    primaryLabel: String,
    secondaryLabel: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        modifier = modifier.height(50.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = primaryLabel,
                    fontSize = 14.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (secondaryLabel != null) {
                    Text(
                        text = secondaryLabel,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        color = contentColor.copy(alpha = 0.88f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentFileCard(
    entity: RecentFileEntity,
    knOnly: Boolean,
    isDarkTheme: Boolean,
    onClick: () -> Unit,
    onOpenLocationClick: () -> Unit,
    onShareAsIsClick: () -> Unit,
    onShareDifferentFormatClick: () -> Unit,
    onSaveAsClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var showCardMenu by remember { mutableStateOf(false) }

    val dateLabel = remember(entity.lastOpenedAt) {
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            .format(Date(entity.lastOpenedAt))
    }

    val statusHint = when {
        entity.savedUnicodeUri != null -> if (knOnly) "ತಿದ್ದುಪಡಿ" else "Edited"
        entity.isLocallyImported -> if (knOnly) "ಆಫ್‌ಲೈನ್" else "Offline"
        else -> null
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Compact, elegant distinct modern file-type icon (~50% smaller than old 36dp block icon)
            FileTypeIcon(
                format = entity.detectedFormat,
                isDarkTheme = isDarkTheme
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entity.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = dateLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = entity.detectedFormat.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (statusHint != null) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = statusHint,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = if (entity.savedUnicodeUri != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            // Single 3-dot menu consolidating Open File Location, Share, Share Different Format, Save As, Rename, Delete
            Box {
                IconButton(
                    onClick = { showCardMenu = true },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "File options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                DropdownMenu(
                    expanded = showCardMenu,
                    onDismissRequest = { showCardMenu = false },
                    containerColor = if (isDarkTheme) Color.Black else MaterialTheme.colorScheme.surface
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(if (knOnly) "ಫೈಲ್ ಸ್ಥಳ ತೆರೆಯಿರಿ" else "Open File Location")
                        },
                        leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                        onClick = {
                            showCardMenu = false
                            onOpenLocationClick()
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (knOnly) "ಹಂಚಿಕೊಳ್ಳಿ" else "Share • ಹಂಚಿಕೊಳ್ಳಿ") },
                        leadingIcon = { Icon(Icons.Default.Share, null) },
                        onClick = {
                            showCardMenu = false
                            onShareAsIsClick()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(if (knOnly) "ಬೇರೆ ಫಾರ್ಮ್ಯಾಟ್ ಹಂಚಿಕೊಳ್ಳಿ" else "Share Different Format")
                        },
                        leadingIcon = { Icon(Icons.Default.Share, null) },
                        onClick = {
                            showCardMenu = false
                            onShareDifferentFormatClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (knOnly) "ಬೇರೆ ಪ್ರತಿಯಾಗಿ ಉಳಿಸಿ" else "Save As") },
                        leadingIcon = { Icon(Icons.Default.Save, null) },
                        onClick = {
                            showCardMenu = false
                            onSaveAsClick()
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (knOnly) "ಹೆಸರು ಬದಲಿಸಿ" else "Rename") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = {
                            showCardMenu = false
                            onRenameClick()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (knOnly) "ತೆಗೆದುಹಾಕಿ" else "Delete",
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.DeleteOutline,
                                null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            showCardMenu = false
                            onDeleteClick()
                        }
                    )
                }
            }
        }
    }
}
