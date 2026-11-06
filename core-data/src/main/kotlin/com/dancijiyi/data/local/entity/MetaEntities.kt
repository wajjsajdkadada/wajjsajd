package com.dancijiyi.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 导入记录表（docs/06 §4.5）：每次导入一条快照，用于「上次导入了什么」。
 *
 * 不建外键：词库被彻底删除后记录仍保留，book_id 变成悬空值，展示为「已删除的词库」。
 */
@Entity(tableName = "import_record")
data class ImportRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "book_id")
    val bookId: Long? = null,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    /** CSV / JSON */
    val format: String,
    val total: Int,
    val inserted: Int,
    val updated: Int,
    val skipped: Int,
    val failed: Int,
    @ColumnInfo(name = "failed_detail_json")
    val failedDetailJson: String? = null,
    /** DONE / CANCELED / FAILED */
    val status: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

/**
 * 键值设置表（docs/06 §4.6）：一律存字符串，读取方负责解析。
 */
@Entity(tableName = "setting")
data class SettingEntity(
    @PrimaryKey
    val key: String,
    val value: String,
)
