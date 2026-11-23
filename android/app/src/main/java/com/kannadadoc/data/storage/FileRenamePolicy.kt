package com.kannadadoc.data.storage

import java.net.URI

/**
 * Pure Kotlin policy for Patch A physical file renaming:
 * - Restricts Rename strictly to Kadata-owned storage (`Documents/Kadata Documents/` via MediaStore
 *   and internal `filesDir/documents/` fallback) until SD/USB reboot persistence is validated.
 * - Validates and normalizes target filenames, preserving extensions even when names contain
 *   internal dots (e.g. `V.S.K. 24-11-2026`, `Dr. Patil`, `Vol. 1.2`) and rejecting format-swapping
 *   extensions so Rename never corrupts file types on disk.
 */
object FileRenamePolicy {

    private const val MAX_FILENAME_LENGTH = 140
    private val ILLEGAL_FILENAME_CHARS = Regex("[/\\\\:*?\"<>|\\r\\n\\t\\u0000]")

    private val KNOWN_DOCUMENT_EXTENSIONS = setOf(
        "docx", "doc", "pdf", "odt", "rtf", "txt", "html", "htm", "epub", "zip"
    )

    /**
     * Returns true ONLY when [uriString] points to a Kadata-owned file eligible for physical rename:
     * - MediaStore URI (`content://media/...`) created by Kadata in `Documents/Kadata Documents/`
     * - App FileProvider URI under `saved_docs/` (or legacy `documents/`), NEVER `imported_docs/`
     *   or `shared_exports/`
     * Always returns false for SAF external storage / SD / USB (`com.android.externalstorage.documents`,
     * `com.android.providers.downloads.documents`), WhatsApp, Gmail, Drive, or third-party providers.
     */
    fun isKadataOwnedRenamableUri(uriString: String, packageName: String): Boolean {
        if (uriString.isBlank() || packageName.isBlank()) return false
        val parsed = runCatching { URI(uriString) }.getOrNull() ?: return false
        if (!parsed.scheme.equals("content", ignoreCase = true)) return false

        val authority = parsed.authority.orEmpty()
        if (authority.equals("media", ignoreCase = true)) {
            return true
        }
        if (authority.equals("$packageName.fileprovider", ignoreCase = true)) {
            val path = parsed.path.orEmpty().lowercase()
            if (path.contains("imported") || path.contains("export")) return false
            val firstSegment = path.trimStart('/').substringBefore('/')
            return firstSegment == "saved_docs" || firstSegment == "documents"
        }
        return false
    }

    /**
     * Validates user-entered filename and preserves the existing extension cleanly:
     * - Internal dots and abbreviations (`V.S.K. 24-11-2026`, `Dr. Patil`, `Vol. 1.2`) always retain
     *   the current extension (`V.S.K. 24-11-2026.docx`, `Dr. Patil.docx`, `Vol. 1.2.docx`).
     * - Explicit extension changes to a different known document format (e.g. `.docx` -> `.pdf`)
     *   are rejected cleanly so Rename never corrupts binary formats on disk.
     * - Fails cleanly on empty, dot-only, oversized, illegal-character, or unchanged filenames.
     */
    fun validateAndNormalizeNewFileName(
        rawNewName: String,
        currentFileName: String
    ): Result<String> {
        val trimmed = rawNewName.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("File name cannot be empty."))
        }
        if (trimmed == "." || trimmed == ".." || trimmed.startsWith('.')) {
            return Result.failure(IllegalArgumentException("Please enter a valid file name."))
        }
        if (ILLEGAL_FILENAME_CHARS.containsMatchIn(trimmed)) {
            return Result.failure(
                IllegalArgumentException("File name cannot contain / \\ : * ? \" < > |")
            )
        }

        val currentTrimmed = currentFileName.trim()
        val rawExistingExt = if (currentTrimmed.contains('.')) {
            currentTrimmed.substringAfterLast('.').trim()
        } else {
            ""
        }
        val existingExt = if (
            rawExistingExt.isNotEmpty() &&
            rawExistingExt.length <= 10 &&
            rawExistingExt.all { it.isLetterOrDigit() }
        ) {
            rawExistingExt
        } else {
            ""
        }

        val finalName = if (existingExt.isNotEmpty()) {
            if (trimmed.endsWith(".$existingExt", ignoreCase = true)) {
                val basePart = trimmed.dropLast(existingExt.length + 1).trimEnd('.', ' ')
                if (basePart.isEmpty()) {
                    return Result.failure(IllegalArgumentException("Please enter a valid file name."))
                }
                "$basePart.$existingExt"
            } else {
                val trailingSegment = if (trimmed.contains('.')) {
                    trimmed.substringAfterLast('.').lowercase()
                } else {
                    ""
                }
                if (
                    trailingSegment.isNotEmpty() &&
                    trailingSegment in KNOWN_DOCUMENT_EXTENSIONS &&
                    trailingSegment != existingExt.lowercase()
                ) {
                    return Result.failure(
                        IllegalArgumentException(
                            "Use Save As to change file format; Rename keeps the .$existingExt format."
                        )
                    )
                }
                val cleanBase = trimmed.trimEnd('.', ' ')
                if (cleanBase.isEmpty()) {
                    return Result.failure(IllegalArgumentException("Please enter a valid file name."))
                }
                "$cleanBase.$existingExt"
            }
        } else {
            val cleanBase = trimmed.trimEnd('.', ' ')
            if (cleanBase.isEmpty()) {
                return Result.failure(IllegalArgumentException("Please enter a valid file name."))
            }
            cleanBase
        }

        if (finalName.length > MAX_FILENAME_LENGTH) {
            return Result.failure(
                IllegalArgumentException("File name is too long (max $MAX_FILENAME_LENGTH characters).")
            )
        }
        if (finalName == currentTrimmed) {
            return Result.failure(
                IllegalArgumentException("The new name is the same as the current name.")
            )
        }
        return Result.success(finalName)
    }

    /**
     * Helper to execute physical rename + transactional DB commit with physical rollback on DB failure.
     */
    inline fun <T> executeRenameWithRollback(
        performPhysicalRename: () -> T,
        commitDatabaseTransaction: (T) -> Unit,
        rollbackPhysicalRename: (T) -> Boolean
    ): Result<T> {
        val renamed = runCatching { performPhysicalRename() }
            .getOrElse { return Result.failure(it) }

        return try {
            commitDatabaseTransaction(renamed)
            Result.success(renamed)
        } catch (dbErr: Throwable) {
            val rolledBack = runCatching { rollbackPhysicalRename(renamed) }.getOrDefault(false)
            if (rolledBack) {
                Result.failure(
                    IllegalStateException(
                        "Could not update Kadata library; original file name was restored.",
                        dbErr
                    )
                )
            } else {
                Result.failure(
                    IllegalStateException(
                        "File was renamed on disk, but updating Kadata failed and rollback could not complete: ${dbErr.localizedMessage ?: "database error"}",
                        dbErr
                    )
                )
            }
        }
    }
}
