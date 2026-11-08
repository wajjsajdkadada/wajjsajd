package com.dancijiyi.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dancijiyi.data.local.entity.ImportRecordEntity
import com.dancijiyi.data.local.entity.SettingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MetaDao {

    // ---------------- 设置 ----------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSetting(setting: SettingEntity)

    @Query("SELECT value FROM setting WHERE key = :key")
    suspend fun getSetting(key: String): String?

    /** 算法参数整组观察（key 前缀 algo.） */
    @Query("SELECT * FROM setting WHERE key LIKE 'algo.%'")
    fun observeAlgorithmSettings(): Flow<List<SettingEntity>>

    @Query("DELETE FROM setting")
    suspend fun clearSettings()

    // ---------------- 导入记录 ----------------

    @Insert
    suspend fun insertImportRecord(record: ImportRecordEntity): Long

    @Query("SELECT * FROM import_record ORDER BY created_at DESC LIMIT :limit")
    fun observeImportRecords(limit: Int): Flow<List<ImportRecordEntity>>
}
