package com.dancijiyi.data.seed

import com.dancijiyi.domain.model.WordDraft

/**
 * 内置示例词库（`docs/01` FR-1.10）：首次启动写入，可删除但不会自动重建。
 * 词条只保留最必要的四要素，用于演示「导入 → 列表 → 复习」全链路。
 */
object SeedData {

    const val BOOK_NAME = "四级核心 1600（示例）"

    val WORDS: List<WordDraft> = listOf(
        WordDraft("abandon", "/əˈbændən/", "v. 放弃；抛弃", "They abandoned the plan.", "他们放弃了这个计划。"),
        WordDraft("ability", "/əˈbɪləti/", "n. 能力；才能", "She has the ability to lead.", "她有领导才能。"),
        WordDraft("absorb", "/əbˈzɔːb/", "v. 吸收；吸引", "Plants absorb carbon dioxide.", "植物吸收二氧化碳。"),
        WordDraft("accurate", "/ˈækjərət/", "adj. 准确的；精确的", "The data is accurate.", "这些数据是准确的。"),
        WordDraft("achieve", "/əˈtʃiːv/", "v. 实现；达到", "He achieved his goal.", "他实现了目标。"),
        WordDraft("adapt", "/əˈdæpt/", "v. 适应；改编", "Children adapt quickly.", "孩子们适应得很快。"),
        WordDraft("adequate", "/ˈædɪkwət/", "adj. 足够的；适当的", "We need adequate time.", "我们需要足够的时间。"),
        WordDraft("advocate", "/ˈædvəkeɪt/", "v. 提倡；拥护", "They advocate reform.", "他们提倡改革。"),
        WordDraft("alter", "/ˈɔːltə(r)/", "v. 改变；更改", "Nothing will alter my mind.", "什么也改变不了我的想法。"),
        WordDraft("ambiguous", "/æmˈbɪɡjuəs/", "adj. 模棱两可的", "His answer was ambiguous.", "他的回答模棱两可。"),
        WordDraft("analyze", "/ˈænəlaɪz/", "v. 分析；解析", "Let's analyze the result.", "我们来分析这个结果。"),
        WordDraft("anticipate", "/ænˈtɪsɪpeɪt/", "v. 预期；预料", "We anticipate a delay.", "我们预计会延误。"),
    )
}
