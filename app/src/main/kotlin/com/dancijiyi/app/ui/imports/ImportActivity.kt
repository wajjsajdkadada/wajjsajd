package com.dancijiyi.app.ui.imports

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dancijiyi.app.DanCiJiYiApp
import com.dancijiyi.app.perm.StoragePermission
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.util.Locale

/**
 * 词库导入页。
 *
 * 权限说明（重要）：**本页不申请任何存储权限**。文件通过 SAF（`ACTION_OPEN_DOCUMENT`）选取，
 * 由系统文件选择器授予单次读取授权，`ContentResolver` 直接打开流。
 * 这样做的收益：少一条「权限被拒」的降级路径，且 Android 10+ 分区存储下依然可用（docs/01 §8）。
 *
 * 页面按四段切换：待选 → 已就绪 → 导入中 → 结果（成功 / 失败）。
 */
class ImportActivity : ComponentActivity() {

    private val viewModel: ImportViewModel by viewModels {
        ImportViewModel.factory((application as DanCiJiYiApp).container)
    }

    private lateinit var bookSpinner: Spinner
    private lateinit var fileInfo: TextView
    private lateinit var startButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var cancelButton: Button
    private lateinit var resultText: TextView
    private lateinit var failureText: TextView
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button
    private lateinit var againButton: Button
    private lateinit var hintText: TextView

    private var spinnerBound = false

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) handlePicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    // ---------------- 文件选择 ----------------

    private fun handlePicked(uri: Uri) {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "未命名文件"
        var size = -1L
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) cursor.getString(nameIdx)?.let { name = it }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        }
        // 用 applicationContext 的 resolver，lambda 被 ViewModel 缓存也不会泄漏 Activity
        val resolver = applicationContext.contentResolver
        viewModel.onFilePicked(name, size) { resolver.openInputStream(uri) }
    }

    private fun importSample() {
        val assets = applicationContext.assets
        viewModel.onFilePicked("template.csv（内置示例）", -1L) { assets.open("template.csv") }
    }

    // ---------------- 存储权限（旧系统兼容通道） ----------------

    private val requestStorage =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showDownloadCsvPicker()
            } else {
                showPermissionDeniedDialog()
            }
        }

    private fun onLegacyImportClick() {
        if (!StoragePermission.isLegacyChannelAvailable()) {
            Toast.makeText(this, "当前系统请用「选择 CSV 文件」，不需要存储权限", Toast.LENGTH_SHORT).show()
            return
        }
        when {
            StoragePermission.isGranted(this) -> showDownloadCsvPicker()

            // 用户之前拒绝过 → 必须先解释「为什么要这个权限」，再申请
            shouldShowRequestPermissionRationale(StoragePermission.permission) -> showRationaleDialog()

            else -> requestStorage.launch(StoragePermission.permission)
        }
    }

    /** rationale：说清「为什么需要」＋「不给也能用」，而不是硬要权限 */
    private fun showRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要存储权限")
            .setMessage(
                "用途：列出「下载」目录里的 CSV 词库文件，直接读取。\n\n" +
                    "你可以不给这个权限——改用系统文件选择器导入，或者手动逐个添加单词，功能同样能用。",
            )
            .setPositiveButton("去授权") { _, _ -> requestStorage.launch(StoragePermission.permission) }
            .setNegativeButton("用系统选择器") { _, _ -> openDocument.launch(OPEN_MIME) }
            .setNeutralButton("手动添加") { _, _ ->
                ManualWordDialog.show(this) { viewModel.addManualWord(it) }
            }
            .show()
    }

    /** 拒绝后的降级：给两条不需要权限的路 */
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("没有读取权限")
            .setMessage(
                "无法列出「下载」目录里的文件。以下两种方式都不需要权限：\n" +
                    "· 用系统文件选择器导入 CSV\n" +
                    "· 手动逐个添加单词",
            )
            .setPositiveButton("手动添加") { _, _ ->
                ManualWordDialog.show(this) { viewModel.addManualWord(it) }
            }
            .setNeutralButton("用系统选择器") { _, _ -> openDocument.launch(OPEN_MIME) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDownloadCsvPicker() {
        val files = LegacyCsvScanner.listDownloadCsv()
        if (files.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("下载目录里没有 CSV 文件")
                .setMessage("可以先用「选择 CSV 文件」，或手动添加单词。")
                .setPositiveButton("手动添加") { _, _ ->
                    ManualWordDialog.show(this) { viewModel.addManualWord(it) }
                }
                .setNegativeButton("知道了", null)
                .show()
            return
        }
        val labels = files.map { "${it.name}（${it.length() / 1024} KB）" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择下载目录里的 CSV")
            .setItems(labels) { _, which ->
                val file = files[which]
                viewModel.onFilePicked(file.name, file.length()) { FileInputStream(file) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- UI ----------------

    private fun buildContentView(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "导入词库（CSV）"
            textSize = 20f
        })

        root.addView(TextView(this).apply {
            text = "无需存储权限：文件由系统选择器授权读取"
            textSize = 12f
            setPadding(0, dp(4), 0, dp(12))
        })

        root.addView(TextView(this).apply { text = "导入到词库" })
        bookSpinner = Spinner(this)
        root.addView(bookSpinner)

        val pickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(8))
        }
        pickRow.addView(Button(this).apply {
            text = "选择 CSV 文件"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                openDocument.launch(arrayOf("text/*", "application/csv", "application/octet-stream", "*/*"))
            }
        })
        pickRow.addView(Button(this).apply {
            text = "用示例文件"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { importSample() }
        })
        root.addView(pickRow)

        // 旧系统兼容通道（API ≤ 32 才显示）+ 权限被拒后的降级入口
        if (StoragePermission.isLegacyChannelAvailable()) {
            root.addView(Button(this).apply {
                text = "从下载目录读取（需存储权限）"
                setOnClickListener { onLegacyImportClick() }
            })
        }

        root.addView(Button(this).apply {
            text = "手动添加单词（无需任何权限）"
            setOnClickListener {
                ManualWordDialog.show(this@ImportActivity) { draft ->
                    viewModel.addManualWord(draft)
                }
            }
        })

        hintText = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(hintText)

        fileInfo = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(fileInfo)

        startButton = Button(this).apply {
            text = "开始导入"
            setOnClickListener { viewModel.startImport() }
        }
        root.addView(startButton)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        root.addView(progressBar)

        progressText = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(progressText)

        cancelButton = Button(this).apply {
            text = "取消导入"
            setOnClickListener { viewModel.cancelImport() }
        }
        root.addView(cancelButton)

        resultText = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(12), 0, dp(8))
        }
        root.addView(resultText)

        failureText = TextView(this).apply {
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(failureText)

        val resultRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        againButton = Button(this).apply {
            text = "再导一个"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { viewModel.reset() }
        }
        resultRow.addView(againButton)
        resultRow.addView(Button(this).apply {
            text = "返回"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { finish() }
        })
        root.addView(resultRow)

        errorText = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(12), 0, dp(8))
        }
        root.addView(errorText)

        retryButton = Button(this).apply {
            text = "重试"
            setOnClickListener { viewModel.retry() }
        }
        root.addView(retryButton)

        return ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun render(state: ImportUiState) {
        // 词库下拉：仅在数据变化时重建，避免每次刷新都把用户的选择重置
        if (!spinnerBound && state.books.isNotEmpty()) {
            bookSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                state.books.map { it.wordbook.name },
            )
            bookSpinner.onItemSelectedListener = SimpleItemSelected { position ->
                state.books.getOrNull(position)?.let { viewModel.selectBook(it.wordbook.id) }
            }
            spinnerBound = true
        }

        val idle = state.phase == ImportUiState.Phase.IDLE
        val ready = state.phase == ImportUiState.Phase.READY
        val importing = state.phase == ImportUiState.Phase.IMPORTING
        val done = state.phase == ImportUiState.Phase.DONE
        val error = state.phase == ImportUiState.Phase.ERROR

        fileInfo.visibility = if (ready || done) View.VISIBLE else View.GONE
        fileInfo.text = state.fileName?.let {
            "已选文件：$it" + if (state.fileSizeBytes > 0) "（${formatSize(state.fileSizeBytes)}）" else ""
        }.orEmpty()

        startButton.visibility = if (ready) View.VISIBLE else View.GONE
        progressBar.visibility = if (importing) View.VISIBLE else View.GONE
        progressText.visibility = if (importing) View.VISIBLE else View.GONE
        cancelButton.visibility = if (importing) View.VISIBLE else View.GONE
        resultText.visibility = if (done) View.VISIBLE else View.GONE
        failureText.visibility = if (done && state.report?.failedRows?.isNotEmpty() == true) View.VISIBLE else View.GONE
        againButton.visibility = if (done || error) View.VISIBLE else View.GONE
        errorText.visibility = if (error) View.VISIBLE else View.GONE
        retryButton.visibility = if (error) View.VISIBLE else View.GONE

        hintText.text = state.hint ?: when {
            idle && state.books.isEmpty() -> "还没有词库，请先回上一页新建一个"
            idle -> "支持的列：word, meaning（必需）；phonetic, example, example_translation（可选）"
            else -> ""
        }

        if (importing) {
            val p = state.progress
            val fraction = p?.fraction
            if (fraction == null) {
                progressBar.isIndeterminate = true
            } else {
                progressBar.isIndeterminate = false
                progressBar.progress = (fraction * 100).toInt()
            }
            progressText.text = buildString {
                append("正在导入…")
                p?.let {
                    append("  已处理 ")
                    append(it.processed)
                    append(" 行")
                    append("  新增 ")
                    append(it.inserted)
                    if (it.failed > 0) {
                        append("  失败 ")
                        append(it.failed)
                    }
                }
            }
        }

        if (done) {
            val r = state.report
            resultText.text = buildString {
                append("导入完成（")
                append(r?.status?.name ?: "")
                append("）\n")
                append("总行数 ")
                append(r?.total ?: 0)
                append("  新增 ")
                append(r?.inserted ?: 0)
                append("  更新 ")
                append(r?.updated ?: 0)
                append("  跳过 ")
                append(r?.skipped ?: 0)
                append("  失败 ")
                append(r?.failed ?: 0)
            }
            if (r != null && r.failedRows.isNotEmpty()) {
                failureText.text = buildString {
                    append("失败明细（前 ")
                    append(r.failedRows.size)
                    append(" 条）：\n")
                    r.failedRows.forEach { row ->
                        append("第 ")
                        append(row.line)
                        append(" 行：")
                        append(row.reason)
                        append("\n")
                    }
                }
            }
        }

        if (error) {
            errorText.text = buildString {
                append(state.errorTitle ?: "导入失败")
                append("\n")
                append(state.errorDetail ?: "")
            }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.CHINA, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format(Locale.CHINA, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** SAF 的 MIME 过滤：宁可放宽，再靠扩展名与内容嗅探校验 */
        val OPEN_MIME = arrayOf(
            "text/*",
            "application/csv",
            "text/plain",
            "application/octet-stream",
            "*/*",
        )
    }

    /** 避免匿名类噪音的小工具 */
    private class SimpleItemSelected(
        private val onSelected: (Int) -> Unit,
    ) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
            onSelected(position)
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }
}
