package com.dancijiyi.app.ui.imports

import android.os.Environment
import java.io.File

/**
 * 旧系统兼容通道：列出「下载」目录下的 CSV 文件。
 *
 * 仅在 API ≤ 32 且已获得存储权限时可用（见 [com.dancijiyi.app.perm.StoragePermission]）。
 * 用 `@Suppress("DEPRECATION")` 是因为 `getExternalStoragePublicDirectory` 在 33+ 已废弃，
 * 而这条通道本身就只服务于旧系统。
 */
object LegacyCsvScanner {

    @Suppress("DEPRECATION")
    fun listDownloadCsv(): List<File> {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() || !dir.canRead()) return emptyList()
        return dir.listFiles { file ->
            file.isFile && file.extension.equals("csv", ignoreCase = true)
        }?.sortedBy { it.name.lowercase() }.orEmpty()
    }
}
