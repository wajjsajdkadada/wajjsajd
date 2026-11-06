package com.dancijiyi.app

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dancijiyi.app.ui.demo.DemoUiState
import com.dancijiyi.app.ui.demo.DemoViewModel
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
