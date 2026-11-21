package com.kannadadoc.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DraftDao {

    @Query("SELECT * FROM document_drafts WHERE uriString = :uriString LIMIT 1")
    suspend fun getDraft(uriString: String): DraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: DraftEntity)

    @Query("UPDATE document_drafts SET documentTitle = :newTitle WHERE uriString = :uriString")
    suspend fun updateTitle(uriString: String, newTitle: String)

    @Query("DELETE FROM document_drafts WHERE uriString = :uriString")
    suspend fun deleteDraft(uriString: String)
}
