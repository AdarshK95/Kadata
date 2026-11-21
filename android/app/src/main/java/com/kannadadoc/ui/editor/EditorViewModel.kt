package com.kannadadoc.ui.editor

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kannadadoc.data.local.DraftDao
import com.kannadadoc.data.local.DraftEntity
import com.kannadadoc.data.local.RecentFileDao
import com.kannadadoc.data.storage.SafStorageManager
import com.kannadadoc.export.DocxWriter
import com.kannadadoc.export.PdfExporter
import com.kannadadoc.export.RtfDocWriter
import com.kannadadoc.export.ShareHelper
import com.kannadadoc.parser.DocBlock
import com.kannadadoc.parser.DocFormat
import com.kannadadoc.parser.DocumentJson
import com.kannadadoc.parser.DocumentParserFacade
import com.kannadadoc.parser.ParsedDocument
import com.kannadadoc.parser.TextRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class ExportAction {
    SHARE_DIFFERENT,
    SAVE_COPY
}

enum class KannadaEncodingChoice(
    val id: String,
    val labelEn: String,
    val labelKn: String,
    val suffix: String,
    val defaultFontForWord: String
) {
    NUDI("nudi", "Nudi", "ನುಡಿ", "Nudi", "Calibri"),
    BARAHA("baraha", "Baraha", "ಬರಹ", "Baraha", "Calibri"),
    UNICODE("unicode", "Modern Unicode", "ಆಧುನಿಕ ಯೂನಿಕೋಡ್", "Unicode", "Noto Sans Kannada")
}

enum class TargetFileType(
    val id: String,
    val extension: String,
    val label: String,
    val mimeType: String
) {
    DOC("doc", "doc", ".doc (Word 97–2003)", "application/msword"),
    DOCX("docx", "docx", ".docx (Word)", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    PDF("pdf", "pdf", ".pdf (PDF)", "application/pdf")
}

data class DocumentInfo(
    val fileName: String = "",
    val fileFormat: DocFormat = DocFormat.DOCX,
    val detectedEncodingLabel: String = "Unicode Kannada",
    val fontFamiliesFound: List<String> = emptyList(),
    val hasLegacyKannada: Boolean = false,
    val hasUnicodeKannada: Boolean = false,
    val hasEnglishText: Boolean = false
)

data class EditorUiState(
    val uriString: String = "",
    val documentTitle: String = "",
    val isLoading: Boolean = true,
    val parsedDocJson: String? = null,
    val savedDraftHtml: String? = null,
    val activeConverter: String = "auto",
    val isOriginalView: Boolean = false,
    val fontSizePt: Int = 18,
    val showFormattingStrip: Boolean = true,
    val conversionBannerText: String? = null,
    val warningMessage: String? = null,
    val statusMessage: String? = null,
    val isSaving: Boolean = false,
    val isDirty: Boolean = false,
    val showSavedTick: Boolean = false,
    val lastSavedLabel: String? = null,
    val documentInfo: DocumentInfo = DocumentInfo()
)

class EditorViewModel(
    private val appContext: Context,
    private val parserFacade: DocumentParserFacade,
    private val storageManager: SafStorageManager,
    private val recentFileDao: RecentFileDao,
    private val draftDao: DraftDao,
    private val docxWriter: DocxWriter,
    private val rtfDocWriter: RtfDocWriter,
    private val pdfExporter: PdfExporter
) : ViewModel() {

    companion object {
        private val STALE_GLITCH_REGEX = Regex("[\\u00C0-\\u00C9\\u00F0\\u00F6\\u00F7\\u00F8\\u00FC]")
        private val KANNADA_UNICODE_REGEX = Regex("[\\u0C80-\\u0CFF]")
        private val HIGH_ASCII_NUDI_REGEX = Regex("[\\u00A2-\\u00FF]")
        private val LATIN_WORD_REGEX = Regex("[A-Za-z]{2,}")

        private val NUDI_FONT_REGEX = Regex("(^nudi|nudi[\\s\\-_]*\\d|kgp|kan[\\-_]?nudi)", RegexOption.IGNORE_CASE)
        private val BARAHA_FONT_REGEX = Regex("(^brh|baraha|tunga[\\s\\-_]*brh)", RegexOption.IGNORE_CASE)
        private val SHREELIPI_FONT_REGEX = Regex("(shree[\\-_]?kan|srilipi|shree.*deccan)", RegexOption.IGNORE_CASE)
        private val AKRUTI_FONT_REGEX = Regex("(akruti|akrutikan)", RegexOption.IGNORE_CASE)
    }

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var pendingSafSaveDoc: ParsedDocument? = null
    private var pendingSafEncoding: KannadaEncodingChoice = KannadaEncodingChoice.NUDI
    private var pendingSafFileType: TargetFileType = TargetFileType.DOCX

    fun loadExistingDocument(
        uri: Uri,
        fileName: String,
        format: DocFormat,
        initialFontSizePt: Int
    ) {
        viewModelScope.launch {
            _uiState.value = EditorUiState(
                uriString = uri.toString(),
                documentTitle = fileName,
                isLoading = true,
                fontSizePt = initialFontSizePt,
                isDirty = false,
                showSavedTick = false,
                documentInfo = DocumentInfo(fileName = fileName, fileFormat = format)
            )

            val recent = recentFileDao.getByUri(uri.toString())
            val existingDraft = draftDao.getDraft(uri.toString())

            val usableDraftHtml = existingDraft?.draftHtml?.takeUnless { html ->
                STALE_GLITCH_REGEX.containsMatchIn(html)
            }

            val parseResult = parserFacade.parseDocument(appContext, uri, fileName, format)
            parseResult
                .onSuccess { parsed ->
                    val json = DocumentJson.toJson(parsed)
                    val info = inspectDocumentInfo(parsed)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        parsedDocJson = json,
                        savedDraftHtml = usableDraftHtml,
                        activeConverter = recent?.converterOverride ?: existingDraft?.converterUsed ?: "auto",
                        warningMessage = parsed.warningMessage,
                        isDirty = false,
                        documentInfo = info
                    )
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        statusMessage = err.localizedMessage ?: "Failed to open file"
                    )
                }
        }
    }

    fun loadPreParsedDocument(parsed: ParsedDocument, initialFontSizePt: Int) {
        viewModelScope.launch {
            val json = DocumentJson.toJson(parsed)
            val info = inspectDocumentInfo(parsed)
            _uiState.value = EditorUiState(
                uriString = parsed.sourceUri,
                documentTitle = parsed.fileName,
                isLoading = false,
                parsedDocJson = json,
                savedDraftHtml = null,
                activeConverter = "auto",
                fontSizePt = initialFontSizePt,
                warningMessage = parsed.warningMessage,
                isDirty = false,
                showSavedTick = false,
                documentInfo = info
            )
        }
    }

    fun onWebDocumentLoaded(convertedRuns: Int, detectedSummary: String, restoredFromDraft: Boolean) {
        val banner = when {
            restoredFromDraft -> "Resumed your previous draft"
            convertedRuns > 0 -> {
                val label = detectedSummary.ifBlank { "Nudi / ASCII" }
                "Kannada text automatically formatted ($label)"
            }
            else -> null
        }
        _uiState.value = _uiState.value.copy(
            conversionBannerText = banner,
            isDirty = false
        )
    }

    fun markDirty() {
        if (!_uiState.value.isDirty || _uiState.value.showSavedTick) {
            _uiState.value = _uiState.value.copy(
                isDirty = true,
                showSavedTick = false
            )
        }
    }

    fun clearSavedTick() {
        if (_uiState.value.showSavedTick) {
            _uiState.value = _uiState.value.copy(showSavedTick = false)
        }
    }

    fun dismissConversionBanner() {
        _uiState.value = _uiState.value.copy(conversionBannerText = null)
    }

    fun dismissWarning() {
        _uiState.value = _uiState.value.copy(warningMessage = null)
    }

    fun clearStatusMessage() {
        _uiState.value = _uiState.value.copy(statusMessage = null)
    }

    fun reportEditorError(message: String) {
        _uiState.value = _uiState.value.copy(isLoading = false, statusMessage = message)
    }

    fun toggleFormattingStrip() {
        _uiState.value = _uiState.value.copy(
            showFormattingStrip = !_uiState.value.showFormattingStrip
        )
    }

    fun adjustFontSize(deltaPt: Int): Int {
        val next = (_uiState.value.fontSizePt + deltaPt).coerceIn(14, 34)
        _uiState.value = _uiState.value.copy(fontSizePt = next)
        return next
    }

    fun setOriginalView(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isOriginalView = enabled)
    }

    fun selectConverter(converterId: String) {
        val uriStr = _uiState.value.uriString
        _uiState.value = _uiState.value.copy(
            activeConverter = converterId,
            isOriginalView = false,
            isDirty = true
        )
        if (uriStr.isNotBlank()) {
            viewModelScope.launch {
                recentFileDao.updateConverterOverride(uriStr, converterId)
            }
        }
    }

    fun onDraftChanged(html: String, idmJson: String) {
        val state = _uiState.value
        if (state.uriString.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            draftDao.upsert(
                DraftEntity(
                    uriString = state.uriString,
                    documentTitle = state.documentTitle,
                    draftHtml = html,
                    draftIdmJson = idmJson,
                    converterUsed = state.activeConverter,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Shares the existing file as-is without converting to another format.
     * Prefers the saved copy if this file was already saved; otherwise shares the original file.
     */
    fun shareExistingFileAsIs() {
        val state = _uiState.value
        if (state.uriString.isBlank()) {
            _uiState.value = state.copy(statusMessage = "Please save the document first before sharing.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            runCatching {
                val recent = withContext(Dispatchers.IO) {
                    recentFileDao.getByUri(state.uriString)
                }
                val uriToShare = Uri.parse(recent?.savedUnicodeUri ?: state.uriString)
                val targetName = state.documentTitle.ifBlank { recent?.displayName ?: "Document.docx" }
                val cachedFile = storageManager.copyUriToExportCache(uriToShare, targetName)
                val mime = mimeForFileName(targetName, state.documentInfo.fileFormat)
                _uiState.value = _uiState.value.copy(isSaving = false)
                ShareHelper.shareFile(
                    context = appContext,
                    file = cachedFile,
                    mimeType = mime,
                    chooserTitle = "Share • ಹಂಚಿಕೊಳ್ಳಿ"
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    statusMessage = err.localizedMessage ?: "Unable to share file"
                )
            }
        }
    }

    /**
     * Handles exported IDM JSON from the WebView for:
     * - Default Save / Auto-Save (save_default_nudi / save_default_baraha / save_default_unicode)
     * - 2-Step Share Different Format (share_<encoding>_<ext>)
     * - 2-Step Save a Copy (savecopy_<encoding>_<ext>)
     */
    fun handleExportPayload(
        exportMode: String,
        idmJson: String,
        isAutoSave: Boolean,
        onRequestSafCreateFile: (suggestedFileName: String, mimeType: String) -> Unit
    ) {
        viewModelScope.launch {
            if (!isAutoSave) {
                _uiState.value = _uiState.value.copy(isSaving = true)
            }
            runCatching {
                val parsedDoc = DocumentJson.fromJson(idmJson)
                val title = _uiState.value.documentTitle.ifBlank { "Document.docx" }
                val base = title.substringBeforeLast('.')

                when {
                    // 1. Primary Save / Auto-Save modes
                    exportMode == "save_default_nudi" -> {
                        val targetFile = storageManager.resolveSavedDocxFile(title, "Nudi")
                        writeInternalDocx(parsedDoc, targetFile, defaultFont = "Calibri")
                        markSaveComplete(
                            label = "Saved (Nudi)",
                            snackbar = if (isAutoSave) null else "Saved as ${targetFile.name} (Nudi)"
                        )
                    }

                    exportMode == "save_default_baraha" -> {
                        val targetFile = storageManager.resolveSavedDocxFile(title, "Baraha")
                        writeInternalDocx(parsedDoc, targetFile, defaultFont = "Calibri")
                        markSaveComplete(
                            label = "Saved (Baraha)",
                            snackbar = if (isAutoSave) null else "Saved as ${targetFile.name} (Baraha)"
                        )
                    }

                    exportMode == "save_default_unicode" || exportMode == "save_internal" -> {
                        val targetFile = storageManager.resolveSavedDocxFile(title, "Unicode")
                        writeInternalDocx(parsedDoc, targetFile, defaultFont = "Noto Sans Kannada")
                        markSaveComplete(
                            label = "Saved (Unicode)",
                            snackbar = if (isAutoSave) null else "Saved as ${targetFile.name} (Unicode)"
                        )
                    }

                    // 2. Two-step Share Different Format: share_<encoding>_<ext>
                    exportMode.startsWith("share_") -> {
                        val (encoding, fileType) = parseTwoStepMode(exportMode.removePrefix("share_"))
                        val outFile = exportToCacheFile(parsedDoc, base, encoding, fileType)
                        _uiState.value = _uiState.value.copy(isSaving = false)
                        ShareHelper.shareFile(
                            context = appContext,
                            file = outFile,
                            mimeType = fileType.mimeType,
                            chooserTitle = "Share ${encoding.labelEn} (${fileType.extension.uppercase()})"
                        )
                    }

                    // 3. Two-step Save a Copy: savecopy_<encoding>_<ext>
                    exportMode.startsWith("savecopy_") -> {
                        val (encoding, fileType) = parseTwoStepMode(exportMode.removePrefix("savecopy_"))
                        pendingSafSaveDoc = parsedDoc
                        pendingSafEncoding = encoding
                        pendingSafFileType = fileType
                        _uiState.value = _uiState.value.copy(isSaving = false)
                        val suggested = "${base}_${encoding.suffix}.${fileType.extension}"
                        onRequestSafCreateFile(suggested, fileType.mimeType)
                    }

                    else -> {
                        _uiState.value = _uiState.value.copy(isSaving = false)
                    }
                }
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    statusMessage = if (isAutoSave) null else (err.localizedMessage ?: "Export failed")
                )
            }
        }
    }

    private fun markSaveComplete(label: String, snackbar: String?) {
        _uiState.value = _uiState.value.copy(
            isSaving = false,
            isDirty = false,
            showSavedTick = true,
            lastSavedLabel = label,
            statusMessage = snackbar
        )
    }

    private fun parseTwoStepMode(suffix: String): Pair<KannadaEncodingChoice, TargetFileType> {
        val parts = suffix.split("_")
        val encId = parts.getOrNull(0) ?: "nudi"
        val extId = parts.getOrNull(1) ?: "docx"
        val encoding = KannadaEncodingChoice.entries.firstOrNull { it.id == encId }
            ?: KannadaEncodingChoice.NUDI
        val fileType = TargetFileType.entries.firstOrNull { it.id == extId }
            ?: TargetFileType.DOCX
        return encoding to fileType
    }

    private suspend fun exportToCacheFile(
        parsedDoc: ParsedDocument,
        baseName: String,
        encoding: KannadaEncodingChoice,
        fileType: TargetFileType
    ): File = withContext(Dispatchers.IO) {
        when (fileType) {
            TargetFileType.PDF -> pdfExporter.exportToPdf(parsedDoc, "${baseName}_${encoding.suffix}")
            TargetFileType.DOCX -> {
                val out = storageManager.resolveExportCacheFile(baseName, encoding.suffix, "docx")
                FileOutputStream(out).use { fos ->
                    docxWriter.write(parsedDoc, fos, defaultFont = encoding.defaultFontForWord)
                }
                out
            }
            TargetFileType.DOC -> {
                val out = storageManager.resolveExportCacheFile(baseName, encoding.suffix, "doc")
                FileOutputStream(out).use { fos ->
                    rtfDocWriter.write(parsedDoc, fos, defaultFont = encoding.defaultFontForWord)
                }
                out
            }
        }
    }

    fun writePendingSafCopy(targetUri: Uri) {
        val doc = pendingSafSaveDoc ?: return
        val encoding = pendingSafEncoding
        val fileType = pendingSafFileType
        pendingSafSaveDoc = null

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            runCatching {
                withContext(Dispatchers.IO) {
                    when (fileType) {
                        TargetFileType.PDF -> {
                            val tempPdf = pdfExporter.exportToPdf(doc, _uiState.value.documentTitle)
                            appContext.contentResolver.openOutputStream(targetUri)?.use { out ->
                                tempPdf.inputStream().use { input -> input.copyTo(out) }
                            } ?: error("Cannot write PDF to selected location")
                        }
                        TargetFileType.DOCX -> {
                            appContext.contentResolver.openOutputStream(targetUri)?.use { out ->
                                docxWriter.write(doc, out, defaultFont = encoding.defaultFontForWord)
                            } ?: error("Cannot write DOCX to selected location")
                        }
                        TargetFileType.DOC -> {
                            appContext.contentResolver.openOutputStream(targetUri)?.use { out ->
                                rtfDocWriter.write(doc, out, defaultFont = encoding.defaultFontForWord)
                            } ?: error("Cannot write DOC to selected location")
                        }
                    }
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    statusMessage = "Saved ${encoding.labelEn} (.${fileType.extension}) copy successfully"
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    statusMessage = err.localizedMessage ?: "Failed to save copy"
                )
            }
        }
    }

    private suspend fun writeInternalDocx(
        parsedDoc: ParsedDocument,
        targetFile: File,
        defaultFont: String
    ) = withContext(Dispatchers.IO) {
        FileOutputStream(targetFile).use { fos ->
            docxWriter.write(parsedDoc, fos, defaultFont = defaultFont)
        }
        val savedUri = storageManager.uriForFile(targetFile).toString()
        if (_uiState.value.uriString.isNotBlank()) {
            recentFileDao.updateSavedUnicodeUri(
                uriString = _uiState.value.uriString,
                savedUri = savedUri,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    private fun mimeForFileName(fileName: String, format: DocFormat): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            ext == "pdf" || format == DocFormat.PDF -> "application/pdf"
            ext == "doc" || format == DocFormat.DOC -> "application/msword"
            ext == "rtf" || format == DocFormat.RTF -> "application/rtf"
            ext == "txt" || format == DocFormat.TXT -> "text/plain"
            else -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        }
    }

    private fun inspectDocumentInfo(doc: ParsedDocument): DocumentInfo {
        val allRuns = mutableListOf<TextRun>()
        for (block in doc.blocks) {
            when (block) {
                is DocBlock.Paragraph -> allRuns.addAll(block.runs)
                is DocBlock.Table -> {
                    for (row in block.rows) {
                        for (cell in row) {
                            for (p in cell) allRuns.addAll(p.runs)
                        }
                    }
                }
            }
        }

        val fontFamilies = linkedSetOf<String>()
        val detectedCategories = linkedSetOf<String>()
        var hasLegacy = false
        var hasUnicode = false
        var hasEnglish = false

        for (run in allRuns) {
            val text = run.text
            if (text.isBlank()) continue
            val font = run.fontFamily?.trim()
            if (!font.isNullOrEmpty()) fontFamilies.add(font)

            when {
                KANNADA_UNICODE_REGEX.containsMatchIn(text) -> {
                    hasUnicode = true
                    detectedCategories.add("Unicode Kannada")
                }
                font != null && NUDI_FONT_REGEX.containsMatchIn(font) -> {
                    hasLegacy = true
                    detectedCategories.add("Nudi (Legacy ASCII)")
                }
                font != null && BARAHA_FONT_REGEX.containsMatchIn(font) -> {
                    hasLegacy = true
                    detectedCategories.add("Baraha (BRH Kannada)")
                }
                font != null && SHREELIPI_FONT_REGEX.containsMatchIn(font) -> {
                    hasLegacy = true
                    detectedCategories.add("ShreeLipi")
                }
                font != null && AKRUTI_FONT_REGEX.containsMatchIn(font) -> {
                    hasLegacy = true
                    detectedCategories.add("Akruti")
                }
                HIGH_ASCII_NUDI_REGEX.containsMatchIn(text) -> {
                    hasLegacy = true
                    detectedCategories.add("Nudi / ASCII (Detected)")
                }
                LATIN_WORD_REGEX.containsMatchIn(text) -> {
                    hasEnglish = true
                }
            }
        }

        val summary = when {
            detectedCategories.isEmpty() && hasEnglish -> "English / Plain Text"
            detectedCategories.isEmpty() -> "Blank / Unicode Kannada"
            detectedCategories.size == 1 && hasEnglish -> "${detectedCategories.first()} + English"
            else -> detectedCategories.joinToString(" + ")
        }

        return DocumentInfo(
            fileName = doc.fileName,
            fileFormat = doc.sourceFormat,
            detectedEncodingLabel = summary,
            fontFamiliesFound = fontFamilies.toList(),
            hasLegacyKannada = hasLegacy,
            hasUnicodeKannada = hasUnicode,
            hasEnglishText = hasEnglish
        )
    }

    class Factory(
        private val appContext: Context,
        private val parserFacade: DocumentParserFacade,
        private val storageManager: SafStorageManager,
        private val recentFileDao: RecentFileDao,
        private val draftDao: DraftDao,
        private val docxWriter: DocxWriter,
        private val rtfDocWriter: RtfDocWriter,
        private val pdfExporter: PdfExporter
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return EditorViewModel(
                appContext,
                parserFacade,
                storageManager,
                recentFileDao,
                draftDao,
                docxWriter,
                rtfDocWriter,
                pdfExporter
            ) as T
        }
    }
}
