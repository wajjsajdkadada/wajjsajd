plugins {
    // AGP 9 内置 Kotlin 支持，无需（也不允许）再 apply kotlin-android 插件
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.dancijiyi.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dancijiyi.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core-data"))
    // 桌面小组件模块：library 的 manifest（receiver 声明）必须被 app 依赖才会合并进 APK
    implementation(project(":widget"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
}
