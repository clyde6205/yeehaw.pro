package co.yehaw.browser.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TabStateEntity::class, ContainerEntity::class, ArchiveEntity::class, DownloadEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tabStateDao(): TabStateDao
    abstract fun containerDao(): ContainerDao
    abstract fun archiveDao(): ArchiveDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "yeehaw.db")
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
