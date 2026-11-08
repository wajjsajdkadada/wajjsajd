package com.dancijiyi.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.dancijiyi.app.notify.ReminderNotifier
import com.dancijiyi.app.notify.ReminderScheduler
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dancijiyi.app.ui.settings.AlgorithmSettingsActivity
import com.dancijiyi.app.ui.review.ReviewActivity
import com.dancijiyi.app.ui.wordlist.WordListActivity
import com.dancijiyi.app.ui.imports.ImportActivity
import com.dancijiyi.app.ui.demo.DemoUiState
import com.dancijiyi.app.ui.demo.DemoViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Room 数据层演示页（第 4 提交）。
 *
 * 目的：把「增 / 删 / 查」三条路径都点到，证明数据确实落了库、且读回来是同一份。
 * 操作步骤（验收用）：
 * 1. 启动后应自动出现内置示例词库的 12 个词（首次启动写入）；
 * 2. 输入单词 + 释义 → 点「写入」→ 列表顶部立即出现该词（响应式，无需手动刷新）；
 * 3. 杀掉进程重进 → 词仍在（证明落库，不是内存态）；
 * 4. 点「删首个」→ 该词消失；点「清空历史」→ 提示成功。
 *
 * 注意：本页刻意用最朴素的 View 搭建，只为验证数据链路；
 * 正式界面在第 5 提交之后用 Compose 重做（见 docs/04）。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: DemoViewModel by viewModels {
        DemoViewModel.factory((application as DanCiJiYiApp).container)
    }

    private lateinit var bookInput: EditText
    private lateinit var wordInput: EditText
    private lateinit var meaningInput: EditText
    private lateinit var statusView: TextView
    private lateinit var listView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())

        // 崩溃兜底：上次异常退出时给用户一个可见的交代，并附最近一条堆栈
        (application as DanCiJiYiApp).readCrashLog()?.let { report ->
            android.app.AlertDialog.Builder(this)
                .setTitle("上次运行异常退出")
                .setMessage("已记录崩溃日志，可反馈给开发者：\n\n" + report.takeLast(1200))
                .setPositiveButton("知道了") { _, _ ->
                    (application as DanCiJiYiApp).clearCrashLog()
                }
                .setCancelable(false)
                .show()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { render(it) }
            }
        }
    }

    // ---------------- UI ----------------

    private fun buildContentView(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Room 持久化演示"
            textSize = 20f
            setPadding(0, 0, 0, dp(12))
        })

        // 新建词库
        val bookRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bookInput = EditText(this).apply {
            hint = "新词库名"
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bookRow.addView(bookInput)
        bookRow.addView(Button(this).apply {
            text = "新建词库"
            setOnClickListener {
                viewModel.createBook(bookInput.text.toString())
                bookInput.setText("")
            }
        })
        root.addView(bookRow)

        // 写入单词
        val wordRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        wordInput = EditText(this).apply {
            hint = "单词"
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        meaningInput = EditText(this).apply {
            hint = "释义"
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        wordRow.addView(wordInput)
        wordRow.addView(meaningInput)
        root.addView(wordRow)

        root.addView(Button(this).apply {
            text = "写入数据库"
            setOnClickListener {
                viewModel.addWord(wordInput.text.toString(), meaningInput.text.toString())
                wordInput.setText("")
                meaningInput.setText("")
            }
        })

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(12), 0, dp(8))
        }
        root.addView(statusView)

        listView = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, 0)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            addView(listView)
        }
        root.addView(scroll)

        root.addView(Button(this).apply {
            text = "开始复习（队列）"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ReviewActivity::class.java))
            }
        })

        root.addView(Button(this).apply {
            text = "单词列表（RecyclerView）"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, WordListActivity::class.java))
            }
        })

        root.addView(Button(this).apply {
            text = "开启每日提醒（21:00）"
            setOnClickListener { onEnableReminderClick() }
        })

        root.addView(Button(this).apply {
            text = "小组件数据自检"
            setOnClickListener { runWidgetConsistencyCheck() }
        })

        root.addView(Button(this).apply {
            text = "算法参数"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, AlgorithmSettingsActivity::class.java))
            }
        })

        root.addView(Button(this).apply {
            text = "导入词库（CSV）"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ImportActivity::class.java))
            }
        })

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actionRow.addView(Button(this).apply {
            text = "删首个"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { viewModel.deleteFirstWord() }
        })
        actionRow.addView(Button(this).apply {
            text = "清空复习历史"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { viewModel.clearHistory() }
        })
        root.addView(actionRow)

        return root
    }

    private fun render(state: DemoUiState) {
        when (state) {
            DemoUiState.Loading -> {
                statusView.text = "加载中…"
                listView.text = ""
            }

            DemoUiState.NoWordbook -> {
                statusView.text = "还没有词库，先在上方新建一个"
                listView.text = ""
            }

            is DemoUiState.Error -> {
                statusView.text = "出错：${state.message}"
                listView.text = ""
            }

            is DemoUiState.Content -> {
                statusView.text = buildString {
                    append("词库：")
                    append(state.bookName)
                    append("（共 ")
                    append(state.bookCount)
                    append(" 个未归档词库）\n")
                    append("本词库 ")
                    append(state.words.size)
                    append(" 词")
                    state.notice?.let {
                        append("\n")
                        append(it)
                    }
                }
                listView.text = if (state.words.isEmpty()) {
                    "（该词库还没有单词，输入一个试试）"
                } else {
                    state.words.joinToString("\n") { item ->
                        "${item.word.spelling}   ${item.state.stage} 阶   ${item.word.meaning}"
                    }
                }
            }
        }
    }

    // ---------------- 每日提醒（通知权限流程 + 降级） ----------------

    private val requestNotification =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                ReminderScheduler.schedule(this)
                Toast.makeText(this, "已开启每日 21:00 提醒", Toast.LENGTH_SHORT).show()
            } else {
                showReminderDeniedDialog()
            }
        }

    private fun onEnableReminderClick() {
        if (ReminderNotifier.hasPermission(this)) {
            ReminderScheduler.schedule(this)
            Toast.makeText(this, "已开启每日 21:00 提醒", Toast.LENGTH_SHORT).show()
            return
        }
        if (shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("需要通知权限")
                .setMessage(
                    "用途：每天 21:00 提醒你还有多少单词要复习。\n\n" +
                        "不给这个权限也能用——桌面小组件会一直显示今日待复习数量，" +
                        "任务照常会自动排好。",
                )
                .setPositiveButton("去授权") { _, _ ->
                    requestNotification.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                .setNegativeButton("暂不开启", null)
                .show()
        } else {
            requestNotification.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showReminderDeniedDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("提醒未开启")
            .setMessage(
                "没有通知权限，每日提醒不会弹出。\n\n" +
                    "降级方案：把「今日待复习」小组件放到桌面，效果一样——" +
                    "随时能看到还剩多少词，点一下就能开始复习。",
            )
            .setPositiveButton("知道了", null)
            .setNegativeButton("去系统设置") { _, _ ->
                startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName),
                )
            }
            .show()
    }

    /**
     * 小组件数据一致性自检。
     *
     * 组件侧走 `observeDueCount`（全库到期数），列表页走「各词库 dueCount 求和」。
     * 两条查询路径不同、但读的是同一个 Room 实例与同一份 db，结果必须完全相等——
     * 这是验证「组件与主 App 数据一致」最直接的办法。
     */
    private fun runWidgetConsistencyCheck() {
        val container = (application as DanCiJiYiApp).container
        lifecycleScope.launch {
            try {
                val now = java.time.Instant.now(container.clock)
                val widgetCount = container.repository.observeDueCount(now).first()
                val listSum = container.repository.observeWordbooks(now).first().sumOf { it.dueCount }
                val same = widgetCount == listSum
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle(if (same) "数据一致 ✓" else "数据不一致 ✗")
                    .setMessage(
                        "小组件读取（observeDueCount）：$widgetCount 词\n" +
                            "列表页合计（各词库 dueCount 求和）：$listSum 词\n\n" +
                            "两者走同一 Room 实例与同一份 db 文件，理论上必然相等。",
                    )
                    .setPositiveButton("知道了", null)
                    .show()
            } catch (e: Exception) {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("自检失败")
                    .setMessage(e.message ?: e::class.java.simpleName)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
