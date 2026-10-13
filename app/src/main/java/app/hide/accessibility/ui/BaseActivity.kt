package app.hide.accessibility.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import app.hide.accessibility.R

/**
 * 极简基类：只做状态栏配色，不引入 androidx。
 *
 * 用 framework 的 `Theme.DeviceDefault.DayNight`（见 res/values/themes.xml），
 * 深浅色跟随系统，不需要 AppCompatDelegate。
 */
abstract class BaseActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars()
        onCreate()
    }

    /** 子类在这里 setContentView + 初始化 */
    abstract fun onCreate()

    val isNightMode: Boolean
        get() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun applySystemBars() {
        runCatching {
            val bg = if (isNightMode) R.color.bg_dark else R.color.bg
            window.statusBarColor = getColor(bg)
            window.navigationBarColor = getColor(bg)
            // 浅色背景用深色状态栏图标
            val flags = window.decorView.systemUiVisibility
            window.decorView.systemUiVisibility = if (isNightMode) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
    }
}
