package com.dancijiyi.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2：新增复习队列的两张表（`review_session`、`queue_item`）。
 *
 * 为什么必须有迁移而不是清库重建：应用已经装过 v1（模拟器上就有数据），
 * 直接改版本号会让 Room 抛 `IllegalStateException: Room cannot verify the data integrity`。
 * 这里保留 `fallbackToDestructiveMigration` 关闭的状态，用真实迁移演示「不丢用户数据」。
 *
 * 建表语句与 `core-data/schemas/.../2.json` 中的 `createSql` **逐字一致**，
 * 否则 Room 启动时校验 schema 会失败。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `review_session` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`date_key` TEXT NOT NULL, " +
                "`started_at` INTEGER NOT NULL, " +
                "`ended_at` INTEGER, " +
                "`planned_count` INTEGER NOT NULL, " +
                "`answered_count` INTEGER NOT NULL, " +
                "`deferred_count` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_review_session_date_key` " +
                "ON `review_session` (`date_key`)",
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `queue_item` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`session_id` INTEGER NOT NULL, " +
                "`word_id` INTEGER NOT NULL, " +
                "`position` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "FOREIGN KEY(`session_id`) REFERENCES `review_session`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`word_id`) REFERENCES `word`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_queue_item_session_id_position` " +
                "ON `queue_item` (`session_id`, `position`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_queue_item_session_id_word_id` " +
                "ON `queue_item` (`session_id`, `word_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_queue_item_word_id` " +
                "ON `queue_item` (`word_id`)",
        )
    }
}
