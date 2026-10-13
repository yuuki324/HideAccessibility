@file:Suppress("unused")

package app.hide.accessibility

/**
 * 全局常量。
 */
object Const {

    /** 模块包名 */
    const val MODULE_PACKAGE = "app.hide.accessibility"

    /**
     * 配置组名。
     *
     * 被注入 system_server 的一侧用 `XposedService.getRemotePreferences(CONFIG_PREFS_NAME)` 读写；
     * App 侧也必须用同一个组名走框架通道，否则读写的会是两个不同的文件。
     *
     * 模块数据目录下还有一个同名兜底文件（见 [LEGACY_PREFS_NAME]），
     * 仅在框架通道不可用时写入本地，system_server 读不到它。
     */
    const val CONFIG_PREFS_NAME = "config"

    /** 本地兜底 prefs 文件名，等价于 `Context.getSharedPreferences` 的默认名 */
    const val LEGACY_PREFS_NAME = "${MODULE_PACKAGE}_preferences"

    /** 隐藏列表在 prefs 里的键名 */
    const val KEY_BLOCK_APPS = "_block_apps"

    /** 日志 TAG */
    const val TAG = "HideAccessibility"
}
