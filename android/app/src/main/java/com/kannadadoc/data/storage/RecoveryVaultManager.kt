package com.kannadadoc.data.storage

import android.content.Context
import android.os.StatFs
import com.kannadadoc.converter.KannadaTextConverter
import com.kannadadoc.data.local.RevisionDao
import com.kannadadoc.data.local.RevisionEntity
import com.kannadadoc.parser.DocBlock
import com.kannadadoc.parser.DocumentJson
import com.kannadadoc.parser.ParsedDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class CrashRecoveryCandidate(
    val uriString: String,
    val documentTitle: String,
    val checkpointPath: String,
    val charDelta: Int,
    val updatedAt: Long,
    val sourceModifiedAt: Long
)

class RecoveryVaultManager(
    private val context: Context,
    private val revisionDao: RevisionDao
) {
    private val vaultRoot: File get() = File(context.filesDir, "recovery_vault").apply { mkdirs() }
    private val checkpointsDir: File get() = File(vaultRoot, "checkpoints").apply { mkdirs() }
    private val sessionMarkerFile: File get() = File(vaultRoot, "active_session.json")
    private val vaultMutex = Mutex()

    fun docKeyFor(uriString: String): String = sha256Hex(uriString).take(16)

    fun availableInternalBytes(): Long = runCatching {
        StatFs(context.filesDir.absolutePath).availableBytes
    }.getOrDefault(128L * 1024L * 1024L)

    suspend fun writeActiveCheckpoint(
        uriString: String,
        documentTitle: String,
        idmJson: String,
        charDelta: Int,
        sourceModifiedAt: Long
    ): File = withContext(Dispatchers.IO + NonCancellable) {
        vaultMutex.withLock {
            val docKey = docKeyFor(uriString)
            val target = File(checkpointsDir, "${docKey}_active.json.gz")
            writeGzipAtomic(target, idmJson.toByteArray(Charsets.UTF_8))

            val marker = JSONObject().apply {
                put("state", "DIRTY")
                put("uriString", uriString)
                put("documentTitle", documentTitle)
                put("checkpointPath", target.absolutePath)
                put("charDelta", charDelta)
                put("updatedAt", System.currentTimeMillis())
                put("sourceModifiedAt", sourceModifiedAt)
            }
            writeBytesAtomic(sessionMarkerFile, marker.toString().toByteArray(Charsets.UTF_8))
            target
        }
    }

    suspend fun markSessionClean(uriString: String) = withContext(Dispatchers.IO + NonCancellable) {
        vaultMutex.withLock {
            val docKey = docKeyFor(uriString)
            runCatching { File(checkpointsDir, "${docKey}_active.json.gz").delete() }
            if (sessionMarkerFile.exists()) {
                val marker = runCatching { JSONObject(sessionMarkerFile.readText(Charsets.UTF_8)) }.getOrNull()
                if (marker == null || marker.optString("uriString") == uriString) {
                    val clean = JSONObject().apply {
                        put("state", "CLEAN")
                        put("uriString", uriString)
                        put("updatedAt", System.currentTimeMillis())
                    }
                    runCatching { writeBytesAtomic(sessionMarkerFile, clean.toString().toByteArray(Charsets.UTF_8)) }
                }
            }
        }
    }

    suspend fun loadActiveCheckpointIfNewer(uriString: String, minTimestampMs: Long): ParsedDocument? =
        withContext(Dispatchers.IO) {
            val docKey = docKeyFor(uriString)
            val file = File(checkpointsDir, "${docKey}_active.json.gz")
            if (!file.exists() || file.length() <= 0L) return@withContext null
            if (file.lastModified() <= minTimestampMs) return@withContext null
            readGzipDocument(file)
        }

    suspend fun detectPendingCrashRecovery(): CrashRecoveryCandidate? = withContext(Dispatchers.IO) {
        if (!sessionMarkerFile.exists()) return@withContext null
        val json = runCatching { JSONObject(sessionMarkerFile.readText(Charsets.UTF_8)) }.getOrNull() ?: return@withContext null
        if (json.optString("state") != "DIRTY") return@withContext null
        val uriString = json.optString("uriString")
        val path = json.optString("checkpointPath")
        if (uriString.isBlank() || path.isBlank()) return@withContext null
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return@withContext null
        CrashRecoveryCandidate(
            uriString = uriString,
            documentTitle = json.optString("documentTitle", "Document.docx"),
            checkpointPath = path,
            charDelta = json.optInt("charDelta", 0),
            updatedAt = json.optLong("updatedAt", file.lastModified()),
            sourceModifiedAt = json.optLong("sourceModifiedAt", 0L)
        )
    }

    suspend fun clearCrashRecoveryMarker() = withContext(Dispatchers.IO + NonCancellable) {
        vaultMutex.withLock {
            runCatching {
                if (sessionMarkerFile.exists()) {
                    val json = runCatching { JSONObject(sessionMarkerFile.readText(Charsets.UTF_8)) }.getOrNull()
                    val path = json?.optString("checkpointPath")
                    if (!path.isNullOrBlank()) runCatching { File(path).delete() }
                    sessionMarkerFile.delete()
                }
            }
        }
    }

    suspend fun loadDocumentFromCheckpointPath(path: String): ParsedDocument? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return@withContext null
        readGzipDocument(file)
    }

    suspend fun recordVersionSnapshot(
        uriString: String,
        documentTitle: String,
        idmJson: String,
        charCount: Int,
        charDelta: Int,
        triggerReason: String,
        isSessionBaseline: Boolean = false,
        activeSessionBaselineId: Long? = null
    ): RevisionEntity? = withContext(Dispatchers.IO + NonCancellable) {
        if (uriString.isBlank() || idmJson.isBlank()) return@withContext null
        vaultMutex.withLock {
            val docKey = docKeyFor(uriString)
            val bytes = idmJson.toByteArray(Charsets.UTF_8)
            val contentHash = sha256Hex(bytes)

            val latest = revisionDao.getLatestForDocKey(docKey)
            if (latest != null && latest.contentSha256 == contentHash && File(latest.snapshotFilePath).exists()) {
                return@withLock latest
            }

            val freeBytes = availableInternalBytes()
            if (freeBytes < 5L * 1024L * 1024L) {
                enforceGlobalQuotaLocked(freeBytes)
            }

            val now = System.currentTimeMillis()
            val docDir = File(vaultRoot, docKey).apply { mkdirs() }
            val outFile = File(docDir, "rev_${now}_${contentHash.take(6)}.json.gz")
            writeGzipAtomic(outFile, bytes)

            val insertedId = revisionDao.insert(
                RevisionEntity(
                    docKey = docKey,
                    uriString = uriString,
                    documentTitle = documentTitle,
                    snapshotFilePath = outFile.absolutePath,
                    contentSha256 = contentHash,
                    charCount = charCount,
                    charDelta = charDelta,
                    fileSizeBytes = outFile.length(),
                    triggerReason = triggerReason,
                    isSessionBaseline = isSessionBaseline,
                    createdAt = now,
                    orphanedAt = null
                )
            )
            revisionDao.clearOrphanedByUri(uriString)
            compactDocumentLocked(docKey, now, activeSessionBaselineId ?: if (isSessionBaseline) insertedId else null)
            enforceGlobalQuotaLocked(availableInternalBytes())
            revisionDao.getByDocKey(docKey).firstOrNull { it.id == insertedId }
        }
    }

    suspend fun getVersionsForUri(uriString: String): List<RevisionEntity> = withContext(Dispatchers.IO) {
        if (uriString.isBlank()) return@withContext emptyList()
        revisionDao.getByDocKey(docKeyFor(uriString)).filter { File(it.snapshotFilePath).exists() }
    }

    suspend fun loadRevisionDocument(revision: RevisionEntity): ParsedDocument? = withContext(Dispatchers.IO) {
        val file = File(revision.snapshotFilePath)
        if (!file.exists()) return@withContext null
        readGzipDocument(file)
    }

    suspend fun markDocumentOrphaned(uriString: String) = withContext(Dispatchers.IO + NonCancellable) {
        vaultMutex.withLock {
            revisionDao.markOrphanedByUri(uriString, System.currentTimeMillis())
            val docKey = docKeyFor(uriString)
            runCatching { File(checkpointsDir, "${docKey}_active.json.gz").delete() }
        }
    }

    suspend fun renameDocumentRevisions(uriString: String, newTitle: String) = withContext(Dispatchers.IO) {
        runCatching { revisionDao.updateTitleByUri(uriString, newTitle) }
    }

    suspend fun rebindDocumentUri(oldUri: String, newUri: String) = withContext(Dispatchers.IO + NonCancellable) {
        if (oldUri == newUri || oldUri.isBlank() || newUri.isBlank()) return@withContext
        vaultMutex.withLock { runCatching { revisionDao.rebindUri(oldUri, newUri, docKeyFor(newUri)) } }
    }

    /**
     * Updates the live on-disk AutoRecover checkpoint file and active_session.json marker
     * after a verified physical rename (especially if an internal FileProvider URI changed).
     */
    suspend fun rebindActiveCheckpointFile(
        oldUri: String,
        newUri: String,
        newTitle: String
    ) = withContext(Dispatchers.IO + NonCancellable) {
        if (oldUri.isBlank() || newUri.isBlank()) return@withContext
        vaultMutex.withLock {
            runCatching {
                val oldKey = docKeyFor(oldUri)
                val newKey = docKeyFor(newUri)
                val oldCp = File(checkpointsDir, "${oldKey}_active.json.gz")
                val newCp = File(checkpointsDir, "${newKey}_active.json.gz")
                if (oldKey != newKey && oldCp.exists()) {
                    if (!oldCp.renameTo(newCp)) {
                        oldCp.copyTo(newCp, overwrite = true)
                        oldCp.delete()
                    }
                }
                if (sessionMarkerFile.exists()) {
                    val marker = runCatching {
                        JSONObject(sessionMarkerFile.readText(Charsets.UTF_8))
                    }.getOrNull()
                    if (marker != null && marker.optString("uriString") == oldUri) {
                        marker.put("uriString", newUri)
                        marker.put("documentTitle", newTitle)
                        if (newCp.exists()) {
                            marker.put("checkpointPath", newCp.absolutePath)
                        }
                        writeBytesAtomic(sessionMarkerFile, marker.toString().toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
    }

    suspend fun runLifecycleCleanup(activeUris: Set<String>) = withContext(Dispatchers.IO) {
        vaultMutex.withLock {
            val now = System.currentTimeMillis()
            val all = revisionDao.getAllSnapshot()
            for (u in all.map { it.uriString }.toSet() - activeUris) {
                revisionDao.markOrphanedByUri(u, now)
            }
            val refreshed = revisionDao.getAllSnapshot()
            for ((docKey, _) in refreshed.groupBy { it.docKey }) {
                compactDocumentLocked(docKey, now, null)
            }
            enforceGlobalQuotaLocked(availableInternalBytes())

            val referencedFiles = revisionDao.getAllSnapshot()
                .mapNotNull { runCatching { File(it.snapshotFilePath).canonicalPath }.getOrNull() }
                .toSet()

            vaultRoot.listFiles()?.forEach { child ->
                if (child.name == "checkpoints" || child.name == "active_session.json") return@forEach
                if (child.isDirectory) {
                    child.listFiles()?.forEach { f ->
                        val canon = runCatching { f.canonicalPath }.getOrNull()
                        if (canon == null || canon !in referencedFiles) runCatching { f.delete() }
                    }
                    if (child.listFiles().isNullOrEmpty()) runCatching { child.delete() }
                } else if (child.name.endsWith(".tmp")) {
                    runCatching { child.delete() }
                }
            }
        }
    }

    fun extractPlainPreviewText(doc: ParsedDocument): String {
        val sb = StringBuilder()
        for (block in doc.blocks) {
            when (block) {
                is DocBlock.Paragraph -> {
                    sb.append(block.runs.joinToString("") { it.text }.trimEnd()).append("\n\n")
                }
                is DocBlock.Table -> {
                    for (row in block.rows) {
                        val rowText = row.joinToString("  |  ") { cell ->
                            cell.joinToString(" ") { p -> p.runs.joinToString("") { it.text }.trim() }
                        }
                        if (rowText.isNotBlank()) sb.append(rowText).append('\n')
                    }
                    sb.append('\n')
                }
            }
        }
        return sb.toString().trim()
    }

    private suspend fun compactDocumentLocked(docKey: String, nowMs: Long, activeSessionBaselineId: Long?) {
        val revisions = revisionDao.getByDocKey(docKey)
        if (revisions.isEmpty()) return
        val toEvict = RecoveryVaultPolicy.selectPerDocumentEvictions(
            revisions.map { it.toPolicyRecord() }, nowMs, activeSessionBaselineId
        )
        if (toEvict.isNotEmpty()) deleteRevisionsLocked(revisions.filter { it.id in toEvict })
    }

    private suspend fun enforceGlobalQuotaLocked(freeBytes: Long) {
        val quota = RecoveryVaultPolicy.computeGlobalQuotaBytes(freeBytes)
        val all = revisionDao.getAllSnapshot()
        val toEvict = RecoveryVaultPolicy.selectGlobalQuotaEvictions(all.map { it.toPolicyRecord() }, quota)
        if (toEvict.isNotEmpty()) deleteRevisionsLocked(all.filter { it.id in toEvict })
    }

    private suspend fun deleteRevisionsLocked(targets: List<RevisionEntity>) {
        if (targets.isEmpty()) return
        for (rev in targets) runCatching { File(rev.snapshotFilePath).delete() }
        revisionDao.deleteByIds(targets.map { it.id })
    }

    private fun RevisionEntity.toPolicyRecord() = RecoveryVaultPolicy.RevisionRecord(
        id = id, docKey = docKey, createdAt = createdAt, charDelta = charDelta,
        fileSizeBytes = fileSizeBytes, isSessionBaseline = isSessionBaseline, orphanedAt = orphanedAt
    )

    private fun readGzipDocument(file: File): ParsedDocument? = runCatching {
        val json = GZIPInputStream(FileInputStream(file)).use { it.bufferedReader(Charsets.UTF_8).readText() }
        KannadaTextConverter.normalizeDocumentToUnicode(DocumentJson.fromJson(json))
    }.getOrNull()

    private fun writeGzipAtomic(target: File, rawBytes: ByteArray) {
        val baos = ByteArrayOutputStream(rawBytes.size / 2 + 64)
        GZIPOutputStream(baos).use { it.write(rawBytes) }
        writeBytesAtomic(target, baos.toByteArray())
    }

    private fun writeBytesAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(bytes)
            fos.flush()
            runCatching { fos.fd.sync() }
        }
        if (target.exists() && !tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        } else if (!target.exists() && !tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true); tmp.delete()
        }
    }

    private fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))
    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
