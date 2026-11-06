package com.dancijiyi.app

import android.app.Application
import com.dancijiyi.app.di.AppContainer
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * 应用入口：初始化依赖容器，并在首次启动时写入内置示例词库。
 *
 * 种子写入放在应用级作用域里（而不是界面），这样即使首屏还没渲染完，
 * 数据也已经就绪；[com.dancijiyi.domain.repository.WordRepository.ensureSeedData] 本身幂等。
 */
class DanCiJiYiApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.applicationScope.launch {
            runCatching {
                container.repository.ensureSeedData(Instant.now(container.clock))
            }
        }
    }
}
