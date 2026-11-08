package com.dancijiyi.app

import android.app.Application
import android.util.Log
import com.dancijiyi.app.di.AppContainer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

/**
 * 应用入口：初始化依赖容器、写入内置示例词库、安装全局异常兜底。
 *
 * 崩溃兜底策略（两层）：
 * 1. **协程层**：[CoroutineExceptionHandler] 捕获应用级作用域里漏出的异常，写日志而不崩进程；
 * 2. **进程层**：[Thread.setDefaultUncaughtExceptionHandler] 落一份 `crash.log` 再交回系统，
 *    下次启动时由 MainActivity 读取并提示「上次异常退出」，附最近一条堆栈摘要。
 *
 * 为什么不做「崩溃后自动重启」：课上演示时自动重启会掩盖问题；
 * 落盘 + 下次启动提示既能留证据，也不会让用户以为程序没出错。
 */
class DanCiJiYiApp : Application() {

    lateinit var container: AppContainer
        private set

    val crashLogFile: File by lazy { File(filesDir, CRASH_LOG_NAME) }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        installUncaughtExceptionHandler()

        val safeScope = container.applicationScope
        safeScope.launch {
            runCatching {
                container.repository.ensureSeedData(Instant.now(container.clock))
            }.onFailure { Log.w(TAG, "种子数据写入失败：${it.message}") }
        }
    }

    private fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashLog(thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        val entry = buildString {
            append("=== ").append(Instant.now()).append(" ===\n")
            append("thread: ").append(thread.name).append("\n")
            append(Log.getStackTraceString(throwable)).append("\n\n")
        }
        // 只保留最近一次，避免日志无限增长
        crashLogFile.writeText(entry)
        Log.e(TAG, "已记录崩溃日志", throwable)
    }

    /** 读取上次崩溃摘要（MainActivity 启动时调用）；读完可选择清除 */
    fun readCrashLog(): String? =
        crashLogFile.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clearCrashLog() {
        runCatching { crashLogFile.delete() }
    }

    /** 协程兜底示例：需要时用这个 handler 包住容易出错的 Job */
    fun coroutineHandler(): CoroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "协程异常已兜底：${throwable.message}", throwable)
    }

    private companion object {
        const val TAG = "DanCiJiYiApp"
        const val CRASH_LOG_NAME = "crash.log"
    }
}
