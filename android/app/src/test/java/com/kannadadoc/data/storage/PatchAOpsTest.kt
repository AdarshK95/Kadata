package com.kannadadoc.data.storage

import com.kannadadoc.parser.DocFormat
import com.kannadadoc.ui.common.WellnessChimePlayer
import kotlin.math.abs
import com.kannadadoc.ui.editor.TargetFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchAOpsTest {

    private val pkg = "com.kannadadoc"

    @Test
    fun canRenameIsTrueOnlyForKadataOwnedStorageUris() {
        // Kadata-owned MediaStore & internal saved_docs fallback -> TRUE
        assertTrue(FileRenamePolicy.isKadataOwnedRenamableUri("content://media/external_primary/file/1042", pkg))
        assertTrue(FileRenamePolicy.isKadataOwnedRenamableUri("content://com.kannadadoc.fileprovider/saved_docs/Letter.docx", pkg))
        assertTrue(FileRenamePolicy.isKadataOwnedRenamableUri("content://com.kannadadoc.fileprovider/documents/Letter.docx", pkg))

        // Transient imports & share cache inside FileProvider -> FALSE
        assertFalse(FileRenamePolicy.isKadataOwnedRenamableUri("content://com.kannadadoc.fileprovider/imported_docs/abc_wa.docx", pkg))
        assertFalse(FileRenamePolicy.isKadataOwnedRenamableUri("content://com.kannadadoc.fileprovider/shared_exports/tmp.docx", pkg))

        // External SAF / SD Card / USB / Downloads -> FALSE (gated until SD/USB reboot test)
        assertFalse(FileRenamePolicy.isKadataOwnedRenamableUri("content://com.android.externalstorage.documents/document/primary%3ADocuments%2FReport.docx", pkg))
        assertFalse(FileRenamePolicy.isKadataOwnedRenamableUri("content://com.android.externalstorage.documents/document/E6D4-AB29%3AVSK.doc", pkg))
        assertFalse(FileRenamePolicy.isKadataOwnedRenamableUri("content://com.android.providers.downloads.documents/document/msf%3A44", pkg))

        // Third-party read-only services (WhatsApp, Gmail, Drive) & blank/file URIs -> FALSE
        assertFalse(FileRenamePolicy.isKadataOwnedRenamableUri("content://com.whatsapp.provider.media/item/1234", pkg))
        assertFalse(FileRenamePolicy.isKadataOwnedRenamableUri("content://com.google.android.gm.sapi/acc/message/1", pkg))
        assertFalse(FileRenamePolicy.isKadataOwnedRenamableUri("content://com.google.android.apps.docs.storage/document/acc%3D1", pkg))
        assertFalse(FileRenamePolicy.isKadataOwnedRenamableUri("", pkg))
    }

    @Test
    fun validatesAndNormalizesNewFileNamesPreservingExtension() {
        // Preserves extension if omitted
        val kept = FileRenamePolicy.validateAndNormalizeNewFileName("  Annual Report  ", "OldName.docx")
        assertTrue(kept.isSuccess)
        assertEquals("Annual Report.docx", kept.getOrThrow())

        // Allows Kannada Unicode names
        val kannada = FileRenamePolicy.validateAndNormalizeNewFileName("ವಚನ ಸಾಹಿತ್ಯ", "Draft.docx")
        assertTrue(kannada.isSuccess)
        assertEquals("ವಚನ ಸಾಹಿತ್ಯ.docx", kannada.getOrThrow())

        // Preserves extension when user explicitly includes matching extension (case-insensitive)
        val sameExtUpper = FileRenamePolicy.validateAndNormalizeNewFileName("Annual Report.DOCX", "OldName.docx")
        assertTrue(sameExtUpper.isSuccess)
        assertEquals("Annual Report.docx", sameExtUpper.getOrThrow())

        // Rejects empty, dot-only, illegal chars, and unchanged names
        assertTrue(FileRenamePolicy.validateAndNormalizeNewFileName("   ", "A.docx").isFailure)
        assertTrue(FileRenamePolicy.validateAndNormalizeNewFileName("..", "A.docx").isFailure)
        assertTrue(FileRenamePolicy.validateAndNormalizeNewFileName(".hidden", "A.docx").isFailure)
        assertTrue(FileRenamePolicy.validateAndNormalizeNewFileName("bad/name", "A.docx").isFailure)
        assertTrue(FileRenamePolicy.validateAndNormalizeNewFileName("bad:name", "A.docx").isFailure)
        assertTrue(FileRenamePolicy.validateAndNormalizeNewFileName("Same.docx", "Same.docx").isFailure)
        assertTrue(FileRenamePolicy.validateAndNormalizeNewFileName("Same", "Same.docx").isFailure)
    }

    @Test
    fun internalDotsAndAbbreviationsPreserveExtensionWithoutStripping() {
        // Real-world initials, honorifics, and decimal/volume numbers must keep .docx
        val initials = FileRenamePolicy.validateAndNormalizeNewFileName("V.S.K. 24-11-2026", "V.S.K. 23-11-2026.docx")
        assertTrue(initials.isSuccess)
        assertEquals("V.S.K. 24-11-2026.docx", initials.getOrThrow())

        val honorific = FileRenamePolicy.validateAndNormalizeNewFileName("Dr. Patil", "Document.docx")
        assertTrue(honorific.isSuccess)
        assertEquals("Dr. Patil.docx", honorific.getOrThrow())

        val versioned = FileRenamePolicy.validateAndNormalizeNewFileName("Vol. 1.2", "Notes.docx")
        assertTrue(versioned.isSuccess)
        assertEquals("Vol. 1.2.docx", versioned.getOrThrow())

        val parensVol = FileRenamePolicy.validateAndNormalizeNewFileName("Bhojraj Patil (Vol. 1)", "Document.docx")
        assertTrue(parensVol.isSuccess)
        assertEquals("Bhojraj Patil (Vol. 1).docx", parensVol.getOrThrow())

        val trailingDot = FileRenamePolicy.validateAndNormalizeNewFileName("V.S.K.", "Document.docx")
        assertTrue(trailingDot.isSuccess)
        assertEquals("V.S.K.docx", trailingDot.getOrThrow())

        val withExplicitExt = FileRenamePolicy.validateAndNormalizeNewFileName("V.S.K. 24-11-2026.docx", "Old.docx")
        assertTrue(withExplicitExt.isSuccess)
        assertEquals("V.S.K. 24-11-2026.docx", withExplicitExt.getOrThrow())
    }

    @Test
    fun rejectsFormatSwappingExtensionsDuringRename() {
        val toPdf = FileRenamePolicy.validateAndNormalizeNewFileName("Report.pdf", "Report.docx")
        assertTrue(toPdf.isFailure)
        assertTrue(toPdf.exceptionOrNull()?.message?.contains("Use Save As to change file format") == true)

        val toTxt = FileRenamePolicy.validateAndNormalizeNewFileName("Notes.txt", "Old.docx")
        assertTrue(toTxt.isFailure)
        assertTrue(toTxt.exceptionOrNull()?.message?.contains(".docx") == true)

        val docToDocx = FileRenamePolicy.validateAndNormalizeNewFileName("Letter.docx", "Letter.doc")
        assertTrue(docToDocx.isFailure)
        assertTrue(docToDocx.exceptionOrNull()?.message?.contains(".doc") == true)
    }

    @Test
    fun executeRenameWithRollbackRestoresPhysicalFileWhenDbCommitFails() {
        var diskName = "Before.docx"
        var rollbackCalled = false

        val failed = FileRenamePolicy.executeRenameWithRollback(
            performPhysicalRename = {
                diskName = "After.docx"
                "After.docx"
            },
            commitDatabaseTransaction = {
                throw RuntimeException("Simulated Room transaction failure")
            },
            rollbackPhysicalRename = {
                rollbackCalled = true
                diskName = "Before.docx"
                true
            }
        )

        assertTrue(failed.isFailure)
        assertTrue(rollbackCalled)
        assertEquals("Before.docx", diskName)
        assertTrue(failed.exceptionOrNull()?.message?.contains("original file name was restored") == true)
    }

    @Test
    fun executeRenameDoesNotTouchDbWhenPhysicalRenameFails() {
        var dbCommitCalled = false

        val failed = FileRenamePolicy.executeRenameWithRollback(
            performPhysicalRename = {
                throw IllegalStateException("A file named \"Target.docx\" already exists")
            },
            commitDatabaseTransaction = {
                dbCommitCalled = true
            },
            rollbackPhysicalRename = { true }
        )

        assertTrue(failed.isFailure)
        assertFalse(dbCommitCalled)
    }

    @Test
    fun docExportLabelAndBadgeAreHonest() {
        assertEquals("Word document (older format, .doc)", TargetFileType.DOC.label)
        assertEquals("DOC", DocFormat.DOC.badgeLabel)
    }

    @Test
    fun resolvesImportSourceLabelsWithoutOpaqueOffline() {
        assertEquals("WhatsApp", ImportSourcePolicy.detectKnownServiceLabel("com.whatsapp.provider.media"))
        assertEquals("Gmail", ImportSourcePolicy.detectKnownServiceLabel("com.google.android.gm.sapi"))
        assertEquals("Telegram", ImportSourcePolicy.detectKnownServiceLabel("org.telegram.messenger.provider"))
        assertEquals("Google Drive", ImportSourcePolicy.detectKnownServiceLabel("com.google.android.apps.docs.storage"))
        assertEquals("Outlook", ImportSourcePolicy.detectKnownServiceLabel("com.microsoft.office.outlook.fileprovider"))

        // Card origin shows WhatsApp / Gmail directly instead of "Offline"
        assertEquals(
            "WhatsApp",
            ImportSourcePolicy.resolveCardOriginLabel(
                sourceAppLabel = "WhatsApp",
                uriString = "content://com.kannadadoc.fileprovider/imported_docs/abc123456789_DOC-20261123-WA0029.docx",
                displayName = "DOC-20261123-WA0029.docx",
                isEdited = false,
                isLocallyImported = true,
                knOnly = false
            )
        )
        assertEquals(
            "Gmail",
            ImportSourcePolicy.resolveCardOriginLabel(
                sourceAppLabel = "Gmail",
                uriString = "content://com.kannadadoc.fileprovider/imported_docs/abc123456789_Report.pdf",
                displayName = "Report.pdf",
                isEdited = false,
                isLocallyImported = true,
                knOnly = false
            )
        )
        // Legacy pre-Patch-A.2 WhatsApp row with null sourceAppLabel still infers WhatsApp
        assertEquals(
            "WhatsApp",
            ImportSourcePolicy.resolveCardOriginLabel(
                sourceAppLabel = null,
                uriString = "content://com.kannadadoc.fileprovider/imported_docs/089f73c11d96_DOC-20261122-WA0002.pdf",
                displayName = "DOC-20261122-WA0002.pdf",
                isEdited = false,
                isLocallyImported = true,
                knOnly = false
            )
        )
        // Local storage routes resolve honestly
        assertEquals(
            "SD / USB",
            ImportSourcePolicy.resolveCardOriginLabel(
                sourceAppLabel = null,
                uriString = "content://com.android.externalstorage.documents/document/E6D4-AB29%3AVSK.doc",
                displayName = "VSK.doc",
                isEdited = false,
                isLocallyImported = false,
                knOnly = false
            )
        )
        assertEquals(
            "Downloads",
            ImportSourcePolicy.resolveCardOriginLabel(
                sourceAppLabel = null,
                uriString = "content://com.android.providers.downloads.documents/document/msf%3A36770",
                displayName = "Notes.pdf",
                isEdited = false,
                isLocallyImported = false,
                knOnly = false
            )
        )
    }

    @Test
    fun whatsappIncomingFileNamesArePreservedAndContentDeduped() {
        assertTrue(ImportSourcePolicy.shouldPreserveIncomingFileName("WhatsApp", "DOC-20261123-WA0029.docx"))
        assertTrue(ImportSourcePolicy.shouldPreserveIncomingFileName(null, "DOC-20261123-WA0029.docx"))
        assertTrue(ImportSourcePolicy.shouldPreserveIncomingFileName(null, "1e66f85f49d0_DOC-20261123-WA0029.docx"))
        assertFalse(ImportSourcePolicy.shouldPreserveIncomingFileName("Gmail", "Untitled.docx"))

        // Same content SHA-256 produces identical deterministic filename regardless of ephemeral URI
        val f1 = ImportSourcePolicy.buildContentDedupedImportFileName(
            "fdd58a61ff9aa29b00112233445566778899aabbccddeeff0011223344556677",
            "DOC-20261123-WA0029.docx"
        )
        val f2 = ImportSourcePolicy.buildContentDedupedImportFileName(
            "FDD58A61FF9AA29B00112233445566778899AABBCCDDEEFF0011223344556677",
            "DOC-20261123-WA0029.docx"
        )
        assertEquals("fdd58a61ff9a_DOC-20261123-WA0029.docx", f1)
        assertEquals(f1, f2)
    }

    @Test
    fun wellnessChimeSynthesizesGentleNonClippingSmoothAudio() {
        val pcm = WellnessChimePlayer.synthesizeSoothingChimePcm()
        assertTrue("Expected ~1.75s of audio samples", pcm.size in 35_000..42_000)

        // Smooth fade-in and fade-out (zero start/end clicks)
        assertEquals(0, pcm.first().toInt())
        assertTrue("Final sample should fade smoothly near zero", abs(pcm.last().toInt()) < 50)

        // Audible yet subtle & non-clipping (peak < 50% of full scale)
        val peak = pcm.maxOf { abs(it.toInt()) }
        assertTrue("Chime must be audible (peak=$peak)", peak > 1_500)
        assertTrue("Chime must stay subtle and never clip (peak=$peak)", peak < 16_000)
    }
}
