package com.dancijiyi.app.ui.settings

import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dancijiyi.app.DanCiJiYiApp
import com.dancijiyi.app.R
import com.dancijiyi.app.di.AppContainer
import com.dancijiyi.domain.model.AlgorithmConfig
import com.dancijiyi.domain.repository.AlgorithmConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 算法参数配置入口（本阶段落地的「配置入口预留」）。
 *
 * 目前暴露两个最影响体验的参数：每日复习上限、间隔抖动开关。
 * easeFactor 上下限与阶段表暂不开放（改这些等于改算法本身，答辩时反而不利）。
 */
class AlgorithmSettingsViewModel(
    private val repository: AlgorithmConfigRepository,
) : ViewModel() {

    private val _config = MutableStateFlow(AlgorithmConfig.DEFAULT)
    val config: StateFlow<AlgorithmConfig> = _config.asStateFlow()

    private val _draft = MutableStateFlow(AlgorithmConfig.DEFAULT)
    val draft: StateFlow<AlgorithmConfig> = _draft.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeConfig().collect { config ->
                _config.value = config
                _draft.value = config
            }
        }
    }

    fun updateLimit(limit: Int) {
        _draft.value = _draft.value.copy(dailyReviewLimit = limit).sanitized()
    }

    fun updateJitter(enabled: Boolean) {
        _draft.value = _draft.value.copy(jitterEnabled = enabled)
    }

    fun save(onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { repository.saveConfig(_draft.value) }.isSuccess
            onDone(ok)
        }
    }

    fun reset() {
        viewModelScope.launch {
            runCatching { repository.resetToDefault() }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { AlgorithmSettingsViewModel(container.algorithmConfigRepository) }
        }
    }
}

class AlgorithmSettingsActivity : ComponentActivity() {

    private val viewModel: AlgorithmSettingsViewModel by viewModels {
        AlgorithmSettingsViewModel.factory((application as DanCiJiYiApp).container)
    }

    private lateinit var seekBar: SeekBar
    private lateinit var limitValue: TextView
    private lateinit var jitterSwitch: Switch
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_algorithm_settings)

        seekBar = findViewById(R.id.limitSeekBar)
        limitValue = findViewById(R.id.limitValue)
        jitterSwitch = findViewById(R.id.jitterSwitch)
        info = findViewById(R.id.algorithmInfo)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        // SeekBar 位置 → 上限 20..200，步进 10
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val limit = 20 + progress * 10
                limitValue.text = "当前：$limit 词/天（范围 20–200）"
                viewModel.updateLimit(limit)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        jitterSwitch.setOnCheckedChangeListener { _, checked ->
            viewModel.updateJitter(checked)
        }

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            viewModel.save { ok ->
                Toast.makeText(
                    this,
                    if (ok) "参数已保存，下次作答立即生效" else "保存失败",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        findViewById<Button>(R.id.resetButton).setOnClickListener {
            viewModel.reset()
            Toast.makeText(this, "已恢复默认参数", Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.draft.collect { config ->
                    val progress = ((config.dailyReviewLimit - 20) / 10).coerceIn(0, 18)
                    if (seekBar.progress != progress) seekBar.progress = progress
                    limitValue.text = "当前：${config.dailyReviewLimit} 词/天（范围 20–200）"
                    if (jitterSwitch.isChecked != config.jitterEnabled) {
                        jitterSwitch.isChecked = config.jitterEnabled
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.config.collect { config ->
                    info.text = buildString {
                        append("算法版本：").append(config.version).append("\n")
                        append("难度系数区间：").append(config.easeFloor).append(" – ").append(config.easeCeiling).append("\n")
                        append("阶段间隔（分钟）：5 / 30 / 720 / 1440 / 2880 / 5760 / 10080 / 21600 / 43200 / 86400\n")
                        append("反馈档位：不认识 → 回退 2 档；模糊 → 原地加固；认识 → +1；熟练 → +2\n")
                        append("掌握判定：阶段 ≥ 8 且遗忘 ≤ 2 次")
                    }
                }
            }
        }
    }
}
