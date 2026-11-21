package com.kannadadoc

import android.app.Application
import com.kannadadoc.data.local.AppDatabase
import com.kannadadoc.data.local.AppPreferences
import com.kannadadoc.data.storage.SafStorageManager
import com.kannadadoc.export.DocxWriter
import com.kannadadoc.export.PdfExporter
import com.kannadadoc.export.RtfDocWriter
import com.kannadadoc.parser.DocumentParserFacade
import com.kannadadoc.parser.DocumentRouter
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class KannadaDocApp : Application() {

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

    lateinit var docxWriter: DocxWriter
        private set

    lateinit var rtfDocWriter: RtfDocWriter
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
        storageManager = SafStorageManager(
            context = this,
            recentFileDao = database.recentFileDao(),
            documentRouter = documentRouter
        )
        docxWriter = DocxWriter()
        rtfDocWriter = RtfDocWriter()
        pdfExporter = PdfExporter(this)
    }
}
