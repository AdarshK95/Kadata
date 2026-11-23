package com.kannadadoc.data.storage

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object RecoveryVaultPolicy {

    const val MAX_RECENT_24H_PER_DOC = 5
    const val MAX_DAILY_AGE_DAYS = 14
    const val ONE_DAY_MS = 24L * 60L * 60L * 1000L
    const val FOURTEEN_DAYS_MS = MAX_DAILY_AGE_DAYS * ONE_DAY_MS
    const val ORPHAN_GRACE_TTL_MS = 3L * ONE_DAY_MS
    const val MAX_GLOBAL_VAULT_BYTES = 50L * 1024L * 1024L

    data class RevisionRecord(
        val id: Long,
        val docKey: String,
        val createdAt: Long,
        val charDelta: Int,
        val fileSizeBytes: Long,
        val isSessionBaseline: Boolean,
        val orphanedAt: Long?
    )

    fun computeGlobalQuotaBytes(freeStorageBytes: Long): Long {
        if (freeStorageBytes <= 0L) return 2L * 1024L * 1024L
        val onePercent = (freeStorageBytes / 100L).coerceAtLeast(1L * 1024L * 1024L)
        return minOf(MAX_GLOBAL_VAULT_BYTES, onePercent)
    }

    fun selectPerDocumentEvictions(
        revisions: List<RevisionRecord>,
        nowMs: Long,
        activeSessionBaselineId: Long? = null
    ): Set<Long> {
        if (revisions.isEmpty()) return emptySet()

        val evict = linkedSetOf<Long>()
        val sortedDesc = revisions.sortedByDescending { it.createdAt }
        val newestId = sortedDesc.first().id
        val isDocOrphaned = sortedDesc.all { it.orphanedAt != null }

        val surviving = mutableListOf<RevisionRecord>()
        for (rev in sortedDesc) {
            val orph = rev.orphanedAt
            if (orph != null && (nowMs - orph) >= ORPHAN_GRACE_TTL_MS) {
                evict.add(rev.id)
            } else {
                surviving.add(rev)
            }
        }
        if (surviving.isEmpty()) return evict

        val cutoff24h = nowMs - ONE_DAY_MS
        val cutoff14d = nowMs - FOURTEEN_DAYS_MS

        val recent24h = surviving.filter { it.createdAt >= cutoff24h }.toMutableList()
        val midAge = surviving.filter { it.createdAt < cutoff24h && it.createdAt >= cutoff14d }
        val expired = surviving.filter { it.createdAt < cutoff14d }

        while (recent24h.size > MAX_RECENT_24H_PER_DOC) {
            val newestInWindow = recent24h.maxByOrNull { it.createdAt }?.id
            val candidates = recent24h.filter { it.id != newestInWindow && it.id != activeSessionBaselineId }
            val victim = (candidates.ifEmpty { recent24h.filter { it.id != newestInWindow } })
                .minWithOrNull(compareBy<RevisionRecord> { abs(it.charDelta) }.thenBy { it.createdAt })
                ?: break
            evict.add(victim.id)
            recent24h.remove(victim)
        }

        val byDay = midAge.groupBy { it.createdAt / ONE_DAY_MS }
        for ((_, dayList) in byDay) {
            if (dayList.size <= 1) continue
            val keeper = dayList.maxWithOrNull(
                compareBy<RevisionRecord> { abs(it.charDelta) }.thenBy { it.createdAt }
            )
            for (rev in dayList) {
                if (rev.id != keeper?.id) evict.add(rev.id)
            }
        }

        val hasNewerSurviving = surviving.any { it.id !in evict && it.createdAt >= cutoff14d }
        for (rev in expired) {
            if (!isDocOrphaned && !hasNewerSurviving && rev.id == newestId) continue
            evict.add(rev.id)
        }

        return evict
    }

    fun selectGlobalQuotaEvictions(
        allRevisions: List<RevisionRecord>,
        quotaBytes: Long
    ): Set<Long> {
        var totalBytes = allRevisions.sumOf { it.fileSizeBytes.coerceAtLeast(0L) }
        if (totalBytes <= quotaBytes) return emptySet()

        val evict = linkedSetOf<Long>()
        val latestByDoc = allRevisions.groupBy { it.docKey }
            .mapValues { (_, list) -> list.maxByOrNull { it.createdAt }?.id }

        val pass1 = allRevisions
            .filter { latestByDoc[it.docKey] != it.id }
            .sortedWith(compareByDescending<RevisionRecord> { it.orphanedAt != null }.thenBy { it.createdAt })

        for (rev in pass1) {
            if (totalBytes <= quotaBytes) return evict
            evict.add(rev.id)
            totalBytes -= rev.fileSizeBytes.coerceAtLeast(0L)
        }

        val pass2 = allRevisions
            .filter { it.id !in evict }
            .sortedWith(compareByDescending<RevisionRecord> { it.orphanedAt != null }.thenBy { it.createdAt })

        for (rev in pass2) {
            if (totalBytes <= quotaBytes) return evict
            evict.add(rev.id)
            totalBytes -= rev.fileSizeBytes.coerceAtLeast(0L)
        }
        return evict
    }

    fun generateUniqueVersionFileName(
        originalTitle: String,
        versionTimestampMs: Long,
        existingNames: Set<String>
    ): String {
        val rawBase = originalTitle.substringBeforeLast('.').ifBlank { "Document" }
        val cleanBase = rawBase
            .replace(Regex("(?:_(?:Nudi|Baraha|Unicode|Universal))+$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[^a-zA-Z0-9._\\-\\u0C80-\\u0CFF ]"), "_")
            .trim()
            .ifBlank { "Document" }
        val stamp = SimpleDateFormat("ddMMM_HHmm", Locale.US).format(Date(versionTimestampMs))
        val lowerExisting = existingNames.map { it.lowercase(Locale.US) }.toSet()

        val baseCandidate = "${cleanBase}_v${stamp}.docx"
        if (baseCandidate.lowercase(Locale.US) !in lowerExisting) return baseCandidate
        var counter = 1
        while (counter < 1000) {
            val numbered = "${cleanBase}_v${stamp} ($counter).docx"
            if (numbered.lowercase(Locale.US) !in lowerExisting) return numbered
            counter++
        }
        return "${cleanBase}_v${stamp}_${versionTimestampMs % 10000}.docx"
    }
}
