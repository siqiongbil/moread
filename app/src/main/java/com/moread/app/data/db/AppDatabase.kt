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
    version = 8,
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

        /** v7 → v8：books.srvUpdated（书城书服务器副本指纹，追更对比用） */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `books` ADD COLUMN `srvUpdated` TEXT")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "moread.db",
                )
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .let { b ->
                        // 破坏性重建仅限 debug：release 漏写迁移时宁可崩溃暴露，
                        // 也不能静默清空用户书架（书架=用户数据，丢库是最坏结果）
                        if (com.moread.app.BuildConfig.DEBUG) b.fallbackToDestructiveMigration() else b
                    }
                    .build().also { instance = it }
            }
    }
}
