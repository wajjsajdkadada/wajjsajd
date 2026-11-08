package com.dancijiyi.data.settings

import com.dancijiyi.data.local.dao.MetaDao
import com.dancijiyi.data.local.entity.SettingEntity
import com.dancijiyi.domain.model.AlgorithmConfig
import com.dancijiyi.domain.repository.AlgorithmConfigRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 算法参数存储：复用 `setting` 键值表，不额外建表。
 *
 * 读：一次 `loadConfig()` 读全部键（作答前调用，开销可忽略）；
 * 写：逐键 upsert，`ON CONFLICT REPLACE` 保证幂等。
 */
class RoomAlgorithmConfigRepository(
    private val metaDao: MetaDao,
) : AlgorithmConfigRepository {

    override fun observeConfig(): Flow<AlgorithmConfig> =
        metaDao.observeAlgorithmSettings().map { rows -> rows.toConfig() }

    override suspend fun loadConfig(): AlgorithmConfig {
        val defaults = AlgorithmConfig.DEFAULT
        return AlgorithmConfig(
            dailyReviewLimit = metaDao.getSetting(AlgorithmConfig.KEY_DAILY_REVIEW_LIMIT)
                ?.toIntOrNull() ?: defaults.dailyReviewLimit,
            dailyNewLimit = metaDao.getSetting(AlgorithmConfig.KEY_DAILY_NEW_LIMIT)
                ?.toIntOrNull() ?: defaults.dailyNewLimit,
            jitterEnabled = metaDao.getSetting(AlgorithmConfig.KEY_JITTER_ENABLED)
                ?.toBooleanStrictOrNull() ?: defaults.jitterEnabled,
            version = metaDao.getSetting(AlgorithmConfig.KEY_VERSION) ?: defaults.version,
        ).sanitized()
    }

    override suspend fun saveConfig(config: AlgorithmConfig) {
        val safe = config.sanitized()
        metaDao.putSetting(SettingEntity(AlgorithmConfig.KEY_DAILY_REVIEW_LIMIT, safe.dailyReviewLimit.toString()))
        metaDao.putSetting(SettingEntity(AlgorithmConfig.KEY_DAILY_NEW_LIMIT, safe.dailyNewLimit.toString()))
        metaDao.putSetting(SettingEntity(AlgorithmConfig.KEY_JITTER_ENABLED, safe.jitterEnabled.toString()))
        metaDao.putSetting(SettingEntity(AlgorithmConfig.KEY_VERSION, safe.version))
    }

    override suspend fun resetToDefault() {
        saveConfig(AlgorithmConfig.DEFAULT)
    }

    private fun List<SettingEntity>.toConfig(): AlgorithmConfig {
        val map = associate { it.key to it.value }
        val defaults = AlgorithmConfig.DEFAULT
        return AlgorithmConfig(
            dailyReviewLimit = map[AlgorithmConfig.KEY_DAILY_REVIEW_LIMIT]?.toIntOrNull()
                ?: defaults.dailyReviewLimit,
            dailyNewLimit = map[AlgorithmConfig.KEY_DAILY_NEW_LIMIT]?.toIntOrNull()
                ?: defaults.dailyNewLimit,
            jitterEnabled = map[AlgorithmConfig.KEY_JITTER_ENABLED]?.toBooleanStrictOrNull()
                ?: defaults.jitterEnabled,
            version = map[AlgorithmConfig.KEY_VERSION] ?: defaults.version,
        ).sanitized()
    }
}
