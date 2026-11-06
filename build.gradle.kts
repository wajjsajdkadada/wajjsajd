// 根构建脚本：只声明插件版本，不应用（各模块自行 apply）
// 注意：AGP 9 起 Kotlin 支持已内置，Android 模块**不可**再 apply org.jetbrains.kotlin.android，
//      否则报 "The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9"。
//      纯 JVM 模块（:core-domain）仍使用 kotlin-jvm。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// 一键清理所有模块构建产物（gradlew cleanAll）
tasks.register<Delete>("cleanAll") {
    delete(rootProject.layout.buildDirectory)
    subprojects.forEach { delete(it.layout.buildDirectory) }
}
