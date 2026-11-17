package com.dancijiyi.app.ui.wordlist

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dancijiyi.app.DanCiJiYiApp
import com.dancijiyi.app.R
import com.dancijiyi.app.speech.SpeakOutcome
import com.dancijiyi.app.speech.WordSpeaker
import com.dancijiyi.app.ui.detail.WordDetailActivity
import com.dancijiyi.domain.model.WordFilter
import kotlinx.coroutines.launch

/**
 * 单词列表页（RecyclerView）。
 *
 * 功能：搜索（前缀匹配，防抖 250ms）、分组筛选（全部 / 今日到期 / 新词 / 已掌握）、
 * 列表 → 详情跳转（`startActivity` 进入，返回键天然回到列表，返回栈不需要手工维护）。
 */
class WordListActivity : ComponentActivity() {

    private val requestedBookId: Long? by lazy {
        intent.getLongExtra(EXTRA_BOOK_ID, -1L).takeIf { it > 0 }
    }

    private val viewModel: WordListViewModel by viewModels {
        WordListViewModel.factory((application as DanCiJiYiApp).container, requestedBookId)
    }

    private lateinit var bookName: TextView
    private lateinit var dueCount: TextView
    private lateinit var searchInput: EditText
    private lateinit var filterGroup: RadioGroup
    private lateinit var wordList: RecyclerView
    private lateinit var stateBox: View
    private lateinit var stateText: TextView
    private lateinit var stateAction: Button
    private lateinit var adapter: WordAdapter

    /**
     * 朗读器（懒加载）。
     *
     * 为什么懒加载：TTS 引擎是系统服务绑定，创建有开销；
     * 用户可能只是想翻列表，一次都不点喇叭，那就没必要提前初始化。
     * 但**初始化必须提前到 onCreate**（下面会调 initialize()），否则第一次点击会没声音。
     */
    private val speaker by lazy { WordSpeaker(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_word_list)

        bookName = findViewById(R.id.bookName)
        dueCount = findViewById(R.id.dueCount)
        searchInput = findViewById(R.id.searchInput)
        filterGroup = findViewById(R.id.filterGroup)
        wordList = findViewById(R.id.wordList)
        stateBox = findViewById(R.id.stateBox)
        stateText = findViewById(R.id.stateText)
        stateAction = findViewById(R.id.stateAction)

        // 提前创建引擎：TTS 初始化是异步的，放到第一次点击再初始化会"第一次没声音"
        speaker.initialize()

        adapter = WordAdapter(
            onClick = { wordId -> openDetail(wordId) },
            onSpeak = { item -> speakWord(item.word.spelling) },
        )
        wordList.layoutManager = LinearLayoutManager(this)
        wordList.adapter = adapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                viewModel.onKeywordChanged(s?.toString().orEmpty())
            }
        })

        filterGroup.setOnCheckedChangeListener { _, checkedId ->
            viewModel.onFilterChanged(
                when (checkedId) {
                    R.id.filterDue -> WordFilter.DUE_TODAY
                    R.id.filterNew -> WordFilter.NEW
                    R.id.filterMastered -> WordFilter.MASTERED
                    else -> WordFilter.ALL
                },
            )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { render(it) }
            }
        }
    }

    private fun openDetail(wordId: Long) {
        startActivity(
            Intent(this, WordDetailActivity::class.java)
                .putExtra(WordDetailActivity.EXTRA_WORD_ID, wordId),
        )
    }

    /**
     * 朗读并给出必要的降级提示。
     *
     * 为什么要判断返回值：设备缺英文语音包时 `speak()` 不会抛异常，只会"没有声音"，
     * 用户会以为功能坏了。这里把三种失败都翻译成人话。
     */
    private fun speakWord(text: String) {
        when (val outcome = speaker.speak(text)) {
            SpeakOutcome.SPOKEN, SpeakOutcome.INITIALIZING -> Unit
            SpeakOutcome.NO_ENGLISH_VOICE ->
                Toast.makeText(this, "设备缺少英文语音包，请在系统设置里安装", Toast.LENGTH_SHORT).show()
            SpeakOutcome.ENGINE_UNAVAILABLE ->
                Toast.makeText(this, "朗读服务不可用，请检查系统的文字转语音设置", Toast.LENGTH_SHORT).show()
            SpeakOutcome.EMPTY_WORD ->
                Toast.makeText(this, "没有可朗读的内容", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 必须释放：TTS 持有系统服务连接，不 shutdown 会泄漏（Activity 反复进出会越积越多）
        speaker.shutdown()
    }

    private fun render(state: WordListUiState) {
        when (state) {
            WordListUiState.Loading -> showState("加载中…")
            WordListUiState.NoBook -> showState("还没有词库\n请先创建词库或导入 CSV")
            is WordListUiState.Error -> showState("加载失败：${state.message}")

            is WordListUiState.EmptyBook -> {
                bookName.text = state.bookName
                dueCount.text = ""
                showState("「${state.bookName}」还没有单词\n可以回上一页导入 CSV")
            }

            is WordListUiState.FilteredEmpty -> {
                bookName.text = state.bookName
                dueCount.text = ""
                val hint = if (state.keyword.isBlank()) "当前筛选条件下没有单词" else "没有匹配「${state.keyword}」的单词"
                showState(hint)
            }

            is WordListUiState.Content -> {
                bookName.text = state.bookName
                dueCount.text = "共 ${state.total} 词 · 今日到期 ${state.dueCount}"
                stateBox.visibility = View.GONE
                wordList.visibility = View.VISIBLE
                adapter.submitList(state.words)
            }
        }
    }

    private fun showState(text: String) {
        stateBox.visibility = View.VISIBLE
        wordList.visibility = View.GONE
        stateText.text = text
    }

    companion object {
        const val EXTRA_BOOK_ID = "extra_book_id"
    }
}
