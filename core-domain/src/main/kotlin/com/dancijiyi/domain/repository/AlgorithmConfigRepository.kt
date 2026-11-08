package com.dancijiyi.domain.repository

import com.dancijiyi.domain.model.AlgorithmConfig
import kotlinx.coroutines.flow.Flow

/**
 * 算法参数读写契约（设置页的「算法参数」入口用它）。
 */
interface AlgorithmConfigRepository {

    /** 观察参数变化（设置页改完立即生效，调度器每次作答时读取最新值） */
    fun observeConfig(): Flow<AlgorithmConfig>

    /** 读取一次当前参数（调度器在作答事务前调用） */
    suspend fun loadConfig(): AlgorithmConfig

    /** 保存参数（内部会 sanitize 到合法区间） */
    suspend fun saveConfig(config: AlgorithmConfig)

    /** 恢复默认参数 */
    suspend fun resetToDefault()
}
