package com.kannadadoc.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentFileDao {

    @Query("SELECT * FROM recent_files ORDER BY lastModifiedAt DESC, id DESC")
    fun observeRecentFiles(): Flow<List<RecentFileEntity>>

    @Query("SELECT * FROM recent_files")
    suspend fun getAllSnapshot(): List<RecentFileEntity>

    @Query(
        "SELECT * FROM recent_files " +
            "WHERE fileSizeBytes <= 0 OR kannadaFormat IS NULL OR kannadaFormat = '' OR kannadaFormat = 'PDF' " +
            "ORDER BY lastModifiedAt DESC LIMIT :batchSize"
    )
    fun observePendingMetadataBackfill(batchSize: Int = 50): Flow<List<RecentFileEntity>>

    @Query("SELECT * FROM recent_files WHERE uriString = :uriString LIMIT 1")
    suspend fun getByUri(uriString: String): RecentFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RecentFileEntity): Long

    @Query("UPDATE recent_files SET displayName = :newDisplayName, lastModifiedAt = :modifiedAt WHERE uriString = :uriString")
    suspend fun updateDisplayName(uriString: String, newDisplayName: String, modifiedAt: Long = System.currentTimeMillis())

    @Query("UPDATE recent_files SET fileSizeBytes = :sizeBytes WHERE uriString = :uriString")
    suspend fun updateFileSize(uriString: String, sizeBytes: Long)

    @Query("UPDATE recent_files SET kannadaFormat = :kannadaFormat WHERE uriString = :uriString")
    suspend fun updateKannadaFormat(uriString: String, kannadaFormat: String)

    @Query("UPDATE recent_files SET converterOverride = :converterId WHERE uriString = :uriString")
    suspend fun updateConverterOverride(uriString: String, converterId: String?)

    @Query("UPDATE recent_files SET lastOpenedAt = :openedAt WHERE uriString = :uriString")
    suspend fun updateLastOpenedAt(uriString: String, openedAt: Long)


    @Query(
        "UPDATE recent_files SET uriString = :newUri, displayName = :newDisplayName, " +
            "lastModifiedAt = :modifiedAt WHERE uriString = :oldUri"
    )
    suspend fun updateUriAndDisplayName(
        oldUri: String,
        newUri: String,
        newDisplayName: String,
        modifiedAt: Long
    ): Int

    @Query(
        "UPDATE document_revisions SET uriString = :newUri, docKey = :newDocKey, " +
            "documentTitle = :newTitle WHERE uriString = :oldUri"
    )
    suspend fun rebindRevisionUriAndTitle(
        oldUri: String,
        newUri: String,
        newDocKey: String,
        newTitle: String
    )

    @Query("UPDATE document_drafts SET uriString = :newUri, documentTitle = :newTitle WHERE uriString = :oldUri")
    suspend fun rebindDraftUriAndTitle(oldUri: String, newUri: String, newTitle: String)

    @Transaction
    suspend fun commitPhysicalRenameTransaction(
        oldUri: String,
        newUri: String,
        newDocKey: String,
        newDisplayName: String,
        modifiedAt: Long
    ): Int {
        val updated = updateUriAndDisplayName(oldUri, newUri, newDisplayName, modifiedAt)
        rebindRevisionUriAndTitle(oldUri, newUri, newDocKey, newDisplayName)
        rebindDraftUriAndTitle(oldUri, newUri, newDisplayName)
        return updated
    }

    @Query("DELETE FROM recent_files WHERE uriString = :uriString")
    suspend fun deleteByUri(uriString: String)
}
