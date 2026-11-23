package com.kannadadoc.parser

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLDecoder

class EpubParser(
    private val htmlParser: HtmlParser = HtmlParser()
) {
    companion object {
        /**
         * Resolves an OPF manifest href relative to the OPF directory:
         * - Strips `#fragment` / `?query` anchors
         * - Decodes percent-encoded path segments (e.g. `Chapter%201.xhtml` -> `Chapter 1.xhtml`)
         * - Delegates canonical slash, `..`, and lowercase normalization to [SafeZipReader.normalizeEntryPath].
         */
        internal fun resolveOpfHref(baseDir: String, rawHref: String): String {
            val withoutAnchor = rawHref.substringBefore('#').substringBefore('?').trim()
            val decoded = runCatching {
                URLDecoder.decode(withoutAnchor.replace("+", "%2B"), "UTF-8")
            }.getOrDefault(withoutAnchor)
            val combined = if (baseDir.isEmpty()) decoded else "$baseDir/$decoded"
            return SafeZipReader.normalizeEntryPath(combined)
        }
    }

    fun parse(inputStream: InputStream, sourceUri: String, fileName: String): ParsedDocument {
        val entries = SafeZipReader.readMatchingEntries(
            inputStream = inputStream,
            fileName = fileName,
            formatLabel = "EPUB"
        ) { norm ->
            norm == "meta-inf/container.xml" ||
                norm.endsWith(".opf") ||
                norm.endsWith(".xhtml") ||
                norm.endsWith(".html") ||
                norm.endsWith(".htm")
        }

        val warnings = mutableListOf<String>()
        val opfPath = findOpfPath(entries["meta-inf/container.xml"])
            ?.let { SafeZipReader.normalizeEntryPath(it) }
            ?: entries.keys.firstOrNull { it.endsWith(".opf") }

        val spinePaths = if (opfPath != null && entries[opfPath] != null) {
            parseSpinePaths(opfPath, entries[opfPath]!!)
        } else {
            emptyList()
        }

        val orderedChapterPaths = if (spinePaths.isNotEmpty()) {
            spinePaths
        } else {
            warnings.add("EPUB table of contents metadata was missing or malformed; loaded chapters in filename order.")
            entries.keys
                .filter { l ->
                    (l.endsWith(".xhtml") || l.endsWith(".html") || l.endsWith(".htm")) &&
                        !l.endsWith("nav.xhtml") && !l.endsWith("toc.xhtml")
                }
                .sorted()
        }

        val allBlocks = mutableListOf<DocBlock>()
        var missingChapters = 0
        var skippedChapters = 0

        for (chapterPath in orderedChapterPaths) {
            val bytes = entries[chapterPath]
            if (bytes == null) {
                missingChapters++
                continue
            }
            val chapterBlocks = runCatching {
                val html = String(bytes, Charsets.UTF_8)
                htmlParser.parseHtmlToBlocks(html)
            }.getOrElse {
                skippedChapters++
                emptyList()
            }
            if (chapterBlocks.isNotEmpty()) {
                if (allBlocks.isNotEmpty()) {
                    allBlocks.add(DocBlock.Paragraph(runs = emptyList()))
                }
                allBlocks.addAll(chapterBlocks)
            }
        }

        val finalBlocks = if (allBlocks.isEmpty()) {
            listOf(DocBlock.Paragraph(runs = listOf(TextRun(text = ""))))
        } else {
            allBlocks
        }

        if (missingChapters > 0) {
            warnings.add("$missingChapters chapter file(s) referenced by this EPUB were missing from the archive.")
        }
        if (skippedChapters > 0) {
            warnings.add("Skipped $skippedChapters malformed chapter(s) while reading this EPUB.")
        }
        val warning = warnings.takeIf { it.isNotEmpty() }?.joinToString(" ")

        return ParsedDocument(
            sourceUri = sourceUri,
            fileName = fileName,
            sourceFormat = DocFormat.EPUB,
            blocks = finalBlocks,
            warningMessage = warning
        )
    }

    private fun findOpfPath(containerBytes: ByteArray?): String? {
        if (containerBytes == null) return null
        return try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(ByteArrayInputStream(containerBytes), "UTF-8")
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name.equals("rootfile", ignoreCase = true)) {
                    val fullPath = parser.getAttributeValue(null, "full-path")
                    if (!fullPath.isNullOrBlank()) return fullPath
                }
                event = parser.next()
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSpinePaths(opfPath: String, opfBytes: ByteArray): List<String> {
        val baseDir = opfPath.substringBeforeLast('/', "")
        val manifest = mutableMapOf<String, Pair<String, String>>() // id -> (href, mediaType)
        val spineIds = mutableListOf<String>()

        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(ByteArrayInputStream(opfBytes), "UTF-8")
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name.substringAfter(':').lowercase()) {
                        "item" -> {
                            val id = parser.getAttributeValue(null, "id")
                            val href = parser.getAttributeValue(null, "href")
                            val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                            if (!id.isNullOrBlank() && !href.isNullOrBlank()) {
                                manifest[id] = href to mediaType
                            }
                        }
                        "itemref" -> {
                            val idref = parser.getAttributeValue(null, "idref")
                            if (!idref.isNullOrBlank()) spineIds.add(idref)
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            // Fall through to partial results
        }

        val resolved = mutableListOf<String>()
        for (id in spineIds) {
            val (href, mediaType) = manifest[id] ?: continue
            val normalizedPath = resolveOpfHref(baseDir, href)
            if (mediaType.contains("html", true) ||
                normalizedPath.endsWith(".xhtml") ||
                normalizedPath.endsWith(".html") ||
                normalizedPath.endsWith(".htm")
            ) {
                resolved.add(normalizedPath)
            }
        }
        return resolved
    }
}
