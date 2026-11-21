package com.kannadadoc.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kannadadoc.converter.KannadaTextConverter
import com.kannadadoc.data.local.DraftDao
import com.kannadadoc.data.local.RecentFileDao
import com.kannadadoc.data.local.RecentFileEntity
import com.kannadadoc.data.storage.IngestedDocument
import com.kannadadoc.data.storage.SafStorageManager
import com.kannadadoc.data.storage.UnsupportedFormatException
import com.kannadadoc.export.DocxWriter
import com.kannadadoc.export.PdfExporter
import com.kannadadoc.export.RtfDocWriter
import com.kannadadoc.export.ShareHelper
import com.kannadadoc.parser.DocFormat
import com.kannadadoc.parser.DocumentJson
import com.kannadadoc.parser.DocumentParserFacade
import com.kannadadoc.parser.ParsedDocument
import com.kannadadoc.ui.editor.ExportAction
import com.kannadadoc.ui.editor.KannadaEncodingChoice
import com.kannadadoc.ui.editor.TargetFileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

enum class LibraryFilter(val labelEn: String, val labelKn: String) {
    ALL("All", "ಎಲ್ಲಾ"),
    WORD("Word", "ವರ್ಡ್"),
    PDF("PDF", "ಪಿಡಿಎಫ್"),
    TEXT("Text", "ಟೆಕ್ಸ್ಟ್"),
    EDITED("Edited", "ತಿದ್ದುಪಡಿ")
}

data class HomeUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val unsupportedFormatName: String? = null
)

class HomeViewModel(
    private val appContext: Context,
    private val storageManager: SafStorageManager,
    private val recentFileDao: RecentFileDao,
    private val draftDao: DraftDao,
    private val parserFacade: DocumentParserFacade,
    private val docxWriter: DocxWriter,
    private val rtfDocWriter: RtfDocWriter,
    private val pdfExporter: PdfExporter
) : ViewModel() {

    companion object {
        private val INTERNAL_HEX_PREFIX = Regex("^[0-9a-f]{12}_", RegexOption.IGNORE_CASE)
    }

    private val _selectedFilter = MutableStateFlow(LibraryFilter.ALL)
    val selectedFilter: StateFlow<LibraryFilter> = _selectedFilter.asStateFlow()

    val recentFiles: StateFlow<List<RecentFileEntity>> = combine(
        recentFileDao.observeRecentFiles(),
        _selectedFilter
    ) { files, filter ->
        when (filter) {
            LibraryFilter.ALL -> files
            LibraryFilter.WORD -> files.filter { it.detectedFormat == DocFormat.DOCX || it.detectedFormat == DocFormat.DOC }
            LibraryFilter.PDF -> files.filter { it.detectedFormat == DocFormat.PDF }
            LibraryFilter.TEXT -> files.filter { it.detectedFormat == DocFormat.TXT || it.detectedFormat == DocFormat.RTF }
            LibraryFilter.EDITED -> files.filter { it.savedUnicodeUri != null }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var pendingSafDoc: ParsedDocument? = null
    private var pendingSafEncoding: KannadaEncodingChoice = KannadaEncodingChoice.NUDI
    private var pendingSafFileType: TargetFileType = TargetFileType.DOCX

    init {
        // Silently clean any old 12-hex-char internal prefixes stored in earlier builds
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val existing = recentFileDao.observeRecentFiles(100).first()
                for (item in existing) {
                    if (INTERNAL_HEX_PREFIX.containsMatchIn(item.displayName)) {
                        val cleaned = item.displayName.replace(INTERNAL_HEX_PREFIX, "")
                        if (cleaned.isNotBlank()) {
                            recentFileDao.updateDisplayName(item.uriString, cleaned)
                            draftDao.updateTitle(item.uriString, cleaned)
                        }
                    }
                }
            }
        }
    }

    fun setFilter(filter: LibraryFilter) {
        _selectedFilter.value = filter
    }

    fun openUri(
        uri: Uri,
        intentFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION,
        titleHint: String? = null,
        onDocumentReady: (IngestedDocument) -> Unit
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                errorMessage = null,
                unsupportedFormatName = null
            )
            storageManager.ingestDocument(uri, intentFlags, titleHint)
                .onSuccess { ingested ->
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    onDocumentReady(ingested)
                }
                .onFailure { error ->
                    if (error is UnsupportedFormatException) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            unsupportedFormatName = error.formatName
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            errorMessage = error.localizedMessage ?: "Failed to open document"
                        )
                    }
                }
        }
    }

    fun createNewBlankDocument(
        title: String,
        onCreated: (IngestedDocument) -> Unit
    ) {
        viewModelScope.launch {
            val doc = storageManager.registerNewBlankDocument(title)
            onCreated(doc)
        }
    }

    fun renameRecentFile(entity: RecentFileEntity, rawNewName: String) {
        val trimmed = rawNewName.trim()
        if (trimmed.isEmpty()) return
        val existingExt = entity.displayName.substringAfterLast('.', "")
        val finalName = if (existingExt.isNotEmpty() && !trimmed.contains('.')) {
            "$trimmed.$existingExt"
        } else {
            trimmed
        }
        viewModelScope.launch {
            recentFileDao.updateDisplayName(entity.uriString, finalName)
            draftDao.updateTitle(entity.uriString, finalName)
        }
    }

    fun removeRecentFile(uriString: String) {
        viewModelScope.launch {
            recentFileDao.deleteByUri(uriString)
            draftDao.deleteDraft(uriString)
        }
    }

    fun shareRecentFileAsIs(entity: RecentFileEntity) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            runCatching {
                val uriToShare = Uri.parse(entity.savedUnicodeUri ?: entity.uriString)
                val cachedFile = storageManager.copyUriToExportCache(uriToShare, entity.displayName)
                val mime = mimeFor(entity.displayName, entity.detectedFormat)
                _uiState.value = _uiState.value.copy(isLoading = false)
                ShareHelper.shareFile(
                    context = appContext,
                    file = cachedFile,
                    mimeType = mime,
                    chooserTitle = "Share • ಹಂಚಿಕೊಳ್ಳಿ"
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = err.localizedMessage ?: "Unable to share file"
                )
            }
        }
    }

    /**
     * Performs 2-step Share Different Format or Save As directly from the Home card 3-dot menu.
     */
    fun exportRecentFile(
        entity: RecentFileEntity,
        action: ExportAction,
        encoding: KannadaEncodingChoice,
        fileType: TargetFileType,
        onRequestSafCreateFile: (suggestedName: String, mimeType: String) -> Unit
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            runCatching {
                val unicodeDoc = loadUnicodeDocumentFor(entity)
                val preparedDoc = if (fileType == TargetFileType.PDF || encoding == KannadaEncodingChoice.UNICODE) {
                    unicodeDoc
                } else {
                    val legacyFont = if (encoding == KannadaEncodingChoice.BARAHA) "BRH Kannada" else "Nudi 01 e"
                    KannadaTextConverter.prepareDocumentForLegacyExport(unicodeDoc, legacyFont)
                }

                val base = entity.displayName.substringBeforeLast('.').ifBlank { "Document" }

                if (action == ExportAction.SHARE_DIFFERENT) {
                    val outFile = withContext(Dispatchers.IO) {
                        when (fileType) {
                            TargetFileType.PDF -> pdfExporter.exportToPdf(preparedDoc, "${base}_${encoding.suffix}")
                            TargetFileType.DOCX -> {
                                val f = storageManager.resolveExportCacheFile(base, encoding.suffix, "docx")
                                FileOutputStream(f).use { fos ->
                                    docxWriter.write(preparedDoc, fos, defaultFont = encoding.defaultFontForWord)
                                }
                                f
                            }
                            TargetFileType.DOC -> {
                                val f = storageManager.resolveExportCacheFile(base, encoding.suffix, "doc")
                                FileOutputStream(f).use { fos ->
                                    rtfDocWriter.write(preparedDoc, fos, defaultFont = encoding.defaultFontForWord)
                                }
                                f
                            }
                        }
                    }
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    ShareHelper.shareFile(
                        context = appContext,
                        file = outFile,
                        mimeType = fileType.mimeType,
                        chooserTitle = "Share ${encoding.labelEn} (${fileType.extension.uppercase()})"
                    )
                } else {
                    pendingSafDoc = preparedDoc
                    pendingSafEncoding = encoding
                    pendingSafFileType = fileType
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    onRequestSafCreateFile("${base}_${encoding.suffix}.${fileType.extension}", fileType.mimeType)
                }
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = err.localizedMessage ?: "Export failed"
                )
            }
        }
    }

    fun writePendingSafCopy(targetUri: Uri) {
        val doc = pendingSafDoc ?: return
        val encoding = pendingSafEncoding
        val fileType = pendingSafFileType
        pendingSafDoc = null

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            runCatching {
                withContext(Dispatchers.IO) {
                    when (fileType) {
                        TargetFileType.PDF -> {
                            val tempPdf = pdfExporter.exportToPdf(doc, doc.fileName)
                            appContext.contentResolver.openOutputStream(targetUri)?.use { out ->
                                tempPdf.inputStream().use { input -> input.copyTo(out) }
                            } ?: error("Cannot write PDF")
                        }
                        TargetFileType.DOCX -> {
                            appContext.contentResolver.openOutputStream(targetUri)?.use { out ->
                                docxWriter.write(doc, out, defaultFont = encoding.defaultFontForWord)
                            } ?: error("Cannot write DOCX")
                        }
                        TargetFileType.DOC -> {
                            appContext.contentResolver.openOutputStream(targetUri)?.use { out ->
                                rtfDocWriter.write(doc, out, defaultFont = encoding.defaultFontForWord)
                            } ?: error("Cannot write DOC")
                        }
                    }
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    statusMessage = "Saved ${encoding.labelEn} (.${fileType.extension}) copy successfully"
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = err.localizedMessage ?: "Failed to save copy"
                )
            }
        }
    }

    private suspend fun loadUnicodeDocumentFor(entity: RecentFileEntity): ParsedDocument =
        withContext(Dispatchers.IO) {
            val draft = draftDao.getDraft(entity.uriString)
            if (draft != null && draft.draftIdmJson.isNotBlank()) {
                val parsedDraft = runCatching { DocumentJson.fromJson(draft.draftIdmJson) }.getOrNull()
                if (parsedDraft != null) return@withContext parsedDraft
            }
            val uri = Uri.parse(entity.uriString)
            val parsed = parserFacade.parseDocument(appContext, uri, entity.displayName, entity.detectedFormat)
                .getOrThrow()
            KannadaTextConverter.normalizeDocumentToUnicode(parsed)
        }

    private fun mimeFor(fileName: String, format: DocFormat): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            ext == "pdf" || format == DocFormat.PDF -> "application/pdf"
            ext == "doc" || format == DocFormat.DOC -> "application/msword"
            ext == "rtf" || format == DocFormat.RTF -> "application/rtf"
            ext == "txt" || format == DocFormat.TXT -> "text/plain"
            else -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        }
    }

    fun showInfoMessage(message: String) {
        _uiState.value = _uiState.value.copy(statusMessage = message)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun clearStatus() {
        _uiState.value = _uiState.value.copy(statusMessage = null)
    }

    fun dismissUnsupportedFormatAlert() {
        _uiState.value = _uiState.value.copy(unsupportedFormatName = null)
    }

    class Factory(
        private val appContext: Context,
        private val storageManager: SafStorageManager,
        private val recentFileDao: RecentFileDao,
        private val draftDao: DraftDao,
        private val parserFacade: DocumentParserFacade,
        private val docxWriter: DocxWriter,
        private val rtfDocWriter: RtfDocWriter,
        private val pdfExporter: PdfExporter
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(
                appContext,
                storageManager,
                recentFileDao,
                draftDao,
                parserFacade,
                docxWriter,
                rtfDocWriter,
                pdfExporter
            ) as T
        }
    }
}
