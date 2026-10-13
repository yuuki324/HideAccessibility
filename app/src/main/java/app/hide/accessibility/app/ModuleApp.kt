package app.hide.accessibility.app

import android.app.Application
import android.content.res.Configuration
import android.util.Log
import app.hide.accessibility.Const
import app.hide.accessibility.data.ConfigStore
import app.hide.accessibility.utils.Activation

class ModuleApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 配置的读写都要经过框架通道，先登记本地兜底句柄
        ConfigStore.attachApp(this)
        // 只用于刷新激活状态、让"管理应用"入口在未激活时给出提示；
        // 绑定时会顺带接上配置通道
        runCatching { Activation.observe { service -> Log.i(Const.TAG, "framework service: $service") } }
        Log.i(Const.TAG, "module app started, blocked=${ConfigStore.read(this).size}, channel=${ConfigStore.channelState}")
    }

    /** 当前是否深色模式（用于状态栏图标明暗） */
    fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}
