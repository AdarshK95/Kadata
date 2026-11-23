package com.kannadadoc.parser

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Strict stream-reading utility for all document parsers.
 * Reads at most [maxBytes] and probes 1 extra byte; if the stream exceeds [maxBytes],
 * immediately throws [DocumentSizeLimitException] instead of silently truncating content.
 */
object BoundedStreamReader {

    /**
     * Reads the full stream up to [maxBytes]. Throws [DocumentSizeLimitException] if even 1 byte
     * exists beyond [maxBytes] so oversized files are cleanly rejected with an explicit user message.
     */
    fun readStrict(
        inputStream: InputStream,
        maxBytes: Int,
        fileName: String = "",
        formatLabel: String = "Document",
        reportedTotalSizeBytes: Long? = null
    ): ByteArray {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val baos = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024).coerceAtLeast(1024))
        val buf = ByteArray(8192)
        var total = 0

        while (true) {
            val remaining = maxBytes - total
            if (remaining <= 0) {
                if (inputStream.read() != -1) {
                    throw DocumentSizeLimitException(
                        fileName = fileName,
                        formatLabel = formatLabel,
                        actualSizeBytes = reportedTotalSizeBytes,
                        maxAllowedBytes = maxBytes.toLong()
                    )
                }
                break
            }
            val read = inputStream.read(buf, 0, minOf(buf.size, remaining))
            if (read <= 0) break
            baos.write(buf, 0, read)
            total += read
        }
        return baos.toByteArray()
    }

    /**
     * Peeks up to [maxBytes] from [buffered] and resets back to the start without buffering the rest.
     */
    fun peekHeader(buffered: BufferedInputStream, maxBytes: Int): ByteArray {
        buffered.mark(maxBytes + 1)
        val out = ByteArray(maxBytes)
        var total = 0
        while (total < maxBytes) {
            val n = buffered.read(out, total, maxBytes - total)
            if (n <= 0) break
            total += n
        }
        buffered.reset()
        return if (total == maxBytes) out else out.copyOf(total)
    }
}
