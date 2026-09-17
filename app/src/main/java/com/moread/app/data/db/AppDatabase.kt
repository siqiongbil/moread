package com.moread.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BookEntity::class, ChapterEntity::class, ProgressEntity::class,
        RuleEntity::class, BookmarkEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun progressDao(): ProgressDao
    abstract fun ruleDao(): RuleDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v5 → v6：新增书签表。存量书籍/进度/规则全部保留。 */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`bookId` INTEGER NOT NULL, `chapterIndex` INTEGER NOT NULL, " +
                        "`pageIndex` INTEGER NOT NULL, `charRatio` REAL NOT NULL, " +
                        "`preview` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)")
            }
        }

        /** v6 → v7：books.origin 索引（去重查询加速；非唯一，容忍历史重复行） */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_origin` ON `books` (`origin`)")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "moread.db",
                )
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7)
                    // 未登记的更早结构变更仍直接重建（开发期历史版本）
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
