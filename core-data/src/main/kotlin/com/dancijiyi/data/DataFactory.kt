package com.dancijiyi.data

import android.content.Context
import com.dancijiyi.data.repository.RoomReviewQueueRepository
import com.dancijiyi.data.repository.RoomWordRepository
import com.dancijiyi.data.settings.RoomAlgorithmConfigRepository
import com.dancijiyi.data.local.DancijiyiDatabase
import com.dancijiyi.domain.repository.AlgorithmConfigRepository
import com.dancijiyi.domain.repository.ReviewQueueRepository
import com.dancijiyi.domain.repository.WordRepository
import java.time.Clock

/**
 * 数据层对外工厂。
 *
 * 为什么要它：`:core-data` 把 Room 作为 `implementation` 依赖（不暴露到 app），
 * 所以 app 层不应该、也无法直接操作 `DancijiyiDatabase`（它的父类 RoomDatabase 不在 app 的
 * 编译类路径上）。这里只暴露**领域接口**，数据库实例封闭在数据层内部。
 */
object DataFactory {

    fun wordRepository(context: Context, clock: Clock): WordRepository =
        RoomWordRepository(DancijiyiDatabase.get(context), clock)

    fun algorithmConfigRepository(context: Context): AlgorithmConfigRepository =
        RoomAlgorithmConfigRepository(DancijiyiDatabase.get(context).metaDao())

    fun reviewQueueRepository(
        context: Context,
        configRepository: AlgorithmConfigRepository,
        clock: Clock,
    ): ReviewQueueRepository =
        RoomReviewQueueRepository(DancijiyiDatabase.get(context), configRepository, clock)
}
