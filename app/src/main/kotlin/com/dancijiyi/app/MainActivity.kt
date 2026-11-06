package com.dancijiyi.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * 工程骨架占位页（第 3 提交）。
 *
 * 仅用于验证「Gradle + AGP + Kotlin + Android SDK」这条链路能编译、能装、能起来。
 * 第 4 提交会把它替换为 Room 数据读写演示页；正式界面在第 5 提交之后由 Compose 实现。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "工程骨架 OK\ncompileSdk 37 · minSdk 26"
            textSize = 18f
            gravity = Gravity.CENTER
        }
        setContentView(tv)
    }
}
