package com.kannadadoc.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kannadadoc.parser.DocFormat

@Entity(
    tableName = "recent_files",
    indices = [Index(value = ["uriString"], unique = true)]
)
data class RecentFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uriString: String,
    val displayName: String,
    val detectedFormat: DocFormat,
    val mimeType: String?,
    val fileSizeBytes: Long,
    val isLocallyImported: Boolean,
    val converterOverride: String? = null,
    val savedUnicodeUri: String? = null,
    val lastOpenedAt: Long
)
