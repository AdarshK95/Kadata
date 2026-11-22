package com.kannadadoc.ui.editor

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kannadadoc.data.local.AppPreferences
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
    val bilingualTitle: String,
    val bilingualSubtitle: String,
    val suffix: String,
    val shortBadge: String,
    val defaultFontForWord: String
) {
    NUDI(
        id = "nudi",
        labelEn = "Nudi",
        labelKn = "ನುಡಿ",
        bilingualTitle = "Nudi • ನುಡಿ",
        bilingualSubtitle = "Supported on PC only • ಕಂಪ್ಯೂಟರ್‌ನಲ್ಲಿ (PC) ಮಾತ್ರ ಬೆಂಬಲಿತವಾಗಿದೆ",
        suffix = "Nudi",
        shortBadge = "Nudi",
        defaultFontForWord = "Calibri"
    ),
    BARAHA(
        id = "baraha",
        labelEn = "Baraha",
        labelKn = "ಬರಹ",
        bilingualTitle = "Baraha • ಬರಹ",
        bilingualSubtitle = "Supported on PC only • ಕಂಪ್ಯೂಟರ್‌ನಲ್ಲಿ (PC) ಮಾತ್ರ ಬೆಂಬಲಿತವಾಗಿದೆ",
        suffix = "Baraha",
        shortBadge = "Baraha",
        defaultFontForWord = "Calibri"
    ),
    UNICODE(
        id = "unicode",
        labelEn = "Modern Universal Kannada",
        labelKn = "ಆಧುನಿಕ ಸಾರ್ವತ್ರಿಕ ಕನ್ನಡ",
        bilingualTitle = "Modern Universal Kannada • ಆಧುನಿಕ ಸಾರ್ವತ್ರಿಕ ಕನ್ನಡ",
        bilingualSubtitle = "Supported on all modern devices (phones, tablets, etc.), not supported in Nudi & Baraha • ಫೋನ್, ಟ್ಯಾಬ್ಲೆಟ್ ಮುಂತಾದ ಎಲ್ಲಾ ಆಧುನಿಕ ಸಾಧನಗಳಲ್ಲಿ ಬೆಂಬಲಿತವಾಗಿದೆ, ಆದರೆ ನುಡಿ ಮತ್ತು ಬರಹದಲ್ಲಿ ಬೆಂಬಲಿತವಾಗಿಲ್ಲ",
        suffix = "Universal",
        shortBadge = "Universal",
        defaultFontForWord = "Noto Sans Kannada"
    )
}

enum class TargetFileType(
    val id: String,
    val extension: String,
    val label: String,
    val mimeType: String,
    val docFormat: DocFormat
) {
    DOC("doc", "doc", ".doc (Word 97–2003)", "application/msword", DocFormat.DOC),
    DOCX("docx", "docx", ".docx (Word)", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DocFormat.DOCX),
    PDF("pdf", "pdf", ".pdf (PDF)", "application/pdf", DocFormat.PDF)
}

data class DocumentInfo(
    val fileName: String = "",
    val fileFormat: DocFormat = DocFormat.DOCX,
    val detectedEncodingLabel: String = "Modern Universal Kannada • ಆಧುನಿಕ ಸಾರ್ವತ್ರಿಕ ಕನ್ನಡ",
    val currentEncoding: KannadaEncodingChoice? = null,
    val shortFormatTag: String = "Universal",
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
    private val preferences: AppPreferences,
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
    private var pendingSafSuggestedName: String = "Document.docx"

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
                    preferences.setDocumentFormatTag(uri.toString(), info.shortFormatTag)
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
            if (parsed.sourceUri.isNotBlank()) {
                preferences.setDocumentFormatTag(parsed.sourceUri, info.shortFormatTag)
            }
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
     * Renames the currently open document from inside the editor top bar,
     * preserving the file extension if omitted, and syncing Recent Files + Drafts.
     */
    fun renameCurrentDocument(rawNewName: String) {
        val trimmed = rawNewName.trim()
        if (trimmed.isEmpty()) return
        val state = _uiState.value
        val currentTitle = state.documentTitle.ifBlank { "Untitled.docx" }
        val existingExt = currentTitle.substringAfterLast('.', "")
        val finalName = if (existingExt.isNotEmpty() && !trimmed.contains('.')) {
            "$trimmed.$existingExt"
        } else {
            trimmed
        }

        _uiState.value = state.copy(
            documentTitle = finalName,
            documentInfo = state.documentInfo.copy(fileName = finalName),
            statusMessage = "Renamed to $finalName"
        )

        if (state.uriString.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                recentFileDao.updateDisplayName(state.uriString, finalName)
                draftDao.updateTitle(state.uriString, finalName)
            }
        }
    }

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
     * - Change Document Type (convert_doc_nudi / convert_doc_baraha / convert_doc_unicode)
     * - 2-Step Share Different Format (share_<encoding>_<ext>) — also registered in Recent Files!
     * - 2-Step Save a Copy (savecopy_<encoding>_<ext>) — also registered in Recent Files!
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
                        writeInternalDocx(parsedDoc, targetFile, defaultFont = "Calibri", formatTag = "Nudi")
                        markSaveComplete(
                            label = "Saved (Nudi)",
                            snackbar = if (isAutoSave) null else "Saved as ${targetFile.name} (Nudi)"
                        )
                    }

                    exportMode == "save_default_baraha" -> {
                        val targetFile = storageManager.resolveSavedDocxFile(title, "Baraha")
                        writeInternalDocx(parsedDoc, targetFile, defaultFont = "Calibri", formatTag = "Baraha")
                        markSaveComplete(
                            label = "Saved (Baraha)",
                            snackbar = if (isAutoSave) null else "Saved as ${targetFile.name} (Baraha)"
                        )
                    }

                    exportMode == "save_default_unicode" || exportMode == "save_internal" -> {
                        val targetFile = storageManager.resolveSavedDocxFile(title, "Unicode")
                        writeInternalDocx(parsedDoc, targetFile, defaultFont = "Noto Sans Kannada", formatTag = "Universal")
                        markSaveComplete(
                            label = "Saved (Universal)",
                            snackbar = if (isAutoSave) null else "Saved as ${targetFile.name} (Modern Universal Kannada)"
                        )
                    }

                    // 2. Change Document Type (auto-convert current document to Nudi / Baraha / Modern Universal Kannada)
                    exportMode.startsWith("convert_doc_") -> {
                        val targetEnc = when (exportMode.removePrefix("convert_doc_")) {
                            "nudi" -> KannadaEncodingChoice.NUDI
                            "baraha" -> KannadaEncodingChoice.BARAHA
                            else -> KannadaEncodingChoice.UNICODE
                        }
                        val outFile = storageManager.resolvePersistentExportFile(base, targetEnc.suffix, "docx")
                        withContext(Dispatchers.IO) {
                            FileOutputStream(outFile).use { fos ->
                                docxWriter.write(parsedDoc, fos, defaultFont = targetEnc.defaultFontForWord)
                            }
                        }
                        val registered = storageManager.registerExportedFile(
                            file = outFile,
                            displayName = outFile.name,
                            format = DocFormat.DOCX,
                            mimeType = TargetFileType.DOCX.mimeType
                        )
                        preferences.setDocumentFormatTag(registered.uriString, targetEnc.shortBadge)
                        if (_uiState.value.uriString.isNotBlank()) {
                            preferences.setDocumentFormatTag(_uiState.value.uriString, targetEnc.shortBadge)
                        }
                        val updatedInfo = _uiState.value.documentInfo.copy(
                            fileName = outFile.name,
                            fileFormat = DocFormat.DOCX,
                            detectedEncodingLabel = when (targetEnc) {
                                KannadaEncodingChoice.NUDI -> "Nudi • ನುಡಿ (PC)"
                                KannadaEncodingChoice.BARAHA -> "Baraha • ಬರಹ (PC)"
                                KannadaEncodingChoice.UNICODE -> "Modern Universal Kannada • ಆಧುನಿಕ ಸಾರ್ವತ್ರಿಕ ಕನ್ನಡ"
                            },
                            currentEncoding = targetEnc,
                            shortFormatTag = targetEnc.shortBadge,
                            hasLegacyKannada = targetEnc != KannadaEncodingChoice.UNICODE,
                            hasUnicodeKannada = targetEnc == KannadaEncodingChoice.UNICODE
                        )
                        _uiState.value = _uiState.value.copy(
                            uriString = registered.uriString,
                            documentTitle = outFile.name,
                            documentInfo = updatedInfo,
                            isSaving = false,
                            isDirty = false,
                            showSavedTick = true,
                            lastSavedLabel = "Converted (${targetEnc.shortBadge})",
                            statusMessage = "Document converted to ${targetEnc.labelEn} (${outFile.name})"
                        )
                    }

                    // 3. Two-step Share Different Format: persist in documents/ + register in Recent Files + Share
                    exportMode.startsWith("share_") -> {
                        val (encoding, fileType) = parseTwoStepMode(exportMode.removePrefix("share_"))
                        val outFile = exportToPersistentFile(parsedDoc, base, encoding, fileType)
                        val registered = storageManager.registerExportedFile(
                            file = outFile,
                            displayName = outFile.name,
                            format = fileType.docFormat,
                            mimeType = fileType.mimeType
                        )
                        preferences.setDocumentFormatTag(
                            registered.uriString,
                            if (fileType == TargetFileType.PDF) "Universal" else encoding.shortBadge
                        )
                        _uiState.value = _uiState.value.copy(isSaving = false)
                        ShareHelper.shareFile(
                            context = appContext,
                            file = outFile,
                            mimeType = fileType.mimeType,
                            chooserTitle = "Share ${encoding.labelEn} (${fileType.extension.uppercase()})"
                        )
                    }

                    // 4. Two-step Save a Copy / Save As: SAF CreateDocument + auto-register in Recent Files
                    exportMode.startsWith("savecopy_") -> {
                        val (encoding, fileType) = parseTwoStepMode(exportMode.removePrefix("savecopy_"))
                        val suggested = "${base}_${encoding.suffix}.${fileType.extension}"
                        pendingSafSaveDoc = parsedDoc
                        pendingSafEncoding = encoding
                        pendingSafFileType = fileType
                        pendingSafSuggestedName = suggested
                        _uiState.value = _uiState.value.copy(isSaving = false)
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

    private suspend fun exportToPersistentFile(
        parsedDoc: ParsedDocument,
        baseName: String,
        encoding: KannadaEncodingChoice,
        fileType: TargetFileType
    ): File = withContext(Dispatchers.IO) {
        when (fileType) {
            TargetFileType.PDF -> {
                val cachedPdf = pdfExporter.exportToPdf(parsedDoc, "${baseName}_${encoding.suffix}")
                val persistentPdf = storageManager.resolvePersistentExportFile(baseName, encoding.suffix, "pdf")
                cachedPdf.copyTo(persistentPdf, overwrite = true)
                persistentPdf
            }
            TargetFileType.DOCX -> {
                val out = storageManager.resolvePersistentExportFile(baseName, encoding.suffix, "docx")
                FileOutputStream(out).use { fos ->
                    docxWriter.write(parsedDoc, fos, defaultFont = encoding.defaultFontForWord)
                }
                out
            }
            TargetFileType.DOC -> {
                val out = storageManager.resolvePersistentExportFile(baseName, encoding.suffix, "doc")
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
        val suggestedName = pendingSafSuggestedName
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
                // Register newly saved SAF copy immediately in Recent Files!
                val registered = storageManager.registerSafSavedUri(
                    targetUri = targetUri,
                    fallbackDisplayName = suggestedName,
                    format = fileType.docFormat,
                    mimeType = fileType.mimeType
                )
                preferences.setDocumentFormatTag(
                    registered.uriString,
                    if (fileType == TargetFileType.PDF) "Universal" else encoding.shortBadge
                )
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    statusMessage = "Saved ${encoding.labelEn} (.${fileType.extension}) & added to Recent Files"
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
        defaultFont: String,
        formatTag: String
    ) = withContext(Dispatchers.IO) {
        FileOutputStream(targetFile).use { fos ->
            docxWriter.write(parsedDoc, fos, defaultFont = defaultFont)
        }
        val savedUri = storageManager.uriForFile(targetFile).toString()
        val currentUri = _uiState.value.uriString
        if (currentUri.isNotBlank()) {
            val existing = recentFileDao.getByUri(currentUri)
            if (existing != null) {
                recentFileDao.upsert(
                    existing.copy(
                        savedUnicodeUri = savedUri,
                        fileSizeBytes = targetFile.length(),
                        lastOpenedAt = System.currentTimeMillis()
                    )
                )
            } else {
                recentFileDao.updateSavedUnicodeUri(
                    uriString = currentUri,
                    savedUri = savedUri,
                    timestamp = System.currentTimeMillis()
                )
            }
            preferences.setDocumentFormatTag(currentUri, formatTag)
        }
        preferences.setDocumentFormatTag(savedUri, formatTag)
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
        var hasNudi = false
        var hasBaraha = false
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
                    detectedCategories.add("Modern Universal Kannada • ಆಧುನಿಕ ಸಾರ್ವತ್ರಿಕ ಕನ್ನಡ")
                }
                font != null && NUDI_FONT_REGEX.containsMatchIn(font) -> {
                    hasLegacy = true
                    hasNudi = true
                    detectedCategories.add("Nudi • ನುಡಿ (PC)")
                }
                font != null && BARAHA_FONT_REGEX.containsMatchIn(font) -> {
                    hasLegacy = true
                    hasBaraha = true
                    detectedCategories.add("Baraha • ಬರಹ (PC)")
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
                    hasNudi = true
                    detectedCategories.add("Nudi • ನುಡಿ (PC)")
                }
                LATIN_WORD_REGEX.containsMatchIn(text) -> {
                    hasEnglish = true
                }
            }
        }

        val summary = when {
            detectedCategories.isEmpty() && hasEnglish -> "English / Plain Text"
            detectedCategories.isEmpty() -> "Modern Universal Kannada • ಆಧುನಿಕ ಸಾರ್ವತ್ರಿಕ ಕನ್ನಡ"
            detectedCategories.size == 1 && hasEnglish -> "${detectedCategories.first()} + English"
            else -> detectedCategories.joinToString(" + ")
        }

        val currentEnc = when {
            hasBaraha && !hasNudi && !hasUnicode -> KannadaEncodingChoice.BARAHA
            hasNudi && !hasBaraha && !hasUnicode -> KannadaEncodingChoice.NUDI
            hasUnicode && !hasLegacy -> KannadaEncodingChoice.UNICODE
            hasBaraha -> KannadaEncodingChoice.BARAHA
            hasNudi -> KannadaEncodingChoice.NUDI
            else -> KannadaEncodingChoice.UNICODE
        }

        val shortTag = when {
            detectedCategories.isEmpty() && hasEnglish -> "English"
            hasBaraha -> "Baraha"
            hasNudi -> "Nudi"
            else -> "Universal"
        }

        return DocumentInfo(
            fileName = doc.fileName,
            fileFormat = doc.sourceFormat,
            detectedEncodingLabel = summary,
            currentEncoding = currentEnc,
            shortFormatTag = shortTag,
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
        private val preferences: AppPreferences,
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
                preferences,
                docxWriter,
                rtfDocWriter,
                pdfExporter
            ) as T
        }
    }
}
