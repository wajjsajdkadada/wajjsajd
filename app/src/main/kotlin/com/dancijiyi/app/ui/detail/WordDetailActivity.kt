package com.dancijiyi.app.ui.detail

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dancijiyi.app.DanCiJiYiApp
import com.dancijiyi.app.R
import com.dancijiyi.domain.model.ReviewRecord
import com.dancijiyi.domain.model.ReviewState
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 单词详情页：释义 / 例句 / 记忆状态 / 复习历史。
 *
 * 返回栈：从列表 `startActivity` 进来，返回键走系统默认行为回到列表；
 * 这里额外做了两件事保证体验：
 * 1. 顶栏「← 返回」直接调用 `onBackPressedDispatcher`，与系统返回键行为一致；
 * 2. 词已被删除时提示并自动 `finish()`，不会停在空白页。
 */
class WordDetailActivity : ComponentActivity() {

    private val wordId: Long by lazy { intent.getLongExtra(EXTRA_WORD_ID, -1L) }

    private val viewModel: WordDetailViewModel by viewModels {
        WordDetailViewModel.factory((application as DanCiJiYiApp).container, wordId)
    }

    private lateinit var headerTitle: TextView
    private lateinit var spelling: TextView
    private lateinit var phonetic: TextView
    private lateinit var status: TextView
    private lateinit var meaning: TextView
    private lateinit var example: TextView
    private lateinit var exampleTranslation: TextView
    private lateinit var statStage: TextView
    private lateinit var statEase: TextView
    private lateinit var statDue: TextView
    private lateinit var statLapse: TextView
    private lateinit var stageProgress: ProgressBar
    private lateinit var toggleMastered: Button
    private lateinit var historyContainer: LinearLayout

    private var lastState: ReviewState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_word_detail)

        headerTitle = findViewById(R.id.headerTitle)
        spelling = findViewById(R.id.detailSpelling)
        phonetic = findViewById(R.id.detailPhonetic)
        status = findViewById(R.id.detailStatus)
        meaning = findViewById(R.id.detailMeaning)
        example = findViewById(R.id.detailExample)
        exampleTranslation = findViewById(R.id.detailExampleTranslation)
        statStage = findViewById(R.id.statStage)
        statEase = findViewById(R.id.statEase)
        statDue = findViewById(R.id.statDue)
        statLapse = findViewById(R.id.statLapse)
        stageProgress = findViewById(R.id.stageProgress)
        toggleMastered = findViewById(R.id.toggleMastered)
        historyContainer = findViewById(R.id.historyContainer)

        findViewById<Button>(R.id.backButton).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        toggleMastered.setOnClickListener {
            lastState?.let { viewModel.toggleMastered(it) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { render(it) }
            }
        }
    }

    private fun render(state: WordDetailUiState) {
        when (state) {
            WordDetailUiState.Loading -> headerTitle.text = "加载中…"

            WordDetailUiState.NotFound -> {
                Toast.makeText(this, "该单词已被删除", Toast.LENGTH_SHORT).show()
                finish()
            }

            is WordDetailUiState.Error -> headerTitle.text = "加载失败：${state.message}"

            is WordDetailUiState.Content -> {
                lastState = state.state
                headerTitle.text = state.bookName ?: "单词详情"

                spelling.text = state.word.spelling
                phonetic.text = state.word.phonetic.orEmpty()
                phonetic.visibility = if (state.word.phonetic.isNullOrBlank()) View.GONE else View.VISIBLE

                status.text = "〔${state.statusLabel}〕"
                status.setTextColor(
                    when {
                        state.state.isMastered -> 0xFF2E9E5B.toInt()
                        state.state.reviewCount == 0 -> 0xFF3B82F6.toInt()
                        else -> 0xFFD9534F.toInt()
                    },
                )

                meaning.text = state.word.meaning
                example.text = state.word.example ?: "（暂无例句）"
                exampleTranslation.text = state.word.exampleTranslation.orEmpty()
                exampleTranslation.visibility =
                    if (state.word.exampleTranslation.isNullOrBlank()) View.GONE else View.VISIBLE

                statStage.text = "阶段\n${state.state.stage}/9"
                statEase.text = "难度\n%.2f".format(state.state.easeFactor)
                statDue.text = "到期\n${formatDue(state.state.nextDueAt)}"
                statLapse.text = "遗忘\n${state.state.lapseCount} 次"
                stageProgress.progress = (state.stageFraction * 100).toInt()

                toggleMastered.text = if (state.state.isMastered) "取消掌握" else "标记为已掌握"

                renderHistory(state.history)
            }
        }
    }

    private fun renderHistory(history: List<ReviewRecord>) {
        historyContainer.removeAllViews()
        if (history.isEmpty()) {
            historyContainer.addView(
                TextView(this).apply {
                    text = "还没有复习记录"
                    textSize = 14f
                    setTextColor(0xFF888888.toInt())
                    setPadding(0, 8, 0, 8)
                },
            )
            return
        }
        history.forEach { record ->
            historyContainer.addView(
                TextView(this).apply {
                    text = buildString {
                        append(TIME_FORMAT.format(record.reviewedAt))
                        append("   ")
                        append("〔${record.qualityLabel}〕")
                        append("   ")
                        append(record.stageTransition)
                        append("   ")
                        append("%.1f 秒".format(record.durationMs / 1000.0))
                    }
                    textSize = 13f
                    setPadding(0, 6, 0, 6)
                },
            )
        }
    }

    private fun formatDue(dueAt: Instant): String {
        val now = Instant.now()
        if (!dueAt.isAfter(now)) {
            val hours = ChronoUnit.HOURS.between(dueAt, now)
            return if (hours <= 0) "现在" else "已过期 ${hours} 小时"
        }
        val days = ChronoUnit.DAYS.between(now, dueAt)
        return when {
            days <= 0 -> "今天"
            days == 1L -> "明天"
            else -> "$days 天后"
        }
    }

    companion object {
        const val EXTRA_WORD_ID = "extra_word_id"
        private val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}
