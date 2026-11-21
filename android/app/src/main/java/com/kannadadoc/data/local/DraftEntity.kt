package com.kannadadoc.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Auto-saved working draft so your dad never loses edits if the tablet sleeps
 * or the app closes unexpectedly.
 */
@Entity(tableName = "document_drafts")
data class DraftEntity(
    @PrimaryKey val uriString: String,
    val documentTitle: String,
    val draftHtml: String,
    val draftIdmJson: String,
    val converterUsed: String?,
    val updatedAt: Long
)
