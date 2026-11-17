package com.dancijiyi.app

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dancijiyi.app.notify.ReminderNotifier
import com.dancijiyi.app.notify.ReminderScheduler
import com.dancijiyi.app.ui.imports.ImportActivity
import com.dancijiyi.app.ui.review.ReviewActivity
import com.dancijiyi.app.ui.settings.AlgorithmSettingsActivity
import com.dancijiyi.app.ui.wordlist.WordListActivity
import com.dancijiyi.domain.model.WordDraft
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime

/**
 * 首页（第 17 轮 UI 重做）。
 *
 * 页面结构（自上而下四层，形成明确视觉层级）：
 * 1. **渐变头部**：问候语 + 今日待复习大数字 + 词库概况 + 小组件状态胶囊；
 * 2. **统计三卡**：总词数 / 今日到期 / 词库数；
 * 3. **主 CTA**：整块渐变大卡片「开始复习」，副标题显示今日就绪词数与每日上限；
 * 4. **2×2 图标宫格**：单词列表 / 导入词库 / 算法参数 / 每日提醒。
 *
 * 调试功能（新建词库、写入单词、删首个、清空历史、数据自检）全部收进底部
 * 「开发者选项」，避免答辩时误触，也避免首页被调试按钮占满。
 *
 * 数据来源：直接订阅 Repository 的 Flow（`observeWordbooks` + `observeDueCount`），
 * 写库后数字会自动变，不需要手动刷新。
 */
class MainActivity : ComponentActivity() {

    private val container by lazy { (application as DanCiJiYiApp).container }

    // ---- 视图引用（命名与 activity_home.xml 的 id 一一对应） ----
    private lateinit var header: View
    private lateinit var greetingText: TextView
    private lateinit var dueCountText: TextView
    private lateinit var headerSubtitle: TextView
    private lateinit var statTotal: TextView
    private lateinit var statDue: TextView
    private lateinit var statBooks: TextView
    private lateinit var ctaCard: View
    private lateinit var ctaTitle: TextView
    private lateinit var ctaSubtitle: TextView
    private lateinit var navListSub: TextView
    private lateinit var navImportSub: TextView
    private lateinit var navSettingsSub: TextView
    private lateinit var navReminderSub: TextView
    private lateinit var navReminderIcon: ImageView
    private lateinit var statsRow: View
    private lateinit var navRow2: View

    /** 上一次显示的到期数，用于数字滚动动画（避免每次都从 0 滚） */
    private var lastDueShown = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        bindViews()
        setupClicks()

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

        playIntroAnimation()
        observeHomeStats()
        refreshReminderUi()
    }

    override fun onResume() {
        super.onResume()
        // 从复习页/导入页返回时，提醒状态可能已变（用户在别的页面开关过）
        refreshReminderUi()
    }

    // ---------------- 视图绑定 ----------------

    private fun bindViews() {
        header = findViewById(R.id.header)
        greetingText = findViewById(R.id.greetingText)
        dueCountText = findViewById(R.id.dueCountText)
        headerSubtitle = findViewById(R.id.headerSubtitle)
        statTotal = findViewById(R.id.statTotal)
        statDue = findViewById(R.id.statDue)
        statBooks = findViewById(R.id.statBooks)
        ctaCard = findViewById(R.id.ctaCard)
        ctaTitle = findViewById(R.id.ctaTitle)
        ctaSubtitle = findViewById(R.id.ctaSubtitle)
        navListSub = findViewById(R.id.navListSub)
        navImportSub = findViewById(R.id.navImportSub)
        navSettingsSub = findViewById(R.id.navSettingsSub)
        navReminderSub = findViewById(R.id.navReminderSub)
        navReminderIcon = findViewById(R.id.navReminderIcon)
        statsRow = findViewById(R.id.statsRow)
        navRow2 = findViewById(R.id.navRow2)

        // 问候语按当前时间变化：让页面看起来"活着"，成本只有一个 when
        greetingText.text = when (LocalTime.now().hour) {
            in 5..10 -> "早上好，今天也来背几个词"
            in 11..13 -> "中午好，碎片时间刷一轮"
            in 14..18 -> "下午好，先把今天的任务过了"
            else -> "晚上好，睡前再过一遍更牢"
        }
    }

    private fun setupClicks() {
        // 主 CTA：直接进复习页（没有到期词时进去会看到"今日已完成"空状态）
        ctaCard.setOnClickListener {
            startActivity(Intent(this, ReviewActivity::class.java))
        }
        findViewById<View>(R.id.navList).setOnClickListener {
            startActivity(Intent(this, WordListActivity::class.java))
        }
        findViewById<View>(R.id.navImport).setOnClickListener {
            startActivity(Intent(this, ImportActivity::class.java))
        }
        findViewById<View>(R.id.navSettings).setOnClickListener {
            startActivity(Intent(this, AlgorithmSettingsActivity::class.java))
        }
        findViewById<View>(R.id.navReminder).setOnClickListener {
            if (ReminderScheduler.isEnabled(this)) {
                ReminderScheduler.cancel(this)
                Toast.makeText(this, "已关闭每日提醒", Toast.LENGTH_SHORT).show()
                refreshReminderUi()
            } else {
                onEnableReminderClick()
            }
        }
        findViewById<View>(R.id.devEntry).setOnClickListener { showDevPanel() }
    }

    // ---------------- 数据订阅 ----------------

    /** 首页需要的四项数据（一次组合出来，避免多次重组界面） */
    private data class HomeStats(
        val totalWords: Int,
        val dueCount: Int,
        val bookCount: Int,
        val dailyLimit: Int,
    )

    private fun observeHomeStats() {
        // 用「进入页面那一刻」当作到期判定基准；页面在前台时实时性足够
        val now = Instant.now(container.clock)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    container.repository.observeWordbooks(now),
                    container.repository.observeDueCount(now),
                ) { books, due ->
                    HomeStats(
                        totalWords = books.sumOf { it.wordCount },
                        dueCount = due,
                        bookCount = books.size,
                        // 每日上限来自算法参数（设置页可改），读失败就退回默认 50
                        dailyLimit = runCatching {
                            container.algorithmConfigRepository.loadConfig().dailyReviewLimit
                        }.getOrDefault(50),
                    )
                }.collect { renderHome(it) }
            }
        }
    }

    private fun renderHome(stats: HomeStats) {
        // 大数字做滚动动画：从上次的值滚到新值，比直接 setText 更有反馈感
        animateCount(dueCountText, stats.dueCount)
        statTotal.text = stats.totalWords.toString()
        statDue.text = stats.dueCount.toString()
        statBooks.text = stats.bookCount.toString()

        headerSubtitle.text = "共 ${stats.totalWords} 词 · ${stats.bookCount} 个词库"
        navListSub.text = "${stats.totalWords} 词"
        navSettingsSub.text = "每日上限 ${stats.dailyLimit}"

        val ready = minOf(stats.dueCount, stats.dailyLimit)
        if (stats.dueCount == 0) {
            ctaTitle.text = "今日已完成"
            ctaSubtitle.text = "明天继续保持 · 小组件也会同步归零"
        } else {
            ctaTitle.text = "开始复习"
            ctaSubtitle.text = "今日 $ready 词就绪 · 上限 ${stats.dailyLimit}"
        }
    }

    /**
     * 数字滚动动画。
     *
     * 为什么不用 ObjectAnimator.ofInt(view, "text", ...)：TextView 没有可动画的 text 属性，
     * 用 ValueAnimator + 监听器把插值结果写进 text 最直接。
     */
    private fun animateCount(target: TextView, to: Int) {
        val from = if (lastDueShown < 0) 0 else lastDueShown
        lastDueShown = to
        if (from == to) {
            target.text = to.toString()
            return
        }
        ValueAnimator.ofInt(from, to).apply {
            duration = 480L
            addUpdateListener { target.text = it.animatedValue.toString() }
            start()
        }
    }

    // ---------------- 入场动画 ----------------

    /**
     * 首屏入场：头部上浮淡入，其余区块依次延迟淡入。
     *
     * 用 ViewPropertyAnimator 而不是 XML animation：代码里能看到"谁在第几毫秒动"，
     * 调顺序比改 XML 资源直观。
     */
    private fun playIntroAnimation() {
        header.alpha = 0f
        header.translationY = -dp(12).toFloat()
        header.animate().alpha(1f).translationY(0f).setDuration(300L).start()

        listOf(statsRow, ctaCard, navRow2).forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = dp(20).toFloat()
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(90L * (index + 1))
                .setDuration(320L)
                .start()
        }
    }

    // ---------------- 每日提醒 ----------------

    /** 宫格里的「每日提醒」文案与图标颜色随开关状态变化 */
    private fun refreshReminderUi() {
        val enabled = ReminderScheduler.isEnabled(this)
        navReminderSub.text = if (enabled) "已开启 · 21:00" else "未开启 · 点击设置"
        // 关闭时把铃铛调灰，状态一眼可辨（比只改文字更直观）
        navReminderIcon.alpha = if (enabled) 1f else 0.4f
    }

    private val requestNotification =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                ReminderScheduler.schedule(this)
                Toast.makeText(this, "已开启每日 21:00 提醒", Toast.LENGTH_SHORT).show()
                refreshReminderUi()
            } else {
                showReminderDeniedDialog()
            }
        }

    private fun onEnableReminderClick() {
        if (ReminderNotifier.hasPermission(this)) {
            ReminderScheduler.schedule(this)
            Toast.makeText(this, "已开启每日 21:00 提醒", Toast.LENGTH_SHORT).show()
            refreshReminderUi()
            return
        }
        if (shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("需要通知权限")
                .setMessage(
                    "用途：每天 21:00 提醒你还有多少单词要复习。\n\n" +
                        "不给这个权限也能用——桌面小组件会一直显示今日待复习数量。",
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
                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName),
                )
            }
            .show()
    }

    // ---------------- 开发者选项（调试入口） ----------------

    /**
     * 调试面板：把原来占满首页的 5 个调试按钮收进一个弹窗。
     *
     * 收起来的理由：首页是给"用户"看的，不是给"验收"看的；
     * 答辩演示时误点「清空复习历史」会直接毁掉现场数据。
     */
    private fun showDevPanel() {
        val items = arrayOf(
            "新建词库",
            "手动写入单词",
            "删除第一个单词",
            "清空复习历史",
            "小组件数据自检",
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("开发者选项")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> askText("新建词库", "词库名") { name ->
                        runAction(success = "已新建词库：$name") {
                            container.repository.createWordbook(name)
                        }
                    }

                    1 -> askWord()
                    2 -> runAction(success = "已删除 1 条记录") {
                        val book = container.repository.observeWordbooks(Instant.now(container.clock))
                            .first().firstOrNull()
                            ?: error("还没有词库")
                        val word = container.repository.observeWords(book.wordbook.id).first().firstOrNull()
                            ?: error("该词库还没有单词")
                        container.repository.deleteWord(word.word.id)
                    }

                    3 -> runAction(success = "已清空复习历史") {
                        container.repository.clearReviewHistory()
                    }

                    4 -> runWidgetConsistencyCheck()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 单输入框弹窗（新建词库用） */
    private fun askText(title: String, hint: String, onConfirm: (String) -> Unit) {
        val input = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(wrapper)
            .setPositiveButton("确定") { _, _ -> onConfirm(input.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 双输入框弹窗（手动写入单词用） */
    private fun askWord() {
        val spelling = EditText(this).apply {
            hint = "单词（必填）"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val meaning = EditText(this).apply {
            hint = "释义（必填）"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(spelling)
            addView(meaning)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("手动写入单词")
            .setView(wrapper)
            .setPositiveButton("写入") { _, _ ->
                runAction(success = "已写入：${spelling.text}") {
                    val book = container.repository.observeWordbooks(Instant.now(container.clock))
                        .first().firstOrNull()
                        ?: container.repository.createWordbook("我的词库").let { id ->
                            // createWordbook 返回新 id，这里再查一次拿到完整对象
                            container.repository.observeWordbooks(Instant.now(container.clock))
                                .first().first { it.wordbook.id == id }
                        }
                    container.repository.addWord(
                        book.wordbook.id,
                        WordDraft(
                            spelling = spelling.text.toString().trim(),
                            meaning = meaning.text.toString().trim(),
                        ),
                        Instant.now(container.clock),
                    )
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 统一执行调试动作：成功/失败都给出 Toast，避免"点了没反应" */
    private fun runAction(success: String, block: suspend () -> Unit) {
        lifecycleScope.launch {
            runCatching { block() }
                .onSuccess { Toast.makeText(this@MainActivity, success, Toast.LENGTH_SHORT).show() }
                .onFailure {
                    Toast.makeText(
                        this@MainActivity,
                        "失败：${it.message}",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
        }
    }

    /**
     * 小组件数据一致性自检。
     *
     * 组件侧走 `observeDueCount`（全库到期数），列表页走「各词库 dueCount 求和」。
     * 两条查询路径不同、但读的是同一个 Room 实例与同一份 db，结果必须完全相等。
     */
    private fun runWidgetConsistencyCheck() {
        lifecycleScope.launch {
            try {
                val now = Instant.now(container.clock)
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
