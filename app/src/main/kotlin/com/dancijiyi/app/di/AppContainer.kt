package com.dancijiyi.app.di

import android.content.Context
import com.dancijiyi.data.importer.WordImportRunner
import com.dancijiyi.data.local.DancijiyiDatabase
import com.dancijiyi.data.repository.RoomWordRepository
import com.dancijiyi.domain.repository.WordRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock

/**
 * 手写依赖容器（docs/05 ADR：不引入 Hilt）。
 *
 * 组成根放在 :app，`:core-data` 只暴露实现类，不反向依赖 UI。
 * 数据库与仓库都是懒加载单例：桌面小组件与主 App 在同一进程内共享同一个库实例。
 */
class AppContainer(context: Context) {

    /** 注入式时钟：生产用系统时区，测试可换成固定时刻 */
    val clock: Clock = Clock.systemDefaultZone()

    /** 应用级协程作用域：用于种子数据写入等「不依附任何界面」的任务 */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database: DancijiyiDatabase by lazy { DancijiyiDatabase.get(context) }

    val repository: WordRepository by lazy { RoomWordRepository(database, clock) }

    /** 词库导入执行器：流式解析 + 分批入库 + 进度回调 */
    val importRunner: WordImportRunner by lazy { WordImportRunner(repository, clock) }
}
