package com.dancijiyi.app.ui.review

import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
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
import com.dancijiyi.domain.repository.SessionStats
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * 复习交互页（卡片式）。
 *
 * 交互：
 * - **点击卡片** 或 **左右滑动** → 在「正面（单词）」与「背面（释义）」之间翻转；
 * - 三档反馈：不认识 / 模糊 / 认识 → 各自对应 FORGOT / VAGUE / RECALLED，
 *   点击后立即结算、写库、出队，队列由 Flow 自动推送下一题；
 * - 未翻到背面时反馈按钮禁用（避免不看释义就瞎点，污染调度数据）。
 *
 * 结算页展示五类数据：本次词数、答对率、平均耗时、顺延词数、**三档分布**。
 * 复习完成后队列在数据层已闭合（会话置 COMPLETED），列表/详情页靠 Flow 自动刷新。
 */
class ReviewActivity : ComponentActivity() {

    private val viewModel: ReviewViewModel by viewModels {
        ReviewViewModel.factory((application as DanCiJiYiApp).container)
    }

    private lateinit var progress: ProgressBar
    private lateinit var count: TextView
    private lateinit var deferredNotice: TextView
    private lateinit var cardBox: View
    private lateinit var cardFront: LinearLayout
    private lateinit var cardBack: LinearLayout
    private lateinit var frontSpelling: TextView
    private lateinit var frontPhonetic: TextView
    private lateinit var backSpelling: TextView
    private lateinit var backMeaning: TextView
    private lateinit var backExample: TextView
    private lateinit var backTranslation: TextView
    private lateinit var stateBox: LinearLayout
    private lateinit var stateText: TextView
    private lateinit var finishedBox: LinearLayout
    private lateinit var finishedStats: TextView
    private lateinit var finishedDistribution: TextView
    private lateinit var qualityRow: LinearLayout
    private lateinit var skipButton: Button
    private lateinit var qualityButtons: List<Button>

    private var showingBack = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_review)

        progress = findViewById(R.id.queueProgress)
        count = findViewById(R.id.queueCount)
        deferredNotice = findViewById(R.id.deferredNotice)
        cardBox = findViewById(R.id.cardBox)
        cardFront = findViewById(R.id.cardFront)
        cardBack = findViewById(R.id.cardBack)
        frontSpelling = findViewById(R.id.frontSpelling)
        frontPhonetic = findViewById(R.id.frontPhonetic)
        backSpelling = findViewById(R.id.backSpelling)
        backMeaning = findViewById(R.id.backMeaning)
        backExample = findViewById(R.id.backExample)
        backTranslation = findViewById(R.id.backTranslation)
        stateBox = findViewById(R.id.stateBox)
        stateText = findViewById(R.id.stateText)
        finishedBox = findViewById(R.id.finishedBox)
        finishedStats = findViewById(R.id.finishedStats)
        finishedDistribution = findViewById(R.id.finishedDistribution)
        qualityRow = findViewById(R.id.qualityRow)
        skipButton = findViewById(R.id.skipButton)

        val btnForgot: Button = findViewById(R.id.btnForgot)
        val btnVague: Button = findViewById(R.id.btnVague)
        val btnRecalled: Button = findViewById(R.id.btnRecalled)
        qualityButtons = listOf(btnForgot, btnVague, btnRecalled)

        // 点击 + 左右滑动：同一个手势检测器处理，避免两套事件互相打架
        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                viewModel.toggleReveal()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > abs(dy) && abs(dx) > SWIPE_THRESHOLD) {
                    viewModel.toggleReveal()
                    return true
                }
                return false
            }
        })
        cardBox.setOnTouchListener { _, event ->
            gesture.onTouchEvent(event)
            true
        }

        btnForgot.setOnClickListener { viewModel.answer(Quality.FORGOT) }
        btnVague.setOnClickListener { viewModel.answer(Quality.VAGUE) }
        btnRecalled.setOnClickListener { viewModel.answer(Quality.RECALLED) }
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

    // ---------------- 卡片翻转 ----------------

    private fun flipTo(showBack: Boolean, animate: Boolean = true) {
        if (showingBack == showBack) return
        if (!animate) {
            setFace(showBack)
            return
        }
        val target = if (showBack) 90f else -90f
        cardBox.animate().rotationY(target).setDuration(130).withEndAction {
            setFace(showBack)
            cardBox.rotationY = -target
            cardBox.animate().rotationY(0f).setDuration(130).start()
        }.start()
    }

    private fun setFace(showBack: Boolean) {
        showingBack = showBack
        cardFront.visibility = if (showBack) View.GONE else View.VISIBLE
        cardBack.visibility = if (showBack) View.VISIBLE else View.GONE
        cardBox.rotationY = 0f
    }

    // ---------------- 渲染 ----------------

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
                val current = queue.current?.word?.word

                progress.progress = (queue.fraction * 100).toInt()
                count.text = "已答 ${queue.progress} / 计划 ${queue.plannedCount}"

                if (state.deferredNotice > 0 && queue.progress == 0) {
                    deferredNotice.visibility = View.VISIBLE
                    deferredNotice.text =
                        "今日上限 ${queue.plannedCount} 词，另有 ${state.deferredNotice} 词顺延到明日"
                } else {
                    deferredNotice.visibility = View.GONE
                }

                frontSpelling.text = current?.spelling.orEmpty()
                frontPhonetic.text = current?.phonetic.orEmpty()
                frontPhonetic.visibility =
                    if (current?.phonetic.isNullOrBlank()) View.GONE else View.VISIBLE

                backSpelling.text = current?.spelling.orEmpty()
                backMeaning.text = current?.meaning.orEmpty()
                backExample.text = current?.example.orEmpty()
                backExample.visibility = if (current?.example.isNullOrBlank()) View.GONE else View.VISIBLE
                backTranslation.text = current?.exampleTranslation.orEmpty()
                backTranslation.visibility =
                    if (current?.exampleTranslation.isNullOrBlank()) View.GONE else View.VISIBLE

                flipTo(state.revealed)

                // 未揭示释义前禁用反馈，防止不看释义就瞎点
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
                renderSettlement(state.stats, state.deferredCount)
            }
        }
    }

    private fun renderSettlement(stats: SessionStats, deferredCount: Int) {
        val avgSeconds = String.format(Locale.CHINA, "%.1f", stats.averageDurationMs / 1000.0)
        finishedStats.text = buildString {
            append("本次复习 ").append(stats.answeredCount).append(" 词")
            append(" · 答对率 ").append(stats.correctRate).append("%\n")
            append("平均 ").append(avgSeconds).append(" 秒/词")
            if (stats.skippedCount > 0) {
                append(" · 跳过 ").append(stats.skippedCount).append(" 词")
            }
            if (deferredCount > 0) {
                append("\n另有 ").append(deferredCount).append(" 词顺延到明日")
            }
        }

        val forgot = stats.qualityCounts[Quality.FORGOT] ?: 0
        val vague = stats.qualityCounts[Quality.VAGUE] ?: 0
        val recalled = stats.qualityCounts[Quality.RECALLED] ?: 0
        val fluent = stats.qualityCounts[Quality.FLUENT] ?: 0
        val total = (forgot + vague + recalled + fluent).coerceAtLeast(1)

        fun bar(count: Int): String {
            val filled = (count * 10 / total).coerceIn(0, 10)
            return "█".repeat(filled) + "░".repeat(10 - filled)
        }

        finishedDistribution.text = buildString {
            append("不认识 ").append(bar(forgot)).append("  ").append(forgot).append("\n")
            append("模糊　 ").append(bar(vague)).append("  ").append(vague).append("\n")
            append("认识　 ").append(bar(recalled)).append("  ").append(recalled)
            if (fluent > 0) append("\n熟练　 ").append(bar(fluent)).append("  ").append(fluent)
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

    private companion object {
        const val SWIPE_THRESHOLD = 120f
    }
}
