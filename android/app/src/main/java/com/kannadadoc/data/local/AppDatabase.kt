package com.kannadadoc.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kannadadoc.parser.DocFormat

class DocFormatConverter {
    @TypeConverter
    fun fromFormat(format: DocFormat): String = format.name

    @TypeConverter
    fun toFormat(value: String): DocFormat =
        runCatching { DocFormat.valueOf(value) }.getOrDefault(DocFormat.UNSUPPORTED)
}

@Database(
    entities = [RecentFileEntity::class, DraftEntity::class, RevisionEntity::class],
    version = 4,
    exportSchema = true
)
@TypeConverters(DocFormatConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun recentFileDao(): RecentFileDao
    abstract fun draftDao(): DraftDao
    abstract fun revisionDao(): RevisionDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `document_drafts` (" +
                        "`uriString` TEXT NOT NULL, " +
                        "`documentTitle` TEXT NOT NULL, " +
                        "`draftHtml` TEXT NOT NULL, " +
                        "`draftIdmJson` TEXT NOT NULL, " +
                        "`converterUsed` TEXT, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`uriString`))"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recent_files ADD COLUMN kannadaFormat TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recent_files ADD COLUMN lastModifiedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE recent_files ADD COLUMN isEdited INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE recent_files ADD COLUMN sourceAppLabel TEXT")
                db.execSQL("UPDATE recent_files SET lastModifiedAt = lastOpenedAt WHERE lastModifiedAt = 0")
                db.execSQL("UPDATE recent_files SET isEdited = 1 WHERE savedUnicodeUri IS NOT NULL")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `document_revisions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`docKey` TEXT NOT NULL, " +
                        "`uriString` TEXT NOT NULL, " +
                        "`documentTitle` TEXT NOT NULL, " +
                        "`snapshotFilePath` TEXT NOT NULL, " +
                        "`contentSha256` TEXT NOT NULL, " +
                        "`charCount` INTEGER NOT NULL, " +
                        "`charDelta` INTEGER NOT NULL, " +
                        "`fileSizeBytes` INTEGER NOT NULL, " +
                        "`triggerReason` TEXT NOT NULL, " +
                        "`isSessionBaseline` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`orphanedAt` INTEGER)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_document_revisions_docKey_createdAt` " +
                        "ON `document_revisions` (`docKey`, `createdAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_document_revisions_uriString` " +
                        "ON `document_revisions` (`uriString`)"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "kannadadoc.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
