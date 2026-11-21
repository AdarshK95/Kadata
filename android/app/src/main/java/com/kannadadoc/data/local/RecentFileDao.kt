package com.kannadadoc.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentFileDao {

    @Query("SELECT * FROM recent_files ORDER BY lastOpenedAt DESC LIMIT :limit")
    fun observeRecentFiles(limit: Int = 60): Flow<List<RecentFileEntity>>

    @Query("SELECT * FROM recent_files WHERE uriString = :uriString LIMIT 1")
    suspend fun getByUri(uriString: String): RecentFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RecentFileEntity): Long

    @Query("UPDATE recent_files SET displayName = :newDisplayName WHERE uriString = :uriString")
    suspend fun updateDisplayName(uriString: String, newDisplayName: String)

    @Query("UPDATE recent_files SET converterOverride = :converterId WHERE uriString = :uriString")
    suspend fun updateConverterOverride(uriString: String, converterId: String?)

    @Query("UPDATE recent_files SET savedUnicodeUri = :savedUri, lastOpenedAt = :timestamp WHERE uriString = :uriString")
    suspend fun updateSavedUnicodeUri(uriString: String, savedUri: String, timestamp: Long)

    @Query("DELETE FROM recent_files WHERE uriString = :uriString")
    suspend fun deleteByUri(uriString: String)
}
