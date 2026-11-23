package com.kannadadoc.export

import com.kannadadoc.parser.DocBlock
import com.kannadadoc.parser.ParsedDocument
import java.io.OutputStream

class TxtWriter {

    fun write(doc: ParsedDocument, outputStream: OutputStream) {
        val sb = StringBuilder()
        var orderedCounter = 0

        for (block in doc.blocks) {
            when (block) {
                is DocBlock.Paragraph -> {
                    val lineText = block.runs.joinToString("") { it.text }
                    when (block.listType) {
                        "bullet" -> {
                            orderedCounter = 0
                            sb.append("• ").append(lineText).append("\n")
                        }
                        "ordered" -> {
                            orderedCounter++
                            sb.append("$orderedCounter. ").append(lineText).append("\n")
                        }
                        else -> {
                            orderedCounter = 0
                            sb.append(lineText).append("\n\n")
                        }
                    }
                }
                is DocBlock.Table -> {
                    orderedCounter = 0
                    for (row in block.rows) {
                        val rowText = row.joinToString("\t") { cell ->
                            cell.joinToString(" ") { p -> p.runs.joinToString("") { it.text } }.trim()
                        }
                        sb.append(rowText).append("\n")
                    }
                    sb.append("\n")
                }
            }
        }

        outputStream.write(sb.toString().trimEnd().toByteArray(Charsets.UTF_8))
    }
}
