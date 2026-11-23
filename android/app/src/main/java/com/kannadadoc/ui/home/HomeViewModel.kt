package com.kannadadoc.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kannadadoc.converter.KannadaTextConverter
import com.kannadadoc.data.local.AppPreferences
import com.kannadadoc.data.local.DraftDao
import com.kannadadoc.data.local.RecentFileDao
import com.kannadadoc.data.local.RecentFileEntity
import com.kannadadoc.data.storage.CrashRecoveryCandidate
import com.kannadadoc.data.storage.DocumentTitleHealer
import com.kannadadoc.data.storage.ImportSourcePolicy
import com.kannadadoc.data.storage.FileLocationHelper
import com.kannadadoc.data.storage.IngestedDocument
import com.kannadadoc.data.storage.RecoveryVaultManager
import com.kannadadoc.data.storage.SafStorageManager
import com.kannadadoc.data.storage.UnsupportedFormatException
import com.kannadadoc.export.DocxWriter
import com.kannadadoc.export.EpubWriter
import com.kannadadoc.export.GoogleDocsShareHelper
import com.kannadadoc.export.HtmlZipWriter
import com.kannadadoc.export.OdtWriter
import com.kannadadoc.export.PdfExporter
import com.kannadadoc.export.RtfDocWriter
import com.kannadadoc.export.ShareHelper
import com.kannadadoc.export.TxtWriter
import com.kannadadoc.parser.DocBlock
import com.kannadadoc.parser.DocFormat
import com.kannadadoc.parser.DocumentJson
import com.kannadadoc.parser.DocumentParserFacade
import com.kannadadoc.parser.DocumentRouter
import com.kannadadoc.parser.DocumentSizeLimitException
import com.kannadadoc.parser.ParsedDocument
import com.kannadadoc.parser.TextRun
import com.kannadadoc.ui.common.CreateDocumentRequest
import com.kannadadoc.ui.editor.ExportAction
import com.kannadadoc.ui.editor.KannadaEncodingChoice
import com.kannadadoc.ui.editor.TargetFileType
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

enum class LibraryFilter(val labelEn: String, val labelKn: String) {
    ALL("All", "ಎಲ್ಲಾ"),
    WORD("Docs", "ಡಾಕ್ಸ್"),
    PDF("PDF", "ಪಿಡಿಎಫ್"),
    TEXT("Text/Web", "ಟೆಕ್ಸ್ಟ್/ವೆಬ್"),
    EBOOK("EPUB", "ಇ-ಪುಸ್ತಕ"),
    EDITED("Edited", "ತಿದ್ದುಪಡಿ")
}

data class FileRejectionAlert(
    val titleEn: String,
    val titleKn: String,
    val messageEn: String,
    val messageKn: String
)

data class HomeUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val fileRejectionAlert: FileRejectionAlert? = null,
    val crashRecoveryCandidate: CrashRecoveryCandidate? = null
)

class HomeViewModel(
    private val appContext: Context,
    private val storageManager: SafStorageManager,
    private val recoveryVault: RecoveryVaultManager,
    private val recentFileDao: RecentFileDao,
    private val draftDao: DraftDao,
    private val preferences: AppPreferences,
    private val parserFacade: DocumentParserFacade,
    private val docxWriter: DocxWriter,
    private val rtfDocWriter: RtfDocWriter,
    private val pdfExporter: PdfExporter,
    private val odtWriter: OdtWriter = OdtWriter(),
    private val txtWriter: TxtWriter = TxtWriter(),
    private val htmlZipWriter: HtmlZipWriter = HtmlZipWriter(),
    private val epubWriter: EpubWriter = EpubWriter(htmlZipWriter)
) : ViewModel() {

    companion object {
        private val INTERNAL_HEX_PREFIX = Regex("^[0-9a-f]{12}_", RegexOption.IGNORE_CASE)
        private val KANNADA_UNICODE_REGEX = Regex("[\u0C80-\u0CFF]")
        private val HIGH_ASCII_NUDI_REGEX = Regex("[\u00A2-\u00FF]")
        private val LATIN_WORD_REGEX = Regex("[A-Za-z]{2,}")
        private val NUDI_FONT_REGEX = Regex("(^nudi|nudi[\\s\\-_]*\\d|kgp|kan[\\-_]?nudi)", RegexOption.IGNORE_CASE)
        private val BARAHA_FONT_REGEX = Regex("(^brh|baraha|tunga[\\s\\-_]*brh)", RegexOption.IGNORE_CASE)
    }

    private val _selectedFilter = MutableStateFlow(LibraryFilter.ALL)
    val selectedFilter: StateFlow<LibraryFilter> = _selectedFilter.asStateFlow()

    val recentFiles: StateFlow<List<RecentFileEntity>> = combine(
        recentFileDao.observeRecentFiles(),
        _selectedFilter
    ) { files, filter ->
        when (filter) {
            LibraryFilter.ALL -> files
            LibraryFilter.WORD -> files.filter {
                val fmt = it.displayFormat
                fmt == DocFormat.DOCX || fmt == DocFormat.DOC || fmt == DocFormat.ODT
            }
            LibraryFilter.PDF -> files.filter { it.displayFormat == DocFormat.PDF }
            LibraryFilter.TEXT -> files.filter {
                val fmt = it.displayFormat
                fmt == DocFormat.TXT || fmt == DocFormat.RTF || fmt == DocFormat.HTML
            }
            LibraryFilter.EBOOK -> files.filter { it.displayFormat == DocFormat.EPUB }
            LibraryFilter.EDITED -> files.filter { it.isEdited }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val pendingSafMetaFile: File
        get() = File(File(appContext.cacheDir, "save_staging").apply { mkdirs() }, "home_pending_saf.json")
    private val pendingSafDocFile: File
        get() = File(File(appContext.cacheDir, "save_staging").apply { mkdirs() }, "home_pending_saf_doc.json")

    private val backfilledUris = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val pending = runCatching { recoveryVault.detectPendingCrashRecovery() }.getOrNull()
            if (pending != null) _uiState.value = _uiState.value.copy(crashRecoveryCandidate = pending)
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                for (item in recentFileDao.getAllSnapshot()) {
                    var currentName = item.displayName
                    if (INTERNAL_HEX_PREFIX.containsMatchIn(currentName)) {
                        val cleaned = currentName.replace(INTERNAL_HEX_PREFIX, "")
                        if (cleaned.isNotBlank()) {
                            currentName = cleaned
                            recentFileDao.updateDisplayName(item.uriString, cleaned, item.lastModifiedAt)
                            draftDao.updateTitle(item.uriString, cleaned)
                            recoveryVault.renameDocumentRevisions(item.uriString, cleaned)
                        }
                    }
                    val preserveIncoming = ImportSourcePolicy.shouldPreserveIncomingFileName(
                        sourceAppLabel = item.sourceAppLabel,
                        displayName = currentName
                    )
                    if (!preserveIncoming && DocumentTitleHealer.isGenericDisplayName(currentName)) {
                        val healed = storageManager.healDisplayNameIfGeneric(Uri.parse(item.uriString), currentName, item.detectedFormat)
                        if (healed != currentName && !DocumentTitleHealer.isGenericDisplayName(healed)) {
                            recentFileDao.updateDisplayName(item.uriString, healed, item.lastModifiedAt)
                            draftDao.updateTitle(item.uriString, healed)
                            recoveryVault.renameDocumentRevisions(item.uriString, healed)
                        }
                    }
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            recentFileDao.observePendingMetadataBackfill(batchSize = 50).collect { pendingFiles ->
                val knownFormats = preferences.documentFormatMap.value
                for (item in pendingFiles) {
                    val legacyPrefTag = knownFormats[item.uriString]
                    val roomTag = item.kannadaFormat?.takeIf { it.isNotBlank() && it != "PDF" }
                    val needsSize = item.fileSizeBytes <= 0L
                    val canMigrateLegacyTag = roomTag == null && !legacyPrefTag.isNullOrBlank() && legacyPrefTag != "PDF"
                    val needsDetectFormat = roomTag == null && !canMigrateLegacyTag
                    if (!needsSize && !canMigrateLegacyTag && !needsDetectFormat) continue

                    val attemptKey = "${item.uriString}:${item.fileSizeBytes}:${roomTag.orEmpty()}:${legacyPrefTag.orEmpty()}"
                    if (!backfilledUris.add(attemptKey)) continue

                    runCatching {
                        if (canMigrateLegacyTag) recentFileDao.updateKannadaFormat(item.uriString, legacyPrefTag!!)
                        if (needsSize) {
                            val actualSize = storageManager.queryActualFileSize(Uri.parse(item.uriString))
                            if (actualSize > 0L) recentFileDao.updateFileSize(item.uriString, actualSize)
                        }
                        if (needsDetectFormat) {
                            val detectedTag = detectFormatTagForRecent(item)
                            if (detectedTag.isNotBlank()) {
                                recentFileDao.updateKannadaFormat(item.uriString, detectedTag)
                                preferences.setDocumentFormatTag(item.uriString, detectedTag)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun detectFormatTagForRecent(item: RecentFileEntity): String {
        val name = item.displayName
        when {
            name.contains("_Nudi", ignoreCase = true) -> return "Nudi"
            name.contains("_Baraha", ignoreCase = true) -> return "Baraha"
            name.contains("_Unicode", ignoreCase = true) || name.contains("_Universal", ignoreCase = true) -> return "Universal"
        }
        val inspectUri = Uri.parse(item.uriString)
        if (item.detectedFormat == DocFormat.PDF) return sniffFirstPagePdfFormat(inspectUri)

        val parsed = parserFacade.parseDocument(appContext, inspectUri, item.displayName, item.detectedFormat).getOrNull()
            ?: return "Universal"
        val allRuns = mutableListOf<TextRun>()
        for (block in parsed.blocks) {
            when (block) {
                is DocBlock.Paragraph -> allRuns.addAll(block.runs)
                is DocBlock.Table -> block.rows.forEach { row -> row.forEach { cell -> cell.forEach { p -> allRuns.addAll(p.runs) } } }
            }
        }
        var hasNudi = false; var hasBaraha = false; var hasUnicode = false; var hasEnglish = false
        for (run in allRuns) {
            val text = run.text
            if (text.isBlank()) continue
            val font = run.fontFamily?.trim()
            when {
                font != null && BARAHA_FONT_REGEX.containsMatchIn(font) -> hasBaraha = true
                font != null && NUDI_FONT_REGEX.containsMatchIn(font) -> hasNudi = true
                HIGH_ASCII_NUDI_REGEX.containsMatchIn(text) -> hasNudi = true
                KANNADA_UNICODE_REGEX.containsMatchIn(text) -> hasUnicode = true
                LATIN_WORD_REGEX.containsMatchIn(text) -> hasEnglish = true
            }
        }
        return when {
            hasBaraha -> "Baraha"
            hasNudi -> "Nudi"
            hasUnicode -> "Universal"
            hasEnglish -> "English"
            else -> preferences.settings.value.defaultSaveFormat.shortLabel
        }
    }

    private fun sniffFirstPagePdfFormat(uri: Uri): String = runCatching {
        appContext.contentResolver.openInputStream(uri)?.use { stream ->
            PDDocument.load(stream).use { pdDoc ->
                if (pdDoc.numberOfPages <= 0) return@use "Scanned"
                val page1Text = PDFTextStripper().apply { startPage = 1; endPage = 1 }.getText(pdDoc).orEmpty()
                when {
                    page1Text.isBlank() -> "Scanned"
                    HIGH_ASCII_NUDI_REGEX.containsMatchIn(page1Text) -> "Nudi"
                    KANNADA_UNICODE_REGEX.containsMatchIn(page1Text) -> "Universal"
                    LATIN_WORD_REGEX.containsMatchIn(page1Text) -> "English"
                    else -> "Scanned"
                }
            }
        } ?: "Scanned"
    }.getOrDefault("Scanned")

    fun setFilter(filter: LibraryFilter) { _selectedFilter.value = filter }

    fun openRecentCard(entity: RecentFileEntity, onDocumentReady: (IngestedDocument) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, fileRejectionAlert = null)
            storageManager.openExistingRecentCard(entity)
                .onSuccess { ingested ->
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    onDocumentReady(ingested)
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        fileRejectionAlert = FileRejectionAlert(
                            titleEn = "Unable to Open File",
                            titleKn = "ಫೈಲ್ ತೆರೆಯಲು ಸಾಧ್ಯವಾಗಲಿಲ್ಲ",
                            messageEn = error.localizedMessage ?: "The file may have been moved, deleted, or storage permission expired.",
                            messageKn = "ಈ ಫೈಲ್ ಅನ್ನು ತೆರೆಯಲು ಸಾಧ್ಯವಾಗಲಿಲ್ಲ. ಫೈಲ್ ಸ್ಥಳಾಂತರಗೊಂಡಿರಬಹುದು ಅಥವಾ ಅಳಿಸಿರಬಹುದು."
                        )
                    )
                }
        }
    }

    fun openUri(
        uri: Uri,
        intentFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION,
        titleHint: String? = null,
        onDocumentReady: (IngestedDocument) -> Unit
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, fileRejectionAlert = null)
            storageManager.ingestDocument(uri, intentFlags, titleHint)
                .onSuccess { ingested ->
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    onDocumentReady(ingested)
                }
                .onFailure { error ->
                    val rejection = when (error) {
                        is DocumentSizeLimitException -> FileRejectionAlert(error.titleEn, error.titleKn, error.userMessageEn, error.userMessageKn)
                        is UnsupportedFormatException -> FileRejectionAlert(
                            "Unsupported File Format", "ಬೆಂಬಲಿತವಲ್ಲದ ಫೈಲ್",
                            "This ${error.formatName} format is not supported.\n\nSupported formats: ${DocumentRouter.SUPPORTED_FORMATS_LABEL}",
                            "ಈ ${error.formatName} ಫಾರ್ಮ್ಯಾಟ್ ಬೆಂಬಲಿತವಾಗಿಲ್ಲ.\n\nಬೆಂಬಲಿತ ಫಾರ್ಮ್ಯಾಟ್‌ಗಳು: ${DocumentRouter.SUPPORTED_FORMATS_LABEL}"
                        )
                        else -> {
                            val detail = error.localizedMessage?.takeIf { it.isNotBlank() } ?: "The file may have been moved, deleted, or permission has expired."
                            FileRejectionAlert(
                                "Unable to Open File", "ಫೈಲ್ ತೆರೆಯಲು ಸಾಧ್ಯವಾಗಲಿಲ್ಲ",
                                "Kadata could not open this file.\n\nReason: $detail",
                                "ಕಡತ ಈ ಫೈಲ್ ಅನ್ನು ತೆರೆಯಲು ಸಾಧ್ಯವಾಗಲಿಲ್ಲ.\n\nಕಾರಣ: $detail"
                            )
                        }
                    }
                    _uiState.value = _uiState.value.copy(isLoading = false, fileRejectionAlert = rejection)
                }
        }
    }

    fun createNewBlankDocument(title: String, onCreated: (IngestedDocument) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            runCatching {
                val saveFormat = preferences.settings.value.defaultSaveFormat
                val defaultFont = if (saveFormat.shortLabel == "Universal") "Noto Sans Kannada" else "Calibri"
                val doc = storageManager.registerNewBlankDocument(title, defaultFont, saveFormat.shortLabel, docxWriter)
                preferences.setDocumentFormatTag(doc.entity.uriString, saveFormat.shortLabel)
                doc
            }.onSuccess { doc ->
                _uiState.value = _uiState.value.copy(isLoading = false)
                onCreated(doc)
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = err.localizedMessage ?: "Failed to create new document")
            }
        }
    }

    fun createEditableDocxFromPdf(extracted: ParsedDocument, onReady: (IngestedDocument, ParsedDocument) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            runCatching {
                val normalized = KannadaTextConverter.normalizeDocumentToUnicode(extracted)
                val saveFormat = preferences.settings.value.defaultSaveFormat
                val enc = when (saveFormat.shortLabel) {
                    "Nudi" -> KannadaEncodingChoice.NUDI
                    "Baraha" -> KannadaEncodingChoice.BARAHA
                    else -> KannadaEncodingChoice.UNICODE
                }
                val prepared = if (enc == KannadaEncodingChoice.UNICODE) normalized else {
                    KannadaTextConverter.prepareDocumentForLegacyExport(normalized, if (enc == KannadaEncodingChoice.BARAHA) "BRH Kannada" else "Nudi 01 e")
                }
                val base = storageManager.stripEncodingSuffix(extracted.fileName.substringBeforeLast('.')).ifBlank { "PDF Document" }
                val ingested = storageManager.saveNewFileInKadataDocuments(
                    desiredFileName = "$base (Edited).docx",
                    format = DocFormat.DOCX,
                    mimeType = TargetFileType.DOCX.mimeType,
                    kannadaFormat = enc.shortBadge,
                    isEdited = true
                ) { out -> docxWriter.write(prepared, out, defaultFont = enc.defaultFontForWord) }
                preferences.setDocumentFormatTag(ingested.entity.uriString, enc.shortBadge)
                ingested to normalized.copy(sourceUri = ingested.entity.uriString, fileName = ingested.entity.displayName, sourceFormat = DocFormat.DOCX)
            }.onSuccess { (ingested, boundDoc) ->
                _uiState.value = _uiState.value.copy(isLoading = false, statusMessage = "Created editable copy: ${ingested.entity.displayName}")
                onReady(ingested, boundDoc)
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = err.localizedMessage ?: "Failed to create editable copy from PDF")
            }
        }
    }

    fun saveCrashRecoveryAsCopy(candidate: CrashRecoveryCandidate, onReady: (IngestedDocument) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            runCatching {
                val doc = recoveryVault.loadDocumentFromCheckpointPath(candidate.checkpointPath) ?: error("Recovery checkpoint unavailable.")
                val saveFormat = preferences.settings.value.defaultSaveFormat
                val enc = when (saveFormat.shortLabel) {
                    "Nudi" -> KannadaEncodingChoice.NUDI
                    "Baraha" -> KannadaEncodingChoice.BARAHA
                    else -> KannadaEncodingChoice.UNICODE
                }
                val prepared = if (enc == KannadaEncodingChoice.UNICODE) doc else {
                    KannadaTextConverter.prepareDocumentForLegacyExport(doc, if (enc == KannadaEncodingChoice.BARAHA) "BRH Kannada" else "Nudi 01 e")
                }
                val base = storageManager.stripEncodingSuffix(candidate.documentTitle.substringBeforeLast('.')).ifBlank { "Recovered" }
                val ingested = storageManager.saveNewFileInKadataDocuments(
                    desiredFileName = "${base}_Recovered.docx",
                    format = DocFormat.DOCX,
                    mimeType = TargetFileType.DOCX.mimeType,
                    kannadaFormat = enc.shortBadge,
                    isEdited = true
                ) { out -> docxWriter.write(prepared, out, defaultFont = enc.defaultFontForWord) }
                preferences.setDocumentFormatTag(ingested.entity.uriString, enc.shortBadge)
                recoveryVault.clearCrashRecoveryMarker()
                ingested
            }.onSuccess { ingested ->
                _uiState.value = _uiState.value.copy(isLoading = false, crashRecoveryCandidate = null, statusMessage = "Recovered copy saved as ${ingested.entity.displayName}")
                onReady(ingested)
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = err.localizedMessage ?: "Failed to save recovered copy")
            }
        }
    }

    fun dismissCrashRecovery(clearMarker: Boolean) {
        _uiState.value = _uiState.value.copy(crashRecoveryCandidate = null)
        if (clearMarker) viewModelScope.launch(Dispatchers.IO) { recoveryVault.clearCrashRecoveryMarker() }
    }

    fun renameRecentFile(entity: RecentFileEntity, rawNewName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = storageManager.renameOwnedDocumentFile(
                oldUriString = entity.uriString,
                currentDisplayName = entity.displayName,
                rawNewName = rawNewName,
                recoveryVault = recoveryVault
            )
            result.onSuccess { renamed ->
                if (renamed.uriString != entity.uriString) {
                    val oldTag = preferences.documentFormatMap.value[entity.uriString]
                    if (!oldTag.isNullOrBlank()) {
                        preferences.setDocumentFormatTag(renamed.uriString, oldTag)
                        preferences.removeDocumentFormatTag(entity.uriString)
                    }
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    statusMessage = "Renamed to ${renamed.displayName}"
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = err.localizedMessage ?: "Failed to rename file"
                )
            }
        }
    }

    /**
     * Remove from List ONLY: deletes the Recent Files row and marks in-app version snapshots
     * orphaned (about 3 days grace), leaving the original file on disk completely untouched.
     */
    fun removeRecentFile(uriString: String) {
        viewModelScope.launch {
            runCatching {
                recentFileDao.deleteByUri(uriString)
                draftDao.deleteDraft(uriString)
                preferences.removeDocumentFormatTag(uriString)
                recoveryVault.markDocumentOrphaned(uriString)
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Removed from list (original file kept in storage)"
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = err.localizedMessage ?: "Failed to remove item from list"
                )
            }
        }
    }

    fun shareRecentFileAsIs(entity: RecentFileEntity) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            runCatching {
                val cachedFile = storageManager.copyUriToExportCache(Uri.parse(entity.uriString), entity.displayName)
                val mime = mimeFor(entity.displayName, entity.displayFormat)
                _uiState.value = _uiState.value.copy(isLoading = false)
                ShareHelper.shareFile(appContext, cachedFile, mime, "Share • ಹಂಚಿಕೊಳ್ಳಿ")
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = err.localizedMessage ?: "Unable to share file")
            }
        }
    }

    fun exportRecentFile(
        entity: RecentFileEntity,
        action: ExportAction,
        encoding: KannadaEncodingChoice,
        fileType: TargetFileType,
        onRequestSafCreateFile: (CreateDocumentRequest) -> Unit
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            runCatching {
                val unicodeDoc = loadUnicodeDocumentFor(entity)
                val preparedDoc = if (fileType.forceUniversalUnicode || encoding == KannadaEncodingChoice.UNICODE) unicodeDoc else {
                    KannadaTextConverter.prepareDocumentForLegacyExport(unicodeDoc, if (encoding == KannadaEncodingChoice.BARAHA) "BRH Kannada" else "Nudi 01 e")
                }
                val cleanBase = storageManager.stripEncodingSuffix(entity.displayName.substringBeforeLast('.')).ifBlank { "Document" }
                val suffix = when (fileType) {
                    TargetFileType.HTML_ZIP -> "Web"
                    TargetFileType.GOOGLE_DOCS -> "GoogleDocs"
                    else -> if (fileType.forceUniversalUnicode) "Universal" else encoding.suffix
                }
                val formatTag = if (fileType.forceUniversalUnicode) "Universal" else encoding.shortBadge

                if (action == ExportAction.SHARE_DIFFERENT) {
                    val (registered, shareableFile) = storageManager.saveExportedFileInKadataDocuments(
                        cleanBase, suffix, fileType.extension, fileType.docFormat, fileType.mimeType, formatTag
                    ) { out -> writeDocumentToStream(preparedDoc, encoding, fileType, out) }
                    preferences.setDocumentFormatTag(registered.uriString, formatTag)
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    if (fileType == TargetFileType.GOOGLE_DOCS) {
                        if (!GoogleDocsShareHelper.openOrShareToGoogleDocs(appContext, shareableFile)) {
                            _uiState.value = _uiState.value.copy(statusMessage = "Google Docs app not installed — opened Android Share sheet instead.")
                        }
                    } else {
                        ShareHelper.shareFile(appContext, shareableFile, fileType.mimeType, "Share ${fileType.label}")
                    }
                } else {
                    val sourceUri = Uri.parse(entity.uriString)
                    val suggested = "${cleanBase}_${suffix}.${fileType.extension}"
                    val parentUri = FileLocationHelper.resolveParentDocumentUri(appContext, sourceUri, entity.displayName)
                    persistPendingSafExport(preparedDoc, encoding, fileType, suggested)
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    onRequestSafCreateFile(CreateDocumentRequest(suggested, fileType.mimeType, parentUri))
                }
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = err.localizedMessage ?: "Export failed")
            }
        }
    }

    fun writePendingSafCopy(targetUri: Uri) {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { loadPendingSafExport() } ?: return@launch
            val (doc, encoding, fileType, suggestedName) = loaded
            _uiState.value = _uiState.value.copy(isLoading = true)
            runCatching {
                withContext(Dispatchers.IO + NonCancellable) {
                    storageManager.writeStagedToUri(targetUri) { out -> writeDocumentToStream(doc, encoding, fileType, out) }
                }
                val formatTag = if (fileType.forceUniversalUnicode) "Universal" else encoding.shortBadge
                val registered = storageManager.registerSafSavedUri(targetUri, suggestedName, fileType.docFormat, fileType.mimeType, formatTag)
                preferences.setDocumentFormatTag(registered.uriString, formatTag)
            }.onSuccess {
                _uiState.value = _uiState.value.copy(isLoading = false, statusMessage = "Saved ${encoding.labelEn} (.${fileType.extension}) & added to Recent Files")
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = err.localizedMessage ?: "Failed to save copy")
            }
        }
    }

    private suspend fun writeDocumentToStream(doc: ParsedDocument, encoding: KannadaEncodingChoice, fileType: TargetFileType, out: java.io.OutputStream) {
        when (fileType) {
            TargetFileType.PDF -> pdfExporter.exportToPdf(doc, doc.fileName).inputStream().use { it.copyTo(out) }
            TargetFileType.DOCX, TargetFileType.GOOGLE_DOCS -> docxWriter.write(doc, out, defaultFont = if (fileType == TargetFileType.GOOGLE_DOCS) "Noto Sans Kannada" else encoding.defaultFontForWord)
            TargetFileType.DOC, TargetFileType.RTF -> rtfDocWriter.write(doc, out, defaultFont = encoding.defaultFontForWord)
            TargetFileType.ODT -> odtWriter.write(doc, out, defaultFont = encoding.defaultFontForWord)
            TargetFileType.TXT -> txtWriter.write(doc, out)
            TargetFileType.HTML_ZIP -> htmlZipWriter.writeZippedWebPage(doc, out)
            TargetFileType.EPUB -> epubWriter.write(doc, out)
        }
    }

    private suspend fun persistPendingSafExport(doc: ParsedDocument, encoding: KannadaEncodingChoice, fileType: TargetFileType, suggestedName: String) =
        withContext(Dispatchers.IO) {
            pendingSafDocFile.writeText(DocumentJson.toJson(doc), Charsets.UTF_8)
            pendingSafMetaFile.writeText(JSONObject().apply {
                put("encoding", encoding.name); put("fileType", fileType.name); put("suggestedName", suggestedName)
            }.toString(), Charsets.UTF_8)
        }

    private data class PendingSafPayload(val doc: ParsedDocument, val encoding: KannadaEncodingChoice, val fileType: TargetFileType, val suggestedName: String)

    private fun loadPendingSafExport(): PendingSafPayload? {
        if (!pendingSafMetaFile.exists() || !pendingSafDocFile.exists()) return null
        return runCatching {
            val meta = JSONObject(pendingSafMetaFile.readText(Charsets.UTF_8))
            val doc = DocumentJson.fromJson(pendingSafDocFile.readText(Charsets.UTF_8))
            val enc = runCatching { KannadaEncodingChoice.valueOf(meta.getString("encoding")) }.getOrDefault(KannadaEncodingChoice.NUDI)
            val ft = runCatching { TargetFileType.valueOf(meta.getString("fileType")) }.getOrDefault(TargetFileType.DOCX)
            val suggested = meta.optString("suggestedName", "Document.docx")
            pendingSafMetaFile.delete(); pendingSafDocFile.delete()
            PendingSafPayload(doc, enc, ft, suggested)
        }.getOrNull()
    }

    private suspend fun loadUnicodeDocumentFor(entity: RecentFileEntity): ParsedDocument = withContext(Dispatchers.IO) {
        val parsed = parserFacade.parseDocument(appContext, Uri.parse(entity.uriString), entity.displayName, entity.detectedFormat).getOrThrow()
        KannadaTextConverter.normalizeDocumentToUnicode(parsed)
    }

    private fun mimeFor(fileName: String, format: DocFormat): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            ext == "pdf" || format == DocFormat.PDF -> "application/pdf"
            ext == "doc" || format == DocFormat.DOC -> "application/msword"
            ext == "odt" || format == DocFormat.ODT -> "application/vnd.oasis.opendocument.text"
            ext == "rtf" || format == DocFormat.RTF -> "application/rtf"
            ext == "txt" || format == DocFormat.TXT -> "text/plain"
            ext == "zip" && format == DocFormat.HTML -> "application/zip"
            ext == "html" || ext == "htm" || format == DocFormat.HTML -> "text/html"
            ext == "epub" || format == DocFormat.EPUB -> "application/epub+zip"
            else -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        }
    }

    fun showInfoMessage(message: String) { _uiState.value = _uiState.value.copy(statusMessage = message) }
    fun clearError() { _uiState.value = _uiState.value.copy(errorMessage = null) }
    fun clearStatus() { _uiState.value = _uiState.value.copy(statusMessage = null) }
    fun dismissFileRejectionAlert() { _uiState.value = _uiState.value.copy(fileRejectionAlert = null) }

    class Factory(
        private val appContext: Context,
        private val storageManager: SafStorageManager,
        private val recoveryVault: RecoveryVaultManager,
        private val recentFileDao: RecentFileDao,
        private val draftDao: DraftDao,
        private val preferences: AppPreferences,
        private val parserFacade: DocumentParserFacade,
        private val docxWriter: DocxWriter,
        private val rtfDocWriter: RtfDocWriter,
        private val pdfExporter: PdfExporter,
        private val odtWriter: OdtWriter = OdtWriter(),
        private val txtWriter: TxtWriter = TxtWriter(),
        private val htmlZipWriter: HtmlZipWriter = HtmlZipWriter(),
        private val epubWriter: EpubWriter = EpubWriter(htmlZipWriter)
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(
            appContext, storageManager, recoveryVault, recentFileDao, draftDao, preferences,
            parserFacade, docxWriter, rtfDocWriter, pdfExporter, odtWriter, txtWriter, htmlZipWriter, epubWriter
        ) as T
    }
}
