package com.kannadadoc.data.storage

import java.net.URI
import java.net.URLDecoder

/**
 * Pure Kotlin policy for:
 * - Identifying human-readable import sources (WhatsApp, Gmail, Telegram, Google Drive, etc.)
 * - Resolving the card origin/status badge on Home (never showing generic "Offline" when source is known)
 * - Preserving incoming WhatsApp filenames (never replacing DOC-YYYYMMDD-WAxxxx with body text)
 * - Generating deterministic content-SHA256 filenames so re-importing the same file via ephemeral
 *   WhatsApp/Gmail URIs never creates duplicate copies or duplicate cards.
 */
object ImportSourcePolicy {

    private val INTERNAL_HASH_PREFIX_REGEX = Regex("^[0-9a-f]{12}_", RegexOption.IGNORE_CASE)
    private val WHATSAPP_DOC_NAME_REGEX = Regex(
        "^(DOC|AUD|IMG|VID|PTT)-\\d{8}-WA\\d+.*$",
        RegexOption.IGNORE_CASE
    )

    fun detectKnownServiceLabel(authority: String): String? {
        val lower = authority.lowercase()
        return when {
            lower.contains("whatsapp") -> "WhatsApp"
            (lower.contains("gm") && lower.contains("google")) || lower.contains("gmail") -> "Gmail"
            lower.contains("telegram") -> "Telegram"
            lower.contains("google.android.apps.docs") -> "Google Drive"
            lower.contains("outlook") || lower.contains("microsoft.office.outlook") -> "Outlook"
            lower.contains("teams") -> "Teams"
            lower.contains("skydrive") || lower.contains("onedrive") -> "OneDrive"
            lower.contains("dropbox") -> "Dropbox"
            lower.contains("slack") -> "Slack"
            lower.contains("chrome") -> "Chrome"
            lower.contains("email") || lower.contains("mail") -> "Email"
            lower.contains("bluetooth") -> "Bluetooth"
            lower.contains("quickshare") || lower.contains("nearby") -> "Quick Share"
            else -> null
        }
    }

    /**
     * Returns true when Kadata must preserve the incoming filename as-is instead of running
     * DocumentTitleHealer (which extracts body text as a synthetic title).
     */
    fun shouldPreserveIncomingFileName(sourceAppLabel: String?, displayName: String): Boolean {
        if (sourceAppLabel.equals("WhatsApp", ignoreCase = true)) return true
        val stripped = displayName.trim().replace(INTERNAL_HASH_PREFIX_REGEX, "")
        val baseNoExt = if (stripped.contains('.')) stripped.substringBeforeLast('.') else stripped
        return WHATSAPP_DOC_NAME_REGEX.matches(baseNoExt.trim())
    }

    /**
     * Builds a deterministic filename inside `filesDir/imported/` keyed on the file's content
     * SHA-256 hash (first 12 hex chars) rather than the ephemeral incoming content:// URI.
     */
    fun buildContentDedupedImportFileName(
        contentSha256Hex: String,
        sanitizedDisplayName: String
    ): String {
        val prefix = contentSha256Hex.trim().lowercase().take(12).ifBlank { "000000000000" }
        val safeName = sanitizedDisplayName.trim().ifBlank { "Document" }
        return "${prefix}_$safeName"
    }

    /**
     * Resolves the clear origin/status label shown on Home cards and Details rows.
     * Always shows the actual import app (WhatsApp, Gmail, Telegram, Google Drive, etc.)
     * instead of opaque "Offline".
     */
    fun resolveCardOriginLabel(
        sourceAppLabel: String?,
        uriString: String,
        displayName: String,
        isEdited: Boolean,
        isLocallyImported: Boolean,
        knOnly: Boolean
    ): String? {
        val explicitSource = sourceAppLabel?.trim()?.takeIf { it.isNotBlank() }
        if (explicitSource != null) {
            return explicitSource
        }

        // Fallback inference for pre-Patch-A.2 imported rows where sourceAppLabel was null
        if (isLocallyImported) {
            val pathName = runCatching { URI(uriString).path.orEmpty().substringAfterLast('/') }.getOrDefault("")
            if (shouldPreserveIncomingFileName(null, displayName) || shouldPreserveIncomingFileName(null, pathName)) {
                return "WhatsApp"
            }
        }

        if (isEdited) {
            return if (knOnly) "ತಿದ್ದುಪಡಿ" else "Edited"
        }

        val parsed = runCatching { URI(uriString) }.getOrNull()
        val authority = parsed?.authority.orEmpty().lowercase()

        if (authority == "com.android.providers.downloads.documents") {
            return if (knOnly) "ಡೌನ್‌ಲೋಡ್" else "Downloads"
        }

        if (authority == "com.android.externalstorage.documents") {
            val decodedPath = runCatching {
                URLDecoder.decode(parsed?.rawPath.orEmpty(), "UTF-8")
            }.getOrDefault(parsed?.path.orEmpty())
            val docId = decodedPath.substringAfter("/document/", "")
            return if (docId.isNotEmpty() && !docId.startsWith("primary:", ignoreCase = true)) {
                "SD / USB"
            } else {
                if (knOnly) "ಸಂಗ್ರಹಣೆ" else "Device"
            }
        }

        if (authority == "media") {
            return "Kadata"
        }

        if (isLocallyImported) {
            return if (knOnly) "ಆಮದು" else "Imported"
        }

        return null
    }
}
