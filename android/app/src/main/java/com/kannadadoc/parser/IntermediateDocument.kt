package com.kannadadoc.parser

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ParsedDocument(
    val sourceUri: String,
    val fileName: String,
    val sourceFormat: DocFormat,
    val blocks: List<DocBlock>,
    val warningMessage: String? = null
)

@Serializable
sealed class DocBlock {
    @Serializable
    @SerialName("paragraph")
    data class Paragraph(
        val runs: List<TextRun>,
        val headingLevel: Int = 0,
        val alignment: String = "left",
        val listType: String? = null
    ) : DocBlock()

    @Serializable
    @SerialName("table")
    data class Table(
        val rows: List<List<List<Paragraph>>>
    ) : DocBlock()
}

@Serializable
data class TextRun(
    val text: String,
    val fontFamily: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val fontSizePt: Float? = null,
    val colorHex: String? = null
)

object DocumentJson {
    val codec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    fun toJson(doc: ParsedDocument): String = codec.encodeToString(ParsedDocument.serializer(), doc)

    fun fromJson(json: String): ParsedDocument = codec.decodeFromString(ParsedDocument.serializer(), json)
}
