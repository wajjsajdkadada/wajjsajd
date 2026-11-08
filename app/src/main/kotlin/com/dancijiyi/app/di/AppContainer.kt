package com.dancijiyi.app.di

import android.content.Context
import com.dancijiyi.data.DataFactory
import com.dancijiyi.data.importer.WordImportRunner
import com.dancijiyi.domain.repository.AlgorithmConfigRepository
import com.dancijiyi.domain.repository.ReviewQueueRepository
import com.dancijiyi.domain.repository.WordRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock

/**
 * 手写依赖容器（docs/05 ADR：不引入 Hilt）。
 *
 * 组成根放在 :app，但**不直接接触 Room 类型**——数据库实例与 DAO 都封在 :core-data 内部，
 * 这里拿到的全是领域接口（WordRepository / ReviewQueueRepository / AlgorithmConfigRepository）。
 * 好处：app 模块换掉持久化实现（比如换 SQLDelight）不需要改一行 UI 代码。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 注入式时钟：生产用系统时区，测试可换成固定时刻 */
    val clock: Clock = Clock.systemDefaultZone()

    /** 应用级协程作用域：用于种子数据写入等「不依附任何界面」的任务 */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val repository: WordRepository by lazy { DataFactory.wordRepository(appContext, clock) }

    /** 算法参数仓库：设置页读写，调度器每次作答前读取最新值 */
    val algorithmConfigRepository: AlgorithmConfigRepository by lazy {
        DataFactory.algorithmConfigRepository(appContext)
    }

    /** 复习队列引擎：会话建队、作答结算、队列持久化 */
    val reviewQueueRepository: ReviewQueueRepository by lazy {
        DataFactory.reviewQueueRepository(appContext, algorithmConfigRepository, clock)
    }

    /** 词库导入执行器：流式解析 + 分批入库 + 进度回调 */
    val importRunner: WordImportRunner by lazy { WordImportRunner(repository, clock) }
}
