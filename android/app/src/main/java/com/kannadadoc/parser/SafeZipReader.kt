package com.kannadadoc.parser

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Shared, defensive ZIP archive reader used across DOCX, ODT, EPUB, Zipped HTML,
 * DocumentRouter, and metadata extraction.
 * - Normalizes Windows backslashes (`word\document.xml`), leading slashes (`/word/document.xml`),
 *   and `..` segments into canonical lowercase POSIX paths (`word/document.xml`).
 * - Enforces STRICT fail-fast per-entry, total-decompressed, and entry-count limits:
 *   throws [DocumentSizeLimitException] immediately rather than silently truncating XML/HTML content.
 */
object SafeZipReader {
    const val DEFAULT_MAX_ENTRY_BYTES = DocumentSizePolicy.MAX_ZIP_ENTRY_BYTES
    const val DEFAULT_MAX_TOTAL_BYTES = DocumentSizePolicy.MAX_ZIP_TOTAL_BYTES
    const val DEFAULT_MAX_ENTRIES = DocumentSizePolicy.MAX_ZIP_ENTRIES

    fun normalizeEntryPath(rawName: String): String {
        val slashFixed = rawName.replace('\\', '/').trimStart('/')
        val parts = ArrayDeque<String>()
        for (seg in slashFixed.split('/')) {
            when {
                seg.isEmpty() || seg == "." -> continue
                seg == ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(seg)
            }
        }
        return parts.joinToString("/").lowercase()
    }

    /**
     * Reads all entries matching [predicate] into a map keyed by normalized lowercase path.
     * Throws [DocumentSizeLimitException] if any entry exceeds [maxEntryBytes], total decompressed
     * bytes exceed [maxTotalBytes], or archive entry count exceeds [maxEntries].
     */
    fun readMatchingEntries(
        inputStream: InputStream,
        maxEntryBytes: Int = DEFAULT_MAX_ENTRY_BYTES,
        maxTotalBytes: Int = DEFAULT_MAX_TOTAL_BYTES,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
        fileName: String = "",
        formatLabel: String = "Archive",
        predicate: (normalizedPath: String) -> Boolean
    ): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        var entryCount = 0

        ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryCount++
                if (entryCount > maxEntries) {
                    throw DocumentSizeLimitException(
                        fileName = fileName,
                        formatLabel = formatLabel,
                        actualSizeBytes = null,
                        maxAllowedBytes = maxTotalBytes.toLong(),
                        customReasonEn = "This $formatLabel archive contains more than $maxEntries internal files and was rejected to protect device memory.",
                        customReasonKn = "ಈ $formatLabel ಫೈಲ್‌ನಲ್ಲಿ $maxEntries ಕ್ಕಿಂತ ಹೆಚ್ಚು ಆಂತರಿಕ ಭಾಗಗಳಿವೆ. ಸಾಧನದ ಮೆಮೊರಿ ಸುರಕ್ಷತೆಗಾಗಿ ಇದನ್ನು ತೆರೆಯಲಾಗಲಿಲ್ಲ."
                    )
                }
                if (!entry.isDirectory) {
                    val norm = normalizeEntryPath(entry.name)
                    if (norm.isNotEmpty() && predicate(norm)) {
                        val remainingBudget = (maxTotalBytes - totalBytes).coerceAtMost(maxEntryBytes.toLong()).toInt()
                        if (remainingBudget <= 0) {
                            throw DocumentSizeLimitException(
                                fileName = fileName,
                                formatLabel = formatLabel,
                                actualSizeBytes = null,
                                maxAllowedBytes = maxTotalBytes.toLong()
                            )
                        }
                        val bytes = readStrictEntry(
                            stream = zis,
                            maxBytes = remainingBudget,
                            limitForError = if (maxTotalBytes - totalBytes < maxEntryBytes) maxTotalBytes.toLong() else maxEntryBytes.toLong(),
                            fileName = fileName,
                            formatLabel = formatLabel
                        )
                        totalBytes += bytes.size
                        result[norm] = bytes
                    }
                }
                entry = zis.nextEntry
            }
        }
        return result
    }

    /**
     * Streams through ZIP entries for fast format classification without buffering large payloads.
     */
    fun inspectEntries(
        inputStream: InputStream,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
        visitor: (normalizedPath: String, isDirectory: Boolean, readSample: (maxBytes: Int) -> ByteArray) -> Boolean
    ) {
        var entryCount = 0
        ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryCount++
                if (entryCount > maxEntries) break
                val norm = normalizeEntryPath(entry.name)
                val keepGoing = visitor(norm, entry.isDirectory) { sampleLimit ->
                    readSampleOnly(zis, sampleLimit)
                }
                if (!keepGoing) break
                entry = zis.nextEntry
            }
        }
    }

    private fun readStrictEntry(
        stream: InputStream,
        maxBytes: Int,
        limitForError: Long,
        fileName: String,
        formatLabel: String
    ): ByteArray {
        val baos = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024).coerceAtLeast(256))
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val remaining = maxBytes - total
            if (remaining <= 0) {
                if (stream.read() != -1) {
                    throw DocumentSizeLimitException(
                        fileName = fileName,
                        formatLabel = formatLabel,
                        actualSizeBytes = null,
                        maxAllowedBytes = limitForError
                    )
                }
                break
            }
            val n = stream.read(buf, 0, minOf(buf.size, remaining))
            if (n <= 0) break
            baos.write(buf, 0, n)
            total += n
        }
        return baos.toByteArray()
    }

    private fun readSampleOnly(stream: InputStream, maxBytes: Int): ByteArray {
        val baos = ByteArrayOutputStream(minOf(maxBytes, 1024).coerceAtLeast(64))
        val buf = ByteArray(minOf(maxBytes, 1024).coerceAtLeast(64))
        var total = 0
        while (total < maxBytes) {
            val n = stream.read(buf, 0, minOf(buf.size, maxBytes - total))
            if (n <= 0) break
            baos.write(buf, 0, n)
            total += n
        }
        return baos.toByteArray()
    }
}
