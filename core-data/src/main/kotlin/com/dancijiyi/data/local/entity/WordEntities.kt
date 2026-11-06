package com.dancijiyi.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 词库表（docs/06 §4.1）。
 *
 * [archivedAt] 非空＝已归档：退出切换器与复习队列，但单词/状态/流水全部保留，
 * 统计继续计入——这是「删除词库不伤统计」的实现基础（docs/06 §6.2）。
 */
@Entity(tableName = "wordbook")
data class WordbookEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String? = null,
    @ColumnInfo(name = "is_builtin")
    val isBuiltin: Boolean = false,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "archived_at")
    val archivedAt: Long? = null,
)

/**
 * 单词表（docs/06 §4.2）。
 *
 * [spellingLower] 是去重与搜索键：SQLite 的 `COLLATE NOCASE` 只处理 ASCII，
 * 且 Room 无法在 @Index 上声明 collation，因此显式存小写列，
 * 配合 `(book_id, spelling_lower)` 唯一索引同时支撑 upsert、字母排序与前缀搜索。
 */
@Entity(
    tableName = "word",
    foreignKeys = [
        ForeignKey(
            entity = WordbookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["book_id", "spelling_lower"], unique = true)],
)
data class WordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "book_id")
    val bookId: Long,
    val spelling: String,
    @ColumnInfo(name = "spelling_lower")
    val spellingLower: String,
    val phonetic: String? = null,
    val meaning: String,
    val example: String? = null,
    @ColumnInfo(name = "example_translation")
    val exampleTranslation: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
