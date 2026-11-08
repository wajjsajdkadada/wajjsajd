package com.dancijiyi.app.perm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 存储权限判定与「是否需要这个权限」的集中判断。
 *
 * 背景（重要）：本项目主导入路径用 SAF（ACTION_OPEN_DOCUMENT），**不需要**存储权限。
 * 只有「从下载目录直接读取」这条兼容通道才需要它：
 * - Android 12 及以下（API ≤ 32）：`READ_EXTERNAL_STORAGE` 真实生效，可列目录；
 * - Android 13+（API 33+）：该权限已被拆分且对普通 CSV 无意义，入口直接隐藏，
 *   manifest 里也用 `maxSdkVersion="32"` 限定，避免在 33+ 上申请一个无效权限。
 */
object StoragePermission {

    /** 旧系统兼容通道可用的最高 API（33 起走 SAF，不再申请） */
    private const val LEGACY_MAX_SDK = Build.VERSION_CODES.S_V2 // 32

    val permission: String = Manifest.permission.READ_EXTERNAL_STORAGE

    /** 当前设备是否适用「旧系统兼容通道」 */
    fun isLegacyChannelAvailable(): Boolean = Build.VERSION.SDK_INT <= LEGACY_MAX_SDK

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
