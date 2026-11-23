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
    // Legacy v2/v3 column retained only for non-destructive schema compatibility;
    // v0.21.0 promotes all legacy savedUnicodeUri entries into canonical uriString (1 Card = 1 File).
    val savedUnicodeUri: String? = null,
    val kannadaFormat: String? = null,
    val lastOpenedAt: Long,
    val lastModifiedAt: Long = lastOpenedAt,
    val isEdited: Boolean = false,
    val sourceAppLabel: String? = null
) {
    val displayFormat: DocFormat
        get() = DocFormat.displayFormatFor(displayName, detectedFormat)
}
