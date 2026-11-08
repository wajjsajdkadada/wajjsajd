package com.dancijiyi.domain.model

/**
 * 算法参数（可在设置页调整，见 [AlgorithmConfigRepository]）。
 *
 * 为什么单独抽成数据类而不是散落在常量里：
 * 1. 调度器是纯函数，参数从外部传入 → 单测可以精确控制边界；
 * 2. 设置页直接编辑这个对象，落库到 `setting` 表，重启后生效；
 * 3. 将来换算法（如 SM-2）只需换实现，配置结构不用动。
 */
data class AlgorithmConfig(
    /** 每日复习上限（含顺延判断），20..200 */
    val dailyReviewLimit: Int = 50,
    /** 每日新词上限（新词单独限流，避免一次性塞爆） */
    val dailyNewLimit: Int = 20,
    /** 间隔抖动开关：stage≥4 时对间隔做 ±5% 随机，打散「批量导入同时到期」的尖峰 */
    val jitterEnabled: Boolean = true,
    /** 难度系数下限 */
    val easeFloor: Double = 1.30,
    /** 难度系数上限 */
    val easeCeiling: Double = 2.80,
    /** 算法版本，参数结构不兼容变更时用于提示「需重置进度」 */
    val version: String = "ebbinghaus-delta-v1",
) {
    fun sanitized(): AlgorithmConfig = copy(
        dailyReviewLimit = dailyReviewLimit.coerceIn(20, 200),
        dailyNewLimit = dailyNewLimit.coerceIn(5, 100),
        easeFloor = easeFloor.coerceIn(1.10, 2.00),
        easeCeiling = easeCeiling.coerceIn(2.00, 3.50),
    )

    companion object {
        /** 默认配置（docs/01 §5.4.4） */
        val DEFAULT = AlgorithmConfig()

        const val KEY_DAILY_REVIEW_LIMIT = "algo.daily_review_limit"
        const val KEY_DAILY_NEW_LIMIT = "algo.daily_new_limit"
        const val KEY_JITTER_ENABLED = "algo.jitter_enabled"
        const val KEY_VERSION = "algo.version"
    }
}
