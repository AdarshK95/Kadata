package com.kannadadoc.data.storage

import com.kannadadoc.parser.DocFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryVaultPolicyTest {

    @Test
    fun recent24hEvictsSmallestDeltaAndProtectsSessionBaselineAndNewest() {
        val now = 10_000_000_000L
        val revs = listOf(
            RecoveryVaultPolicy.RevisionRecord(1, "docA", now - 600_000, 0, 10_000, isSessionBaseline = true, orphanedAt = null),
            RecoveryVaultPolicy.RevisionRecord(2, "docA", now - 500_000, 12, 10_000, false, null),   // smallest delta -> evicted
            RecoveryVaultPolicy.RevisionRecord(3, "docA", now - 400_000, 850, 10_000, false, null),
            RecoveryVaultPolicy.RevisionRecord(4, "docA", now - 300_000, 420, 10_000, false, null),
            RecoveryVaultPolicy.RevisionRecord(5, "docA", now - 200_000, 310, 10_000, false, null),
            RecoveryVaultPolicy.RevisionRecord(6, "docA", now - 100_000, 5, 10_000, false, null)     // newest -> protected!
        )
        val evicted = RecoveryVaultPolicy.selectPerDocumentEvictions(revs, now, activeSessionBaselineId = 1L)
        assertEquals(setOf(2L), evicted)
    }

    @Test
    fun compactsMidAgeToOnePerDayAndPurgesOlderThan14Days() {
        val now = 30L * RecoveryVaultPolicy.ONE_DAY_MS
        val day3Base = now - 3L * RecoveryVaultPolicy.ONE_DAY_MS
        val revs = listOf(
            RecoveryVaultPolicy.RevisionRecord(10, "docA", now - 2_000, 50, 5_000, false, null),          // active newest
            RecoveryVaultPolicy.RevisionRecord(11, "docA", day3Base + 1_000, 20, 5_000, false, null),     // day 3 small -> evicted
            RecoveryVaultPolicy.RevisionRecord(12, "docA", day3Base + 2_000, 900, 5_000, false, null),    // day 3 big -> kept
            RecoveryVaultPolicy.RevisionRecord(13, "docA", now - 20L * RecoveryVaultPolicy.ONE_DAY_MS, 500, 5_000, false, null) // >14d -> evicted
        )
        val evicted = RecoveryVaultPolicy.selectPerDocumentEvictions(revs, now)
        assertTrue(11L in evicted)
        assertTrue(13L in evicted)
        assertFalse(10L in evicted)
        assertFalse(12L in evicted)
    }

    @Test
    fun deletedFileOrphansSurviveUnder72hAndPurgeAfter72h() {
        val now = 20L * RecoveryVaultPolicy.ONE_DAY_MS
        val recentOrphan = RecoveryVaultPolicy.RevisionRecord(
            1, "docDel", now - 4_000, 100, 5_000, false, orphanedAt = now - 2L * RecoveryVaultPolicy.ONE_DAY_MS
        )
        val expiredOrphan = RecoveryVaultPolicy.RevisionRecord(
            2, "docOld", now - 4_000, 100, 5_000, false, orphanedAt = now - 4L * RecoveryVaultPolicy.ONE_DAY_MS
        )
        assertTrue(RecoveryVaultPolicy.selectPerDocumentEvictions(listOf(recentOrphan), now).isEmpty())
        assertEquals(setOf(2L), RecoveryVaultPolicy.selectPerDocumentEvictions(listOf(expiredOrphan), now))
    }

    @Test
    fun globalQuotaEnforcesOnePercentFreeStorageAndEvictsNonLatestFirst() {
        assertEquals(4L * 1024L * 1024L, RecoveryVaultPolicy.computeGlobalQuotaBytes(400L * 1024L * 1024L))
        assertEquals(50L * 1024L * 1024L, RecoveryVaultPolicy.computeGlobalQuotaBytes(20L * 1024L * 1024L * 1024L))

        val all = listOf(
            RecoveryVaultPolicy.RevisionRecord(1, "docA", 1000, 50, 600_000, false, null),
            RecoveryVaultPolicy.RevisionRecord(2, "docA", 2000, 50, 600_000, false, null), // latest for docA
            RecoveryVaultPolicy.RevisionRecord(3, "docB", 1500, 50, 600_000, false, null)  // latest for docB
        )
        val evicted = RecoveryVaultPolicy.selectGlobalQuotaEvictions(all, quotaBytes = 1_200_000)
        assertEquals(setOf(1L), evicted)
    }

    @Test
    fun generatesCollisionFreeVersionNamesAndDocDisplayFormat() {
        val ts = 1_795_360_000_000L
        val first = RecoveryVaultPolicy.generateUniqueVersionFileName("V.S.K. 19-11-2026_Nudi.docx", ts, emptySet())
        assertFalse(first.contains("_Nudi"))
        assertTrue(first.endsWith(".docx"))
        val second = RecoveryVaultPolicy.generateUniqueVersionFileName("V.S.K. 19-11-2026_Nudi.docx", ts, setOf(first))
        assertTrue(second.contains("(1).docx"))

        assertEquals(DocFormat.DOC, DocFormat.displayFormatFor("VSK 22 Nov 26.doc", DocFormat.RTF))
        assertEquals(DocFormat.RTF, DocFormat.displayFormatFor("Notes.rtf", DocFormat.RTF))
    }
}
