package com.kannadadoc.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "document_revisions",
    indices = [
        Index(value = ["docKey", "createdAt"]),
        Index(value = ["uriString"])
    ]
)
data class RevisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docKey: String,
    val uriString: String,
    val documentTitle: String,
    val snapshotFilePath: String,
    val contentSha256: String,
    val charCount: Int,
    val charDelta: Int,
    val fileSizeBytes: Long,
    val triggerReason: String,
    val isSessionBaseline: Boolean = false,
    val createdAt: Long,
    val orphanedAt: Long? = null
)
