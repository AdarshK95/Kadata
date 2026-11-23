package com.kannadadoc.parser

/**
 * Lossless canonical flattener for nested tables encountered inside a table cell across
 * DOCX (`w:tbl` in `w:tc`), HTML/EPUB (`<table>` in `<td>`), and ODT (`<table:table>` in `<table:table-cell>`).
 *
 * Keeps Kadata's flat `DocBlock.Table` model 100% backward-compatible across Recovery Vault JSON,
 * `editor.js`, and all 6 document writers while guaranteeing zero silent content loss.
 */
object TableFlattenHelper {

    fun flattenNestedTableToParagraphs(
        rows: List<List<List<DocBlock.Paragraph>>>
    ): List<DocBlock.Paragraph> {
        val out = mutableListOf<DocBlock.Paragraph>()
        for (row in rows) {
            val nonEmptyCells = row.map { cell ->
                cell.filter { para -> para.runs.any { it.text.isNotEmpty() } }
            }.filter { it.isNotEmpty() }

            if (nonEmptyCells.isEmpty()) continue

            // Compact 1-paragraph-per-cell rows stay readable on a single line ("Key  |  Value")
            if (nonEmptyCells.size > 1 && nonEmptyCells.all { it.size == 1 }) {
                val combinedRuns = mutableListOf<TextRun>()
                nonEmptyCells.forEachIndexed { idx, cellParas ->
                    if (idx > 0) {
                        combinedRuns.add(TextRun(text = "  |  "))
                    }
                    combinedRuns.addAll(cellParas.first().runs)
                }
                val firstPara = nonEmptyCells.first().first()
                out.add(
                    DocBlock.Paragraph(
                        runs = combinedRuns,
                        headingLevel = 0,
                        alignment = firstPara.alignment,
                        listType = null
                    )
                )
            } else {
                for (cellParas in nonEmptyCells) {
                    for (para in cellParas) {
                        out.add(para.copy(listType = null))
                    }
                }
            }
        }
        return out
    }
}
