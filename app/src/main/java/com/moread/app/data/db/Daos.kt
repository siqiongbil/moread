package com.moread.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE origin = :origin ORDER BY id DESC LIMIT 1")
    suspend fun findByOrigin(origin: String): BookEntity?

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("UPDATE books SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index`")
    suspend fun chaptersFor(bookId: Long): List<ChapterEntity>

    @Insert
    suspend fun insertAll(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun get(bookId: Long): ProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ProgressEntity)

    @Query("DELETE FROM progress WHERE bookId = :bookId")
    suspend fun delete(bookId: Long)
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM toc_rules ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM toc_rules ORDER BY sortOrder, id")
    suspend fun getAll(): List<RuleEntity>

    @Insert
    suspend fun insertAll(rules: List<RuleEntity>)

    @Insert
    suspend fun insert(rule: RuleEntity): Long

    @Update
    suspend fun update(rule: RuleEntity)

    @Query("DELETE FROM toc_rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM toc_rules")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM toc_rules")
    suspend fun count(): Int
}

/** 书 + 章的原子替换：重新分章时使用。 */
@Dao
abstract class BookChapterDao {
    @Transaction
    open suspend fun replaceChapters(chapterDao: ChapterDao, bookId: Long, chapters: List<ChapterEntity>) {
        chapterDao.deleteForBook(bookId)
        chapterDao.insertAll(chapters)
    }
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapterIndex, pageIndex, id")
    fun observeFor(bookId: Long): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun insert(b: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}
