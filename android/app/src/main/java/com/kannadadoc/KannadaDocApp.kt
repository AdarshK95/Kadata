package com.kannadadoc

import android.app.Application
import android.content.ComponentCallbacks2
import com.kannadadoc.data.local.AppDatabase
import com.kannadadoc.data.local.AppPreferences
import com.kannadadoc.data.storage.RecoveryVaultManager
import com.kannadadoc.data.storage.SafStorageManager
import com.kannadadoc.export.DocxWriter
import com.kannadadoc.export.EpubWriter
import com.kannadadoc.export.HtmlZipWriter
import com.kannadadoc.export.OdtWriter
import com.kannadadoc.export.PdfExporter
import com.kannadadoc.export.RtfDocWriter
import com.kannadadoc.export.TxtWriter
import com.kannadadoc.parser.DocumentParserFacade
import com.kannadadoc.parser.DocumentRouter
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class KannadaDocApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _memoryPressureEvents = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val memoryPressureEvents: SharedFlow<Int> = _memoryPressureEvents.asSharedFlow()

    lateinit var database: AppDatabase
        private set
    lateinit var preferences: AppPreferences
        private set
    lateinit var documentRouter: DocumentRouter
        private set
    lateinit var parserFacade: DocumentParserFacade
        private set
    lateinit var storageManager: SafStorageManager
        private set
    lateinit var recoveryVault: RecoveryVaultManager
        private set
    lateinit var docxWriter: DocxWriter
        private set
    lateinit var rtfDocWriter: RtfDocWriter
        private set
    lateinit var odtWriter: OdtWriter
        private set
    lateinit var txtWriter: TxtWriter
        private set
    lateinit var htmlZipWriter: HtmlZipWriter
        private set
    lateinit var epubWriter: EpubWriter
        private set
    lateinit var pdfExporter: PdfExporter
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)

        database = AppDatabase.build(this)
        preferences = AppPreferences(this)
        documentRouter = DocumentRouter()
        parserFacade = DocumentParserFacade()
        storageManager = SafStorageManager(this, database.recentFileDao(), documentRouter, parserFacade)
        recoveryVault = RecoveryVaultManager(this, database.revisionDao())
        docxWriter = DocxWriter()
        rtfDocWriter = RtfDocWriter()
        odtWriter = OdtWriter()
        txtWriter = TxtWriter()
        htmlZipWriter = HtmlZipWriter()
        epubWriter = EpubWriter(htmlZipWriter)
        pdfExporter = PdfExporter(this)

        appScope.launch {
            runCatching { storageManager.migrateLegacySavedUnicodeRows() }
            delay(4_500)
            runCatching { storageManager.sweepOrphanedFiles() }
            if (preferences.shouldRunThrottledVaultSweep()) {
                runCatching {
                    val activeUris = database.recentFileDao().getAllSnapshot().map { it.uriString }.toSet()
                    recoveryVault.runLifecycleCleanup(activeUris)
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            _memoryPressureEvents.tryEmit(level)
        }
    }
}
