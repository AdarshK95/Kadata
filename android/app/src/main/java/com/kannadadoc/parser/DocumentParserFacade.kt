package com.kannadadoc.parser

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DocumentParserFacade(
    private val docxParser: DocxParser = DocxParser(),
    private val docHwpfParser: DocHwpfParser = DocHwpfParser(),
    private val rtfParser: RtfParser = RtfParser(),
    private val txtParser: TxtParser = TxtParser(),
    private val pdfTextParser: PdfTextParser = PdfTextParser()
) {
    suspend fun parseDocument(
        context: Context,
        uri: Uri,
        fileName: String,
        format: DocFormat
    ): Result<ParsedDocument> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                when (format) {
                    DocFormat.DOCX -> docxParser.parse(stream, uri.toString(), fileName)
                    DocFormat.DOC -> docHwpfParser.parse(stream, uri.toString(), fileName)
                    DocFormat.RTF -> rtfParser.parse(stream, uri.toString(), fileName)
                    DocFormat.TXT -> txtParser.parse(stream, uri.toString(), fileName)
                    DocFormat.PDF, DocFormat.PDF_EXTRACTED ->
                        pdfTextParser.parse(stream, uri.toString(), fileName)
                    DocFormat.UNSUPPORTED ->
                        error("Unsupported file format for $fileName")
                }
            } ?: error("Unable to open file stream for $fileName")
        }
    }

    fun createBlankDocument(uriString: String, fileName: String): ParsedDocument =
        ParsedDocument(
            sourceUri = uriString,
            fileName = fileName,
            sourceFormat = DocFormat.DOCX,
            blocks = listOf(
                DocBlock.Paragraph(
                    runs = listOf(TextRun(text = "", fontFamily = "Noto Sans Kannada"))
                )
            )
        )
}
