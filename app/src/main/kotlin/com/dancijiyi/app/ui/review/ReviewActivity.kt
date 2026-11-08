package com.dancijiyi.app.ui.review

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dancijiyi.app.DanCiJiYiApp
import com.dancijiyi.app.R
import com.dancijiyi.domain.model.Quality
import kotlinx.coroutines.launch

/**
 * 复习页：一屏一词、翻牌作答、进度与顺延提示、完成页。
 *
 * 队列来源：`ReviewViewModel` → `ReviewQueueRepository.openQueue`（当天会话唯一，重启后恢复）。
 * 作答后由 Flow 推送新队列，UI 不手动刷新。
 */
class ReviewActivity : ComponentActivity() {

    private val viewModel: ReviewViewModel by viewModels {
        ReviewViewModel.factory((application as DanCiJiYiApp).container)
    }

    private lateinit var progress: ProgressBar
    private lateinit var count: TextView
    private lateinit var deferredNotice: TextView
    private lateinit var cardBox: LinearLayout
    private lateinit var spelling: TextView
    private lateinit var phonetic: TextView
    private lateinit var meaning: TextView
    private lateinit var hint: TextView
    private lateinit var stateBox: LinearLayout
    private lateinit var stateText: TextView
    private lateinit var finishedBox: LinearLayout
    private lateinit var finishedStats: TextView
    private lateinit var qualityRow: LinearLayout
    private lateinit var skipButton: Button
    private lateinit var qualityButtons: List<Button>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_review)

        progress = findViewById(R.id.queueProgress)
        count = findViewById(R.id.queueCount)
        deferredNotice = findViewById(R.id.deferredNotice)
        cardBox = findViewById(R.id.cardBox)
        spelling = findViewById(R.id.cardSpelling)
        phonetic = findViewById(R.id.cardPhonetic)
        meaning = findViewById(R.id.cardMeaning)
        hint = findViewById(R.id.cardHint)
        stateBox = findViewById(R.id.stateBox)
        stateText = findViewById(R.id.stateText)
        finishedBox = findViewById(R.id.finishedBox)
        finishedStats = findViewById(R.id.finishedStats)
        qualityRow = findViewById(R.id.qualityRow)
        skipButton = findViewById(R.id.skipButton)

        val btnForgot: Button = findViewById(R.id.btnForgot)
        val btnVague: Button = findViewById(R.id.btnVague)
        val btnRecalled: Button = findViewById(R.id.btnRecalled)
        val btnFluent: Button = findViewById(R.id.btnFluent)
        qualityButtons = listOf(btnForgot, btnVague, btnRecalled, btnFluent)

        cardBox.setOnClickListener { viewModel.reveal() }
        btnForgot.setOnClickListener { viewModel.answer(Quality.FORGOT) }
        btnVague.setOnClickListener { viewModel.answer(Quality.VAGUE) }
        btnRecalled.setOnClickListener { viewModel.answer(Quality.RECALLED) }
        btnFluent.setOnClickListener { viewModel.answer(Quality.FLUENT) }
        skipButton.setOnClickListener { viewModel.skip() }
        findViewById<Button>(R.id.finishedBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.stateAction).apply {
            text = "重试"
            setOnClickListener { viewModel.open() }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { render(it) }
            }
        }
    }

    private fun render(state: ReviewUiState) {
        when (state) {
            ReviewUiState.Loading -> showState("正在准备今日队列…")

            is ReviewUiState.NoDue -> {
                val suffix = if (state.deferredCount > 0) "\n另有 ${state.deferredCount} 词顺延到明日" else ""
                showState("今日没有到期的单词$suffix")
            }

            is ReviewUiState.Error -> showState("队列加载失败：${state.message}")

            is ReviewUiState.Answering -> {
                cardBox.visibility = View.VISIBLE
                stateBox.visibility = View.GONE
                finishedBox.visibility = View.GONE
                qualityRow.visibility = View.VISIBLE
                skipButton.visibility = View.VISIBLE

                val queue = state.queue
                val current = queue.current
                progress.progress = (queue.fraction * 100).toInt()
                count.text = "已答 ${queue.progress} / 计划 ${queue.plannedCount}"

                if (state.deferredNotice > 0 && queue.progress == 0) {
                    deferredNotice.visibility = View.VISIBLE
                    deferredNotice.text = "今日上限 ${queue.plannedCount} 词，另有 ${state.deferredNotice} 词顺延到明日"
                } else {
                    deferredNotice.visibility = View.GONE
                }

                spelling.text = current?.word?.word?.spelling.orEmpty()
                phonetic.text = current?.word?.word?.phonetic.orEmpty()
                phonetic.visibility =
                    if (current?.word?.word?.phonetic.isNullOrBlank()) View.GONE else View.VISIBLE

                meaning.text = current?.word?.word?.meaning.orEmpty()
                meaning.visibility = if (state.revealed) View.VISIBLE else View.GONE
                hint.visibility = if (state.revealed) View.GONE else View.VISIBLE

                // 未揭示答案前禁用四档，避免瞎点污染调度数据
                qualityButtons.forEach { it.isEnabled = state.revealed }
            }

            is ReviewUiState.Finished -> {
                cardBox.visibility = View.GONE
                stateBox.visibility = View.GONE
                qualityRow.visibility = View.GONE
                skipButton.visibility = View.GONE
                deferredNotice.visibility = View.GONE
                finishedBox.visibility = View.VISIBLE

                progress.progress = 100
                count.text = "已完成"

                val s = state.stats
                val rate = if (s.answeredCount == 0) 0 else s.correctCount * 100 / s.answeredCount
                finishedStats.text = buildString {
                    append("本次 ${s.answeredCount} 词 · 答对率 ${rate}%\n")
                    append("跳过 ${s.skippedCount} 词 · 平均 ${s.averageDurationMs / 1000.0} 秒/词\n")
                    if (s.deferredCount > 0) {
                        append("另有 ${s.deferredCount} 词顺延到明日\n")
                    }
                    append("明天再来继续")
                }
            }
        }
    }

    private fun showState(text: String) {
        stateBox.visibility = View.VISIBLE
        stateText.text = text
        cardBox.visibility = View.GONE
        finishedBox.visibility = View.GONE
        qualityRow.visibility = View.GONE
        skipButton.visibility = View.GONE
        deferredNotice.visibility = View.GONE
        progress.progress = 0
        count.text = ""
    }
}
