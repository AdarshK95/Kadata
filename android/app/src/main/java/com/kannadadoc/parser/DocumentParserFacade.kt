package com.kannadadoc.parser

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class DocumentParserFacade(
    private val docxParser: DocxParser = DocxParser(),
    private val docHwpfParser: DocHwpfParser = DocHwpfParser(),
    private val odtParser: OdtParser = OdtParser(),
    private val rtfParser: RtfParser = RtfParser(),
    private val txtParser: TxtParser = TxtParser(),
    private val htmlParser: HtmlParser = HtmlParser(),
    private val epubParser: EpubParser = EpubParser(),
    private val pdfTextParser: PdfTextParser = PdfTextParser()
) {
    suspend fun parseDocument(
        context: Context,
        uri: Uri,
        fileName: String,
        format: DocFormat
    ): Result<ParsedDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val reportedSize = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull()?.takeIf { it > 0L }

            // Early fail-fast size check for non-ZIP formats where raw file size == content size
            val rawLimit = DocumentSizePolicy.maxRawFileBytes(format)
            if (reportedSize != null && reportedSize > rawLimit) {
                throw DocumentSizeLimitException(
                    fileName = fileName,
                    formatLabel = format.name,
                    actualSizeBytes = reportedSize,
                    maxAllowedBytes = rawLimit
                )
            }

            context.contentResolver.openInputStream(uri)?.use { stream ->
                when (format) {
                    DocFormat.DOCX -> docxParser.parse(stream, uri.toString(), fileName)
                    DocFormat.DOC -> {
                        val bytes = BoundedStreamReader.readStrict(
                            inputStream = stream,
                            maxBytes = DocumentSizePolicy.MAX_LEGACY_DOC_BYTES,
                            fileName = fileName,
                            formatLabel = "DOC",
                            reportedTotalSizeBytes = reportedSize
                        )
                        docHwpfParser.parse(ByteArrayInputStream(bytes), uri.toString(), fileName)
                    }
                    DocFormat.ODT -> odtParser.parse(stream, uri.toString(), fileName)
                    DocFormat.RTF -> {
                        val bytes = BoundedStreamReader.readStrict(
                            inputStream = stream,
                            maxBytes = DocumentSizePolicy.MAX_TEXT_OR_RTF_BYTES,
                            fileName = fileName,
                            formatLabel = "RTF",
                            reportedTotalSizeBytes = reportedSize
                        )
                        rtfParser.parse(ByteArrayInputStream(bytes), uri.toString(), fileName)
                    }
                    DocFormat.TXT -> {
                        val bytes = BoundedStreamReader.readStrict(
                            inputStream = stream,
                            maxBytes = DocumentSizePolicy.MAX_TEXT_OR_RTF_BYTES,
                            fileName = fileName,
                            formatLabel = "TXT",
                            reportedTotalSizeBytes = reportedSize
                        )
                        txtParser.parse(ByteArrayInputStream(bytes), uri.toString(), fileName)
                    }
                    DocFormat.HTML -> htmlParser.parse(stream, uri.toString(), fileName)
                    DocFormat.EPUB -> epubParser.parse(stream, uri.toString(), fileName)
                    DocFormat.PDF, DocFormat.PDF_EXTRACTED ->
                        pdfTextParser.parse(stream, uri.toString(), fileName)
                    DocFormat.UNSUPPORTED ->
                        error("Unsupported file format for $fileName")
                }
            } ?: error("Unable to open file stream for $fileName. The file may have been moved, deleted, or permission expired.")
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
