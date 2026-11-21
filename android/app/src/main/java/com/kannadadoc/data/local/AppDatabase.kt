package com.kannadadoc.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.kannadadoc.parser.DocFormat

class DocFormatConverter {
    @TypeConverter
    fun fromFormat(format: DocFormat): String = format.name

    @TypeConverter
    fun toFormat(value: String): DocFormat =
        runCatching { DocFormat.valueOf(value) }.getOrDefault(DocFormat.UNSUPPORTED)
}

@Database(
    entities = [RecentFileEntity::class, DraftEntity::class],
    version = 2,
    exportSchema = true
)
@TypeConverters(DocFormatConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun recentFileDao(): RecentFileDao
    abstract fun draftDao(): DraftDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "kannadadoc.db"
            )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
