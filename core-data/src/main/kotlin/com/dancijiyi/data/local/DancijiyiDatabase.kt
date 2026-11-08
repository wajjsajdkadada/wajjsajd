package com.dancijiyi.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.dancijiyi.data.local.dao.MetaDao
import com.dancijiyi.data.local.dao.QueueDao
import com.dancijiyi.data.local.dao.ReviewDao
import com.dancijiyi.data.local.dao.WordDao
import com.dancijiyi.data.local.dao.WordbookDao
import com.dancijiyi.data.local.entity.ImportRecordEntity
import com.dancijiyi.data.local.entity.QueueItemEntity
import com.dancijiyi.data.local.entity.ReviewLogEntity
import com.dancijiyi.data.local.entity.ReviewSessionEntity
import com.dancijiyi.data.local.entity.ReviewStateEntity
import com.dancijiyi.data.local.entity.SettingEntity
import com.dancijiyi.data.local.entity.WordEntity
import com.dancijiyi.data.local.entity.WordbookEntity

/**
 * 应用数据库（docs/06）。
 *
 * - `exportSchema = true` 且把 schema 写入 `core-data/schemas`，纳入版本管理，便于迁移测试；
 * - 禁止 `fallbackToDestructiveMigration`：静默清库在答辩演示时是灾难；
 * - Room 默认开启外键（PRAGMA foreign_keys=ON），归档/彻底删除语义依赖这一点；
 * - 主线程访问默认禁止（allowMainThreadQueries 未开启）。
 */
@Database(
    entities = [
        WordbookEntity::class,
        WordEntity::class,
        ReviewStateEntity::class,
        ReviewLogEntity::class,
        ImportRecordEntity::class,
        SettingEntity::class,
        ReviewSessionEntity::class,
        QueueItemEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class DancijiyiDatabase : RoomDatabase() {

    abstract fun wordbookDao(): WordbookDao

    abstract fun wordDao(): WordDao

    abstract fun reviewDao(): ReviewDao

    abstract fun metaDao(): MetaDao

    abstract fun queueDao(): QueueDao

    companion object {
        const val DB_NAME = "dancijiyi.db"

        @Volatile
        private var instance: DancijiyiDatabase? = null

        /** 进程内单例：主 App 与桌面小组件共用同一个库文件 */
        fun get(context: Context): DancijiyiDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): DancijiyiDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                DancijiyiDatabase::class.java,
                DB_NAME,
            )
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
