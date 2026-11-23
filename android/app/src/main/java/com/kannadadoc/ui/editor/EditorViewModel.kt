package com.kannadadoc.ui.editor

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kannadadoc.converter.KannadaTextConverter
import com.kannadadoc.data.local.AppPreferences
import com.kannadadoc.data.local.DraftDao
import com.kannadadoc.data.local.RecentFileDao
import com.kannadadoc.data.local.RecentFileEntity
import com.kannadadoc.data.local.RevisionEntity
import com.kannadadoc.data.storage.CrashRecoveryCandidate
import com.kannadadoc.data.storage.FileLocationHelper
import com.kannadadoc.data.storage.RecoveryVaultManager
import com.kannadadoc.data.storage.RecoveryVaultPolicy
import com.kannadadoc.data.storage.SafStorageManager
import com.kannadadoc.data.storage.UriAccessInfo
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
import com.kannadadoc.parser.DocumentSizeLimitException
import com.kannadadoc.parser.ParsedDocument
import com.kannadadoc.parser.TextRun
import com.kannadadoc.ui.common.CreateDocumentRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.OutputStream

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
        id = "nudi", labelEn = "Nudi", labelKn = "ನುಡಿ",
        bilingualTitle = "Nudi • ನುಡಿ",
        bilingualSubtitle = "Supported on PC only • ಕಂಪ್ಯೂಟರ್‌ನಲ್ಲಿ (PC) ಮಾತ್ರ ಬೆಂಬಲಿತವಾಗಿದೆ",
        suffix = "Nudi", shortBadge = "Nudi", defaultFontForWord = "Calibri"
    ),
    BARAHA(
        id = "baraha", labelEn = "Baraha", labelKn = "ಬರಹ",
        bilingualTitle = "Baraha • ಬರಹ",
        bilingualSubtitle = "Supported on PC only • ಕಂಪ್ಯೂಟರ್‌ನಲ್ಲಿ (PC) ಮಾತ್ರ ಬೆಂಬಲಿತವಾಗಿದೆ",
        suffix = "Baraha", shortBadge = "Baraha", defaultFontForWord = "Calibri"
    ),
    UNICODE(
        id = "unicode", labelEn = "Modern Universal Kannada", labelKn = "ಆಧುನಿಕ ಸಾರ್ವತ್ರಿಕ ಕನ್ನಡ",
        bilingualTitle = "Modern Universal Kannada • ಆಧುನಿಕ ಸಾರ್ವತ್ರಿಕ ಕನ್ನಡ",
        bilingualSubtitle = "Supported on all modern devices (phones, tablets, etc.), not supported in Nudi & Baraha • ಫೋನ್, ಟ್ಯಾಬ್ಲೆಟ್ ಮುಂತಾದ ಎಲ್ಲಾ ಆಧುನಿಕ ಸಾಧನಗಳಲ್ಲಿ ಬೆಂಬಲಿತವಾಗಿದೆ, ಆದರೆ ನುಡಿ ಮತ್ತು ಬರಹದಲ್ಲಿ ಬೆಂಬಲಿತವಾಗಿಲ್ಲ",
        suffix = "Universal", shortBadge = "Universal", defaultFontForWord = "Noto Sans Kannada"
    )
}

enum class TargetFileType(
    val id: String,
    val extension: String,
    val label: String,
    val mimeType: String,
    val docFormat: DocFormat,
    val forceUniversalUnicode: Boolean = false
) {
    DOCX("docx", "docx", "Word (.docx)", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DocFormat.DOCX),
    PDF("pdf", "pdf", "PDF document (.pdf)", "application/pdf", DocFormat.PDF, true),
    ODT("odt", "odt", "OpenDocument format (.odt)", "application/vnd.oasis.opendocument.text", DocFormat.ODT),
    TXT("txt", "txt", "Plain text (.txt)", "text/plain", DocFormat.TXT),
    RTF("rtf", "rtf", "Rich Text format (.rtf)", "application/rtf", DocFormat.RTF),
    HTML_ZIP("html_zip", "zip", "Web page (.html, zipped)", "application/zip", DocFormat.HTML, true),
    EPUB("epub", "epub", "EPUB publication (.epub)", "application/epub+zip", DocFormat.EPUB, true),
    GOOGLE_DOCS("gdocs", "docx", "Google Docs (.docx)", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DocFormat.DOCX, true),
    DOC("doc", "doc", "Word document (older format, .doc)", "application/msword", DocFormat.DOC)
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

data class VersionPreviewState(
    val revision: RevisionEntity,
    val previewText: String,
    val suggestedFileName: String
)

data class EditorUiState(
    val uriString: String = "",
    val documentTitle: String = "",
    val isLoading: Boolean = true,
    val parsedDocJson: String? = null,
    val originalDocJson: String? = null,
    val docPayloadGeneration: Long = 0L,
    val useUrlPayloadTransfer: Boolean = false,
    val restoredFromRecovery: Boolean = false,
    val initialConvertedRuns: Int = 0,
    val initialDetectedSummary: String = "",
    val activeConverter: String = "auto",
    val isOriginalView: Boolean = false,
    val isExternalReadOnly: Boolean = false,
    val sourceAppLabel: String? = null,
    val fontSizePt: Int = 18,
    val showFormattingStrip: Boolean = true,
    val conversionBannerText: String? = null,
    val loadErrorTitle: String? = null,
    val loadErrorMessage: String? = null,
    val warningMessage: String? = null,
    val statusMessage: String? = null,
    val isSaving: Boolean = false,
    val isDirty: Boolean = false,
    val showSavedTick: Boolean = false,
    val lastSavedLabel: String? = null,
    val saveGeneration: Int = 0,
    val magnitudeAutoSaveSignal: Int = 0,
    val lastEditActivityAt: Long = 0L,
    val documentInfo: DocumentInfo = DocumentInfo(),
    val versionsList: List<RevisionEntity> = emptyList(),
    val isLoadingVersions: Boolean = false,
    val versionPreview: VersionPreviewState? = null
)

class EditorViewModel(
    private val appContext: Context,
    private val parserFacade: DocumentParserFacade,
    private val storageManager: SafStorageManager,
    private val recoveryVault: RecoveryVaultManager,
    private val recentFileDao: RecentFileDao,
    private val draftDao: DraftDao,
    private val preferences: AppPreferences,
    private val docxWriter: DocxWriter,
    private val rtfDocWriter: RtfDocWriter,
    private val pdfExporter: PdfExporter,
    private val odtWriter: OdtWriter = OdtWriter(),
    private val txtWriter: TxtWriter = TxtWriter(),
    private val htmlZipWriter: HtmlZipWriter = HtmlZipWriter(),
    private val epubWriter: EpubWriter = EpubWriter(htmlZipWriter)
) : ViewModel() {

    companion object {
        private val KANNADA_UNICODE_REGEX = Regex("[\u0C80-\u0CFF]")
        private val HIGH_ASCII_NUDI_REGEX = Regex("[\u00A2-\u00FF]")
        private val LATIN_WORD_REGEX = Regex("[A-Za-z]{2,}")
        private val NUDI_FONT_REGEX = Regex("(^nudi|nudi[\\s\\-_]*\\d|kgp|kan[\\-_]?nudi)", RegexOption.IGNORE_CASE)
        private val BARAHA_FONT_REGEX = Regex("(^brh|baraha|tunga[\\s\\-_]*brh)", RegexOption.IGNORE_CASE)
        private val SHREELIPI_FONT_REGEX = Regex("(shree[\\-_]?kan|srilipi|shree.*deccan)", RegexOption.IGNORE_CASE)
        private val AKRUTI_FONT_REGEX = Regex("(akruti|akrutikan)", RegexOption.IGNORE_CASE)

        private const val LARGE_PAYLOAD_STREAM_THRESHOLD_CHARS = 256_000
        private const val MAGNITUDE_AUTOSAVE_DELTA_CHARS = 500
        private const val LARGE_PASTE_DELTA_CHARS = 300
        private const val SNAPSHOT_MIN_COOLDOWN_MS = 60_000L
    }

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val saveMutex = Mutex()
    private var sessionBaselineRecorded = false
    private var sessionBaselineRevisionId: Long? = null
    private var initialLoadedUnicodeJson: String? = null
    private var initialLoadedCharCount: Int = 0
    private var pendingDeltaSinceSave: Int = 0
    private var pendingDeltaSinceSnapshot: Int = 0
    private var lastSnapshotAtMs: Long = 0L
    private var sourceFileModifiedAtMs: Long = 0L
    private var latestWebPayloadBytes: ByteArray? = null

    private val pendingSafMetaFile: File
        get() = File(File(appContext.cacheDir, "save_staging").apply { mkdirs() }, "editor_pending_saf.json")
    private val pendingSafDocFile: File
        get() = File(File(appContext.cacheDir, "save_staging").apply { mkdirs() }, "editor_pending_saf_doc.json")

    fun computeSafeMaxPasteChars(): Int {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null) {
            if (am.isLowRamDevice) return 500_000
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val totalGb = info.totalMem / (1024L * 1024L * 1024L)
            return when {
                totalGb <= 4L -> 500_000
                totalGb <= 6L -> 1_000_000
                else -> 2_000_000
            }
        }
        return 1_000_000
    }

    fun getPendingWebPayloadBytes(): ByteArray? = latestWebPayloadBytes

    private fun resetSessionTracking(unicodeJson: String, originalJson: String?, sourceModifiedAt: Long) {
        sessionBaselineRecorded = false
        sessionBaselineRevisionId = null
        initialLoadedUnicodeJson = unicodeJson
        initialLoadedCharCount = unicodeJson.length
        pendingDeltaSinceSave = 0
        pendingDeltaSinceSnapshot = 0
        lastSnapshotAtMs = 0L
        sourceFileModifiedAtMs = sourceModifiedAt

        latestWebPayloadBytes = if (unicodeJson.length > LARGE_PAYLOAD_STREAM_THRESHOLD_CHARS) {
            JSONObject().apply {
                put("unicodeDoc", JSONObject(unicodeJson))
                put("originalDoc", if (originalJson != null) JSONObject(originalJson) else JSONObject.NULL)
            }.toString().toByteArray(Charsets.UTF_8)
        } else null
    }

    fun loadExistingDocument(
        uri: Uri,
        fileName: String,
        format: DocFormat,
        initialFontSizePt: Int,
        accessInfo: UriAccessInfo = UriAccessInfo(true, null, false),
        forceRecoverFromCheckpointPath: String? = null,
        initialStatusMessage: String? = null
    ) {
        viewModelScope.launch {
            val displayFormat = DocFormat.displayFormatFor(fileName, format)
            _uiState.value = EditorUiState(
                uriString = uri.toString(),
                documentTitle = fileName,
                isLoading = true,
                fontSizePt = initialFontSizePt,
                isExternalReadOnly = accessInfo.requiresSaveBeforeEdit,
                sourceAppLabel = accessInfo.sourceAppLabel,
                documentInfo = DocumentInfo(fileName = fileName, fileFormat = displayFormat)
            )

            val uriStr = uri.toString()
            val recent = recentFileDao.getByUri(uriStr)
            val diskModifiedAt = storageManager.queryFileLastModified(uri).takeIf { it > 0L } ?: recent?.lastModifiedAt ?: 0L

            val recoveredDoc = if (!forceRecoverFromCheckpointPath.isNullOrBlank()) {
                recoveryVault.loadDocumentFromCheckpointPath(forceRecoverFromCheckpointPath)
            } else if (!accessInfo.requiresSaveBeforeEdit) {
                recoveryVault.loadActiveCheckpointIfNewer(uriStr, diskModifiedAt)
            } else null

            parserFacade.parseDocument(appContext, uri, fileName, format)
                .onSuccess { parsed ->
                    val converterId = recent?.converterOverride ?: "auto"
                    val report = KannadaTextConverter.normalizeDocumentWithReport(parsed, converterId)
                    val finalUnicodeDoc = recoveredDoc ?: report.document
                    val unicodeJson = DocumentJson.toJson(finalUnicodeDoc)
                    val originalJson = DocumentJson.toJson(parsed)
                    val info = inspectDocumentInfo(parsed).copy(fileFormat = displayFormat)

                    recentFileDao.updateKannadaFormat(uriStr, info.shortFormatTag)
                    preferences.setDocumentFormatTag(uriStr, info.shortFormatTag)

                    resetSessionTracking(unicodeJson, originalJson, diskModifiedAt)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        parsedDocJson = unicodeJson,
                        originalDocJson = originalJson,
                        docPayloadGeneration = System.currentTimeMillis(),
                        useUrlPayloadTransfer = unicodeJson.length > LARGE_PAYLOAD_STREAM_THRESHOLD_CHARS,
                        restoredFromRecovery = recoveredDoc != null,
                        initialConvertedRuns = if (recoveredDoc != null) 0 else report.convertedRuns,
                        initialDetectedSummary = if (recoveredDoc != null) "" else report.detectedSummary,
                        activeConverter = converterId,
                        warningMessage = parsed.warningMessage,
                        statusMessage = initialStatusMessage,
                        isDirty = recoveredDoc != null,
                        documentInfo = info
                    )
                }
                .onFailure { err ->
                    if (recoveredDoc != null) {
                        val unicodeJson = DocumentJson.toJson(recoveredDoc)
                        resetSessionTracking(unicodeJson, unicodeJson, System.currentTimeMillis())
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            parsedDocJson = unicodeJson,
                            originalDocJson = unicodeJson,
                            docPayloadGeneration = System.currentTimeMillis(),
                            useUrlPayloadTransfer = unicodeJson.length > LARGE_PAYLOAD_STREAM_THRESHOLD_CHARS,
                            restoredFromRecovery = true,
                            isDirty = true,
                            warningMessage = "Recovered from AutoRecover checkpoint because the original file was unavailable. Please tap Save."
                        )
                        return@onFailure
                    }
                    val (errTitle, errMsg) = when (err) {
                        is DocumentSizeLimitException -> "${err.titleEn} • ${err.titleKn}" to "${err.userMessageEn}\n\n${err.userMessageKn}"
                        else -> {
                            val detail = err.localizedMessage?.takeIf { it.isNotBlank() } ?: "The document appears corrupted or could not be read."
                            "Unable to Open Document • ದಾಖಲೆ ತೆರೆಯಲಾಗಲಿಲ್ಲ" to
                                "Kadata could not load \"$fileName\".\n\nReason: $detail\n\nಈ ದಾಖಲೆಯನ್ನು ತೆರೆಯಲು ಸಾಧ್ಯವಾಗಲಿಲ್ಲ."
                        }
                    }
                    _uiState.value = _uiState.value.copy(isLoading = false, parsedDocJson = null, loadErrorTitle = errTitle, loadErrorMessage = errMsg)
                }
        }
    }

    fun loadPreParsedDocument(
        parsed: ParsedDocument,
        initialFontSizePt: Int,
        isExternalReadOnly: Boolean = false,
        sourceAppLabel: String? = null
    ) {
        viewModelScope.launch {
            val report = KannadaTextConverter.normalizeDocumentWithReport(parsed, "auto")
            val unicodeJson = DocumentJson.toJson(report.document)
            val originalJson = DocumentJson.toJson(parsed)
            val info = inspectDocumentInfo(parsed)
            if (parsed.sourceUri.isNotBlank()) {
                recentFileDao.updateKannadaFormat(parsed.sourceUri, info.shortFormatTag)
                preferences.setDocumentFormatTag(parsed.sourceUri, info.shortFormatTag)
            }
            resetSessionTracking(unicodeJson, originalJson, System.currentTimeMillis())
            _uiState.value = EditorUiState(
                uriString = parsed.sourceUri,
                documentTitle = parsed.fileName,
                isLoading = false,
                parsedDocJson = unicodeJson,
                originalDocJson = originalJson,
                docPayloadGeneration = System.currentTimeMillis(),
                useUrlPayloadTransfer = unicodeJson.length > LARGE_PAYLOAD_STREAM_THRESHOLD_CHARS,
                initialConvertedRuns = report.convertedRuns,
                initialDetectedSummary = report.detectedSummary,
                isExternalReadOnly = isExternalReadOnly,
                sourceAppLabel = sourceAppLabel,
                fontSizePt = initialFontSizePt,
                warningMessage = parsed.warningMessage,
                documentInfo = info
            )
        }
    }

    fun loadCrashRecoveredDocument(candidate: CrashRecoveryCandidate, initialFontSizePt: Int) {
        val uri = Uri.parse(candidate.uriString)
        val ext = candidate.documentTitle.substringAfterLast('.', "docx").lowercase()
        val format = when (ext) {
            "doc" -> DocFormat.DOC; "odt" -> DocFormat.ODT; "rtf" -> DocFormat.RTF
            "txt" -> DocFormat.TXT; "html", "htm" -> DocFormat.HTML; "epub" -> DocFormat.EPUB
            else -> DocFormat.DOCX
        }
        loadExistingDocument(uri, candidate.documentTitle, format, initialFontSizePt, storageManager.inspectUriAccess(uri), candidate.checkpointPath)
    }

    fun reloadAfterWebViewCrash() {
        val state = _uiState.value
        if (state.uriString.isBlank()) return
        viewModelScope.launch {
            val checkpoint = recoveryVault.loadActiveCheckpointIfNewer(state.uriString, 0L)
            if (checkpoint != null) {
                val unicodeJson = DocumentJson.toJson(checkpoint)
                resetSessionTracking(unicodeJson, state.originalDocJson, sourceFileModifiedAtMs)
                _uiState.value = _uiState.value.copy(
                    parsedDocJson = unicodeJson,
                    docPayloadGeneration = System.currentTimeMillis(),
                    useUrlPayloadTransfer = unicodeJson.length > LARGE_PAYLOAD_STREAM_THRESHOLD_CHARS,
                    restoredFromRecovery = true,
                    isDirty = true,
                    warningMessage = "Recovered your latest edits after a memory reload • ಮೆಮೊರಿ ರೀಲೋಡ್ ನಂತರ ನಿಮ್ಮ ಬದಲಾವಣೆಗಳನ್ನು ಮರುಪಡೆಯಲಾಗಿದೆ."
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    docPayloadGeneration = System.currentTimeMillis(),
                    warningMessage = "Editor reloaded after low memory. Please tap Save regularly."
                )
            }
        }
    }

    fun saveExternalReadOnlyToKadataAndUnlock(onUnlocked: () -> Unit) {
        val state = _uiState.value
        val currentJson = state.parsedDocJson ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            runCatching {
                val unicodeDoc = KannadaTextConverter.normalizeDocumentToUnicode(DocumentJson.fromJson(currentJson))
                val saveFormat = preferences.settings.value.defaultSaveFormat
                val enc = when (saveFormat.shortLabel) {
                    "Nudi" -> KannadaEncodingChoice.NUDI; "Baraha" -> KannadaEncodingChoice.BARAHA; else -> KannadaEncodingChoice.UNICODE
                }
                val prepared = if (enc == KannadaEncodingChoice.UNICODE) unicodeDoc else {
                    KannadaTextConverter.prepareDocumentForLegacyExport(unicodeDoc, if (enc == KannadaEncodingChoice.BARAHA) "BRH Kannada" else "Nudi 01 e")
                }
                val cleanBase = storageManager.stripEncodingSuffix(state.documentTitle.substringBeforeLast('.')).ifBlank { "Document" }
                val oldUri = state.uriString
                val ingested = storageManager.saveNewFileInKadataDocuments(
                    "$cleanBase.docx", DocFormat.DOCX, TargetFileType.DOCX.mimeType, enc.shortBadge, true
                ) { out -> docxWriter.write(prepared, out, defaultFont = enc.defaultFontForWord) }

                if (oldUri.isNotBlank() && oldUri != ingested.entity.uriString) {
                    val oldParsed = runCatching { Uri.parse(oldUri) }.getOrNull()
                    if (oldParsed?.authority == "${appContext.packageName}.fileprovider" && oldParsed.path?.contains("imported", true) == true) {
                        recentFileDao.deleteByUri(oldUri)
                        storageManager.deleteInternalFilesIfUnreferenced(oldUri, null)
                    }
                }
                preferences.setDocumentFormatTag(ingested.entity.uriString, enc.shortBadge)
                ingested
            }.onSuccess { ingested ->
                sourceFileModifiedAtMs = ingested.entity.lastModifiedAt
                _uiState.value = _uiState.value.copy(
                    uriString = ingested.entity.uriString,
                    documentTitle = ingested.entity.displayName,
                    isExternalReadOnly = false,
                    sourceAppLabel = null,
                    isSaving = false,
                    isDirty = false,
                    showSavedTick = true,
                    documentInfo = _uiState.value.documentInfo.copy(fileName = ingested.entity.displayName, fileFormat = DocFormat.DOCX),
                    statusMessage = "Saved to Kadata Documents (${ingested.entity.displayName}) — editing unlocked!"
                )
                onUnlocked()
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(isSaving = false, warningMessage = "Could not save editable copy: ${err.localizedMessage ?: "storage error"}")
            }
        }
    }

    fun onWebDocumentLoaded(convertedRuns: Int, detectedSummary: String, restoredFromDraft: Boolean) {
        val state = _uiState.value
        val banner = when {
            state.restoredFromRecovery || restoredFromDraft -> "Recovered your latest unsaved edits • ನಿಮ್ಮ ಇತ್ತೀಚಿನ ಬದಲಾವಣೆಗಳನ್ನು ಮರುಪಡೆಯಲಾಗಿದೆ"
            convertedRuns > 0 -> "Kannada text automatically formatted (${detectedSummary.ifBlank { "Nudi / ASCII" }})"
            else -> null
        }
        _uiState.value = state.copy(conversionBannerText = banner, isDirty = state.restoredFromRecovery)
    }

    fun markDirty() {
        val now = System.currentTimeMillis()
        _uiState.value = _uiState.value.copy(isDirty = true, showSavedTick = false, lastEditActivityAt = now)
    }

    fun onEditDelta(deltaChars: Int, isLargePaste: Boolean, currentCharCount: Int) {
        val state = _uiState.value
        if (state.isExternalReadOnly || state.isOriginalView) return
        val delta = deltaChars.coerceAtLeast(1)
        pendingDeltaSinceSave += delta
        pendingDeltaSinceSnapshot += delta
        val now = System.currentTimeMillis()
        if ((isLargePaste && delta >= LARGE_PASTE_DELTA_CHARS) || pendingDeltaSinceSave >= MAGNITUDE_AUTOSAVE_DELTA_CHARS) {
            _uiState.value = _uiState.value.copy(magnitudeAutoSaveSignal = _uiState.value.magnitudeAutoSaveSignal + 1, lastEditActivityAt = now)
        } else {
            _uiState.value = _uiState.value.copy(lastEditActivityAt = now)
        }
    }

    fun onCheckpointReady(idmJson: String, charCount: Int) {
        val state = _uiState.value
        if (state.uriString.isBlank() || state.loadErrorMessage != null || state.isExternalReadOnly || !state.isDirty) return

        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            runCatching {
                if (!sessionBaselineRecorded && !initialLoadedUnicodeJson.isNullOrBlank()) {
                    sessionBaselineRecorded = true
                    val baseline = recoveryVault.recordVersionSnapshot(
                        state.uriString, state.documentTitle, initialLoadedUnicodeJson!!,
                        initialLoadedCharCount, 0, "Session Start", isSessionBaseline = true
                    )
                    sessionBaselineRevisionId = baseline?.id
                    lastSnapshotAtMs = System.currentTimeMillis()
                }

                recoveryVault.writeActiveCheckpoint(state.uriString, state.documentTitle, idmJson, pendingDeltaSinceSave, sourceFileModifiedAtMs)

                val now = System.currentTimeMillis()
                if (pendingDeltaSinceSnapshot >= MAGNITUDE_AUTOSAVE_DELTA_CHARS && (now - lastSnapshotAtMs) >= SNAPSHOT_MIN_COOLDOWN_MS) {
                    val deltaSnapshot = pendingDeltaSinceSnapshot
                    pendingDeltaSinceSnapshot = 0
                    lastSnapshotAtMs = now
                    recoveryVault.recordVersionSnapshot(
                        state.uriString, state.documentTitle, idmJson, charCount, deltaSnapshot,
                        "+$deltaSnapshot chars checkpoint", false, sessionBaselineRevisionId
                    )
                }
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    warningMessage = "Auto-recovery checkpoint warning (${err.localizedMessage ?: "storage error"}). Please tap Save."
                )
            }
        }
    }

    fun discardUnsavedAndMarkClean() {
        val uriStr = _uiState.value.uriString
        _uiState.value = _uiState.value.copy(isDirty = false)
        if (uriStr.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO + NonCancellable) {
                recoveryVault.markSessionClean(uriStr)
                draftDao.deleteDraft(uriStr)
            }
        }
    }

    fun clearSavedTick() { if (_uiState.value.showSavedTick) _uiState.value = _uiState.value.copy(showSavedTick = false) }
    fun dismissConversionBanner() { _uiState.value = _uiState.value.copy(conversionBannerText = null) }
    fun dismissWarning() { _uiState.value = _uiState.value.copy(warningMessage = null) }
    fun clearStatusMessage() { _uiState.value = _uiState.value.copy(statusMessage = null) }
    fun showStatusMessage(message: String) { if (message.isNotBlank()) _uiState.value = _uiState.value.copy(statusMessage = message) }
    fun reportEditorError(message: String) { _uiState.value = _uiState.value.copy(isLoading = false, isSaving = false, warningMessage = message) }
    fun reportEditorWarning(message: String) { if (message.isNotBlank()) _uiState.value = _uiState.value.copy(warningMessage = message) }
    fun toggleFormattingStrip() { _uiState.value = _uiState.value.copy(showFormattingStrip = !_uiState.value.showFormattingStrip) }
    fun adjustFontSize(deltaPt: Int): Int {
        val next = (_uiState.value.fontSizePt + deltaPt).coerceIn(14, 34)
        _uiState.value = _uiState.value.copy(fontSizePt = next)
        return next
    }
    fun setOriginalView(enabled: Boolean) { _uiState.value = _uiState.value.copy(isOriginalView = enabled) }

    fun renameCurrentDocument(rawNewName: String) {
        val state = _uiState.value
        if (state.uriString.isBlank() || state.isExternalReadOnly) {
            _uiState.value = state.copy(
                warningMessage = "Only files stored in Kadata Documents can be renamed directly."
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val result = storageManager.renameOwnedDocumentFile(
                oldUriString = state.uriString,
                currentDisplayName = state.documentTitle.ifBlank { "Untitled.docx" },
                rawNewName = rawNewName,
                recoveryVault = recoveryVault
            )
            result.onSuccess { renamed ->
                if (renamed.uriString != state.uriString) {
                    val oldTag = preferences.documentFormatMap.value[state.uriString]
                    if (!oldTag.isNullOrBlank()) {
                        preferences.setDocumentFormatTag(renamed.uriString, oldTag)
                        preferences.removeDocumentFormatTag(state.uriString)
                    }
                }
                sourceFileModifiedAtMs = renamed.modifiedAt
                _uiState.value = _uiState.value.copy(
                    uriString = renamed.uriString,
                    documentTitle = renamed.displayName,
                    documentInfo = _uiState.value.documentInfo.copy(fileName = renamed.displayName),
                    isSaving = false,
                    statusMessage = "Renamed to ${renamed.displayName}"
                )
            }.onFailure { err ->
                // Zero UI/DB/vault state changes on rename failure
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    warningMessage = err.localizedMessage ?: "Failed to rename file"
                )
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
                val targetName = state.documentTitle.ifBlank { "Document.docx" }
                val cachedFile = storageManager.copyUriToExportCache(Uri.parse(state.uriString), targetName)
                _uiState.value = _uiState.value.copy(isSaving = false)
                ShareHelper.shareFile(appContext, cachedFile, mimeForFileName(targetName, state.documentInfo.fileFormat), "Share • ಹಂಚಿಕೊಳ್ಳಿ")
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(isSaving = false, statusMessage = err.localizedMessage ?: "Unable to share file")
            }
        }
    }

    fun loadDocumentVersions() {
        val uriStr = _uiState.value.uriString
        if (uriStr.isBlank()) {
            _uiState.value = _uiState.value.copy(versionsList = emptyList(), isLoadingVersions = false)
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingVersions = true)
            val list = runCatching { recoveryVault.getVersionsForUri(uriStr) }.getOrDefault(emptyList())
            _uiState.value = _uiState.value.copy(versionsList = list, isLoadingVersions = false)
        }
    }

    fun openVersionReadOnlyPreview(revision: RevisionEntity) {
        viewModelScope.launch {
            val doc = recoveryVault.loadRevisionDocument(revision)
            if (doc == null) {
                _uiState.value = _uiState.value.copy(statusMessage = "This version file is no longer available on disk.")
                return@launch
            }
            val previewText = recoveryVault.extractPlainPreviewText(doc).ifBlank { "(Empty document)" }
            val existingNames = withContext(Dispatchers.IO) { recentFileDao.getAllSnapshot().map { it.displayName }.toSet() }
            val suggested = RecoveryVaultPolicy.generateUniqueVersionFileName(
                _uiState.value.documentTitle.ifBlank { revision.documentTitle }, revision.createdAt, existingNames
            )
            _uiState.value = _uiState.value.copy(versionPreview = VersionPreviewState(revision, previewText, suggested))
        }
    }

    fun dismissVersionPreview() { _uiState.value = _uiState.value.copy(versionPreview = null) }

    fun savePreviewedVersionAsNewFile(revision: RevisionEntity, desiredFileName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            runCatching {
                val unicodeDoc = recoveryVault.loadRevisionDocument(revision) ?: error("Version snapshot could not be loaded.")
                val saveFormat = preferences.settings.value.defaultSaveFormat
                val enc = when (saveFormat.shortLabel) {
                    "Nudi" -> KannadaEncodingChoice.NUDI; "Baraha" -> KannadaEncodingChoice.BARAHA; else -> KannadaEncodingChoice.UNICODE
                }
                val prepared = if (enc == KannadaEncodingChoice.UNICODE) unicodeDoc else {
                    KannadaTextConverter.prepareDocumentForLegacyExport(unicodeDoc, if (enc == KannadaEncodingChoice.BARAHA) "BRH Kannada" else "Nudi 01 e")
                }
                val safeName = desiredFileName.trim().let { if (it.endsWith(".docx", true)) it else "$it.docx" }
                val ingested = storageManager.saveNewFileInKadataDocuments(
                    safeName, DocFormat.DOCX, TargetFileType.DOCX.mimeType, enc.shortBadge, true
                ) { out -> docxWriter.write(prepared, out, defaultFont = enc.defaultFontForWord) }
                preferences.setDocumentFormatTag(ingested.entity.uriString, enc.shortBadge)
                ingested
            }.onSuccess { ingested ->
                _uiState.value = _uiState.value.copy(
                    isSaving = false, versionPreview = null,
                    statusMessage = "Saved version as ${ingested.entity.displayName} in Kadata Documents"
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(isSaving = false, statusMessage = "Failed to save version: ${err.localizedMessage ?: "storage error"}")
            }
        }
    }

    fun handleExportPayload(
        exportMode: String,
        idmJson: String,
        isAutoSave: Boolean,
        charCount: Int = idmJson.length,
        onRequestSafCreateFile: (CreateDocumentRequest) -> Unit,
        onSaveCompleteCallback: (() -> Unit)? = null
    ) {
        viewModelScope.launch {
            if (!isAutoSave) _uiState.value = _uiState.value.copy(isSaving = true)
            runCatching {
                val unicodeDoc = KannadaTextConverter.normalizeDocumentToUnicode(DocumentJson.fromJson(idmJson))
                val title = _uiState.value.documentTitle.ifBlank { "Document.docx" }
                val cleanBase = storageManager.stripEncodingSuffix(title.substringBeforeLast('.')).ifBlank { "Document" }

                fun prepareForEncoding(doc: ParsedDocument, encoding: KannadaEncodingChoice, forceUnicode: Boolean = false): ParsedDocument {
                    if (forceUnicode || encoding == KannadaEncodingChoice.UNICODE) return doc
                    return KannadaTextConverter.prepareDocumentForLegacyExport(doc, if (encoding == KannadaEncodingChoice.BARAHA) "BRH Kannada" else "Nudi 01 e")
                }

                when {
                    exportMode == "save_default_nudi" || exportMode == "save_default_baraha" ||
                        exportMode == "save_default_unicode" || exportMode == "save_internal" -> {
                        val enc = when (exportMode) {
                            "save_default_nudi" -> KannadaEncodingChoice.NUDI
                            "save_default_baraha" -> KannadaEncodingChoice.BARAHA
                            else -> KannadaEncodingChoice.UNICODE
                        }
                        val preparedDoc = prepareForEncoding(unicodeDoc, enc)
                        val savedName = performCanonicalSave(idmJson, preparedDoc, enc, charCount, isAutoSave)
                        markSaveComplete("Saved (${enc.shortBadge})", if (isAutoSave) null else "Saved $savedName (${enc.labelEn})")
                        onSaveCompleteCallback?.invoke()
                    }

                    exportMode.startsWith("convert_doc_") -> {
                        val targetEnc = when (exportMode.removePrefix("convert_doc_")) {
                            "nudi" -> KannadaEncodingChoice.NUDI; "baraha" -> KannadaEncodingChoice.BARAHA; else -> KannadaEncodingChoice.UNICODE
                        }
                        val convertedDoc = prepareForEncoding(unicodeDoc, targetEnc)
                        val (registered, _) = storageManager.saveExportedFileInKadataDocuments(
                            cleanBase, targetEnc.suffix, "docx", DocFormat.DOCX, TargetFileType.DOCX.mimeType, targetEnc.shortBadge
                        ) { out -> docxWriter.write(convertedDoc, out, defaultFont = targetEnc.defaultFontForWord) }
                        preferences.setDocumentFormatTag(registered.uriString, targetEnc.shortBadge)
                        val updatedInfo = _uiState.value.documentInfo.copy(
                            fileName = registered.displayName, fileFormat = DocFormat.DOCX,
                            detectedEncodingLabel = when (targetEnc) {
                                KannadaEncodingChoice.NUDI -> "Nudi • ನುಡಿ (PC)"
                                KannadaEncodingChoice.BARAHA -> "Baraha • ಬರಹ (PC)"
                                KannadaEncodingChoice.UNICODE -> "Modern Universal Kannada • ಆಧುನಿಕ ಸಾರ್ವತ್ರಿಕ ಕನ್ನಡ"
                            },
                            currentEncoding = targetEnc, shortFormatTag = targetEnc.shortBadge,
                            hasLegacyKannada = targetEnc != KannadaEncodingChoice.UNICODE,
                            hasUnicodeKannada = targetEnc == KannadaEncodingChoice.UNICODE
                        )
                        _uiState.value = _uiState.value.copy(
                            uriString = registered.uriString, documentTitle = registered.displayName, documentInfo = updatedInfo,
                            isExternalReadOnly = false, sourceAppLabel = null, isSaving = false, isDirty = false,
                            showSavedTick = true, saveGeneration = _uiState.value.saveGeneration + 1,
                            lastSavedLabel = "Converted (${targetEnc.shortBadge})",
                            statusMessage = "Converted to ${targetEnc.labelEn} (${registered.displayName})"
                        )
                    }

                    exportMode.startsWith("share_") -> {
                        val (encoding, fileType) = parseTwoStepMode(exportMode.removePrefix("share_"))
                        val preparedDoc = prepareForEncoding(unicodeDoc, encoding, fileType.forceUniversalUnicode)
                        val suffix = suffixFor(fileType, encoding)
                        val formatTag = if (fileType.forceUniversalUnicode) "Universal" else encoding.shortBadge
                        val (registered, shareFile) = storageManager.saveExportedFileInKadataDocuments(
                            cleanBase, suffix, fileType.extension, fileType.docFormat, fileType.mimeType, formatTag
                        ) { out -> writeDocumentToStream(preparedDoc, encoding, fileType, out) }
                        preferences.setDocumentFormatTag(registered.uriString, formatTag)
                        _uiState.value = _uiState.value.copy(isSaving = false)
                        if (fileType == TargetFileType.GOOGLE_DOCS) {
                            if (!GoogleDocsShareHelper.openOrShareToGoogleDocs(appContext, shareFile)) {
                                _uiState.value = _uiState.value.copy(statusMessage = "Google Docs app not installed — opened Android Share sheet instead.")
                            }
                        } else {
                            ShareHelper.shareFile(appContext, shareFile, fileType.mimeType, "Share ${fileType.label}")
                        }
                    }

                    exportMode.startsWith("savecopy_") -> {
                        val (encoding, fileType) = parseTwoStepMode(exportMode.removePrefix("savecopy_"))
                        val preparedDoc = prepareForEncoding(unicodeDoc, encoding, fileType.forceUniversalUnicode)
                        val suffix = suffixFor(fileType, encoding)
                        val suggested = "${cleanBase}_${suffix}.${fileType.extension}"
                        val currentUri = runCatching { Uri.parse(_uiState.value.uriString) }.getOrNull()

                        if (currentUri != null && storageManager.isKadataManagedUri(currentUri)) {
                            val formatTag = if (fileType.forceUniversalUnicode) "Universal" else encoding.shortBadge
                            val (registered, _) = storageManager.saveExportedFileInKadataDocuments(
                                cleanBase, suffix, fileType.extension, fileType.docFormat, fileType.mimeType, formatTag
                            ) { out -> writeDocumentToStream(preparedDoc, encoding, fileType, out) }
                            preferences.setDocumentFormatTag(registered.uriString, formatTag)
                            _uiState.value = _uiState.value.copy(isSaving = false, statusMessage = "Saved ${registered.displayName} in Kadata Documents")
                        } else {
                            val parentUri = currentUri?.let { FileLocationHelper.resolveParentDocumentUri(appContext, it, title) }
                            persistPendingSafExport(preparedDoc, encoding, fileType, suggested)
                            _uiState.value = _uiState.value.copy(isSaving = false)
                            onRequestSafCreateFile(CreateDocumentRequest(suggested, fileType.mimeType, parentUri))
                        }
                    }

                    else -> _uiState.value = _uiState.value.copy(isSaving = false)
                }
            }.onFailure { err ->
                val reason = err.localizedMessage ?: "Unknown storage error"
                if (isAutoSave) {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        warningMessage = "Auto-save failed ($reason). Please tap Save • ಸ್ವಯಂ ಉಳಿಸುವಿಕೆ ವಿಫಲವಾಗಿದೆ."
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isSaving = false, statusMessage = "Save / Export failed: $reason")
                }
            }
        }
    }

    private suspend fun performCanonicalSave(
        unicodeJson: String,
        preparedDoc: ParsedDocument,
        encoding: KannadaEncodingChoice,
        charCount: Int,
        isAutoSave: Boolean
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        saveMutex.withLock {
            val state = _uiState.value
            val currentUriStr = state.uriString
            val currentTitle = state.documentTitle.ifBlank { "Document.docx" }
            val currentFormat = state.documentInfo.fileFormat
            val ext = currentTitle.substringAfterLast('.', "docx").lowercase()

            val inPlaceFileType = when {
                ext == "docx" || currentFormat == DocFormat.DOCX -> TargetFileType.DOCX
                ext == "doc" || currentFormat == DocFormat.DOC -> TargetFileType.DOC
                ext == "rtf" || currentFormat == DocFormat.RTF -> TargetFileType.RTF
                ext == "odt" || currentFormat == DocFormat.ODT -> TargetFileType.ODT
                ext == "txt" || currentFormat == DocFormat.TXT -> TargetFileType.TXT
                else -> null
            }

            var finalUriStr = currentUriStr
            var finalTitle = currentTitle
            var finalFormat = currentFormat
            val finalBytes: Long

            val targetUri = currentUriStr.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            val canAttemptInPlace = targetUri != null && inPlaceFileType != null && !state.isExternalReadOnly

            val inPlaceResult = if (canAttemptInPlace) {
                runCatching {
                    storageManager.writeStagedToUri(targetUri!!) { out -> writeDocumentToStream(preparedDoc, encoding, inPlaceFileType!!, out) }
                }
            } else null

            if (inPlaceResult != null && inPlaceResult.isSuccess) {
                finalBytes = inPlaceResult.getOrThrow()
            } else {
                val cleanBase = storageManager.stripEncodingSuffix(currentTitle.substringBeforeLast('.')).ifBlank { "Document" }
                val rescued = storageManager.saveNewFileInKadataDocuments(
                    "$cleanBase.docx", DocFormat.DOCX, TargetFileType.DOCX.mimeType, encoding.shortBadge, true
                ) { out -> docxWriter.write(preparedDoc, out, defaultFont = encoding.defaultFontForWord) }
                if (currentUriStr.isNotBlank() && currentUriStr != rescued.entity.uriString) {
                    recoveryVault.rebindDocumentUri(currentUriStr, rescued.entity.uriString)
                }
                finalUriStr = rescued.entity.uriString
                finalTitle = rescued.entity.displayName
                finalFormat = DocFormat.DOCX
                finalBytes = rescued.entity.fileSizeBytes
                if (inPlaceResult != null && inPlaceResult.isFailure) {
                    _uiState.value = _uiState.value.copy(warningMessage = "Original folder was unavailable; saved safely to Kadata Documents as $finalTitle.")
                }
            }

            val now = System.currentTimeMillis()
            val existing = recentFileDao.getByUri(finalUriStr)
            if (existing != null) {
                recentFileDao.upsert(
                    existing.copy(
                        displayName = finalTitle,
                        detectedFormat = if (finalFormat == DocFormat.DOC && inPlaceFileType == TargetFileType.DOC) DocFormat.RTF else finalFormat,
                        fileSizeBytes = finalBytes,
                        savedUnicodeUri = null,
                        kannadaFormat = encoding.shortBadge,
                        lastOpenedAt = now,
                        lastModifiedAt = now,
                        isEdited = true,
                        sourceAppLabel = null
                    )
                )
            } else {
                recentFileDao.upsert(
                    RecentFileEntity(
                        uriString = finalUriStr,
                        displayName = finalTitle,
                        detectedFormat = finalFormat,
                        mimeType = mimeForFileName(finalTitle, finalFormat),
                        fileSizeBytes = finalBytes,
                        isLocallyImported = Uri.parse(finalUriStr).authority == "${appContext.packageName}.fileprovider",
                        savedUnicodeUri = null,
                        kannadaFormat = encoding.shortBadge,
                        lastOpenedAt = now,
                        lastModifiedAt = now,
                        isEdited = true,
                        sourceAppLabel = null
                    )
                )
            }
            preferences.setDocumentFormatTag(finalUriStr, encoding.shortBadge)

            val deltaForRev = pendingDeltaSinceSave
            recoveryVault.recordVersionSnapshot(
                finalUriStr, finalTitle, unicodeJson, charCount, deltaForRev,
                if (isAutoSave) "Auto-Save" else "Manual Save", false, sessionBaselineRevisionId
            )

            recoveryVault.markSessionClean(finalUriStr)
            if (currentUriStr.isNotBlank() && currentUriStr != finalUriStr) {
                recoveryVault.markSessionClean(currentUriStr)
                draftDao.deleteDraft(currentUriStr)
            }
            draftDao.deleteDraft(finalUriStr)

            pendingDeltaSinceSave = 0
            pendingDeltaSinceSnapshot = 0
            lastSnapshotAtMs = now
            sourceFileModifiedAtMs = now

            _uiState.value = _uiState.value.copy(
                uriString = finalUriStr,
                documentTitle = finalTitle,
                isExternalReadOnly = false,
                sourceAppLabel = null,
                documentInfo = _uiState.value.documentInfo.copy(
                    fileName = finalTitle,
                    fileFormat = DocFormat.displayFormatFor(finalTitle, finalFormat),
                    shortFormatTag = encoding.shortBadge
                )
            )
            finalTitle
        }
    }

    private fun markSaveComplete(label: String, snackbar: String?) {
        _uiState.value = _uiState.value.copy(
            isSaving = false, isDirty = false, showSavedTick = true,
            saveGeneration = _uiState.value.saveGeneration + 1,
            lastSavedLabel = label, statusMessage = snackbar
        )
    }

    fun writePendingSafCopy(targetUri: Uri) {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { loadPendingSafExport() } ?: return@launch
            val (doc, encoding, fileType, suggestedName) = loaded
            _uiState.value = _uiState.value.copy(isSaving = true)
            runCatching {
                withContext(Dispatchers.IO + NonCancellable) {
                    storageManager.writeStagedToUri(targetUri) { out -> writeDocumentToStream(doc, encoding, fileType, out) }
                }
                val formatTag = if (fileType.forceUniversalUnicode) "Universal" else encoding.shortBadge
                val registered = storageManager.registerSafSavedUri(targetUri, suggestedName, fileType.docFormat, fileType.mimeType, formatTag)
                preferences.setDocumentFormatTag(registered.uriString, formatTag)
            }.onSuccess {
                _uiState.value = _uiState.value.copy(isSaving = false, statusMessage = "Saved ${encoding.labelEn} (.${fileType.extension}) & added to Recent Files")
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(isSaving = false, statusMessage = err.localizedMessage ?: "Failed to save copy")
            }
        }
    }

    private fun suffixFor(fileType: TargetFileType, encoding: KannadaEncodingChoice): String = when (fileType) {
        TargetFileType.HTML_ZIP -> "Web"; TargetFileType.GOOGLE_DOCS -> "GoogleDocs"
        else -> if (fileType.forceUniversalUnicode) "Universal" else encoding.suffix
    }

    private fun parseTwoStepMode(suffix: String): Pair<KannadaEncodingChoice, TargetFileType> {
        val encoding = KannadaEncodingChoice.entries.firstOrNull { it.id == suffix.substringBefore('_') } ?: KannadaEncodingChoice.NUDI
        val fileType = TargetFileType.entries.firstOrNull { it.id == suffix.substringAfter('_', "docx") } ?: TargetFileType.DOCX
        return encoding to fileType
    }

    private suspend fun writeDocumentToStream(doc: ParsedDocument, encoding: KannadaEncodingChoice, fileType: TargetFileType, out: OutputStream) {
        when (fileType) {
            TargetFileType.PDF -> pdfExporter.exportToPdf(doc, _uiState.value.documentTitle.ifBlank { "Document" }).inputStream().use { it.copyTo(out) }
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

    private fun mimeForFileName(fileName: String, format: DocFormat): String {
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

    private fun inspectDocumentInfo(doc: ParsedDocument): DocumentInfo {
        val allRuns = mutableListOf<TextRun>()
        for (block in doc.blocks) {
            when (block) {
                is DocBlock.Paragraph -> allRuns.addAll(block.runs)
                is DocBlock.Table -> block.rows.forEach { row -> row.forEach { cell -> cell.forEach { p -> allRuns.addAll(p.runs) } } }
            }
        }
        val fontFamilies = linkedSetOf<String>()
        val detectedCategories = linkedSetOf<String>()
        var hasNudi = false; var hasBaraha = false; var hasLegacy = false; var hasUnicode = false; var hasEnglish = false
        for (run in allRuns) {
            val text = run.text
            if (text.isBlank()) continue
            val font = run.fontFamily?.trim()
            if (!font.isNullOrEmpty()) fontFamilies.add(font)
            when {
                font != null && NUDI_FONT_REGEX.containsMatchIn(font) -> { hasLegacy = true; hasNudi = true; detectedCategories.add("Nudi • ನುಡಿ (PC)") }
                font != null && BARAHA_FONT_REGEX.containsMatchIn(font) -> { hasLegacy = true; hasBaraha = true; detectedCategories.add("Baraha • ಬರಹ (PC)") }
                font != null && SHREELIPI_FONT_REGEX.containsMatchIn(font) -> { hasLegacy = true; detectedCategories.add("ShreeLipi") }
                font != null && AKRUTI_FONT_REGEX.containsMatchIn(font) -> { hasLegacy = true; detectedCategories.add("Akruti") }
                HIGH_ASCII_NUDI_REGEX.containsMatchIn(text) -> { hasLegacy = true; hasNudi = true; detectedCategories.add("Nudi • ನುಡಿ (PC)") }
                KANNADA_UNICODE_REGEX.containsMatchIn(text) -> { hasUnicode = true; detectedCategories.add("Modern Universal Kannada • ಆಧುನಿಕ ಸಾರ್ವತ್ರಿಕ ಕನ್ನಡ") }
                LATIN_WORD_REGEX.containsMatchIn(text) -> hasEnglish = true
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
            doc.fileName, DocFormat.displayFormatFor(doc.fileName, doc.sourceFormat), summary, currentEnc,
            shortTag, fontFamilies.toList(), hasLegacy, hasUnicode, hasEnglish
        )
    }

    class Factory(
        private val appContext: Context,
        private val parserFacade: DocumentParserFacade,
        private val storageManager: SafStorageManager,
        private val recoveryVault: RecoveryVaultManager,
        private val recentFileDao: RecentFileDao,
        private val draftDao: DraftDao,
        private val preferences: AppPreferences,
        private val docxWriter: DocxWriter,
        private val rtfDocWriter: RtfDocWriter,
        private val pdfExporter: PdfExporter,
        private val odtWriter: OdtWriter = OdtWriter(),
        private val txtWriter: TxtWriter = TxtWriter(),
        private val htmlZipWriter: HtmlZipWriter = HtmlZipWriter(),
        private val epubWriter: EpubWriter = EpubWriter(htmlZipWriter)
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorViewModel(
            appContext, parserFacade, storageManager, recoveryVault, recentFileDao, draftDao, preferences,
            docxWriter, rtfDocWriter, pdfExporter, odtWriter, txtWriter, htmlZipWriter, epubWriter
        ) as T
    }
}
