package com.kannadadoc.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RevisionDao {

    @Query("SELECT * FROM document_revisions WHERE docKey = :docKey ORDER BY createdAt DESC")
    suspend fun getByDocKey(docKey: String): List<RevisionEntity>

    @Query("SELECT * FROM document_revisions WHERE uriString = :uriString ORDER BY createdAt DESC")
    suspend fun getByUri(uriString: String): List<RevisionEntity>

    @Query("SELECT * FROM document_revisions ORDER BY createdAt DESC")
    suspend fun getAllSnapshot(): List<RevisionEntity>

    @Query("SELECT * FROM document_revisions WHERE docKey = :docKey ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestForDocKey(docKey: String): RevisionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RevisionEntity): Long

    @Query("UPDATE document_revisions SET orphanedAt = :orphanedAt WHERE uriString = :uriString AND orphanedAt IS NULL")
    suspend fun markOrphanedByUri(uriString: String, orphanedAt: Long)

    @Query("UPDATE document_revisions SET orphanedAt = NULL WHERE uriString = :uriString")
    suspend fun clearOrphanedByUri(uriString: String)

    @Query("UPDATE document_revisions SET documentTitle = :newTitle WHERE uriString = :uriString")
    suspend fun updateTitleByUri(uriString: String, newTitle: String)

    @Query("UPDATE document_revisions SET uriString = :newUri, docKey = :newDocKey WHERE uriString = :oldUri")
    suspend fun rebindUri(oldUri: String, newUri: String, newDocKey: String)

    @Query("DELETE FROM document_revisions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
