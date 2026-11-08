plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.dancijiyi.widget"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 小组件与主 App 共享同一个数据层（同一 Room 实例、同一库文件）
    implementation(project(":core-data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // 跨天归零：每日 00:05 定时刷新
    implementation(libs.androidx.work.runtime.ktx)
}
