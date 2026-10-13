package app.hide.accessibility.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import app.hide.accessibility.Const
import app.hide.accessibility.data.ConfigStore

/**
 * 模块入口（libxposed API 102）。
 *
 * 入口类名写在 `META-INF/xposed/java_init.list`，框架在**每个被注入的进程**都会 new 一个实例。
 *
 * 本模块只注入系统框架（作用域 = `system`，见 `META-INF/xposed/scope.list`），
 * 因为要改的是 `AccessibilityManagerService` 和 `SettingsProvider` 的 `ContentProvider.Transport`
 * ——两者都活在 system_server 里。
 *
 * 生命周期用 [onSystemServerStarting] 而不是 [onPackageLoaded]：
 * 前者在 system_server 里 `ActivityThread.systemMain()` **之前**触发，此时 framework 类都能加载，
 * 装 hook 的时机也早于任何应用来问"无障碍开了没"。
 */
class ModuleEntry : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        Log.i(Const.TAG, "module loaded: process=${param.processName} systemServer=${param.isSystemServer}")
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        Log.i(Const.TAG, "system_server starting, framework api=${apiVersion}, ${frameworkName} ${frameworkVersion}")

        // 配置通道与 hook 安装各自 try：配置读不到时仍要能报出"模块已加载 + hook 结果"
        var configNote = "ok via getRemotePreferences"
        runCatching {
            ConfigStore.listen(getRemotePreferences(Const.CONFIG_PREFS_NAME))
        }.onFailure {
            configNote = "FAILED ${it.javaClass.simpleName}: ${it.message}"
            Log.e(Const.TAG, "remote preferences unavailable: ${it.message}")
        }

        val hookResult = runCatching { FrameworkHooks(this, param.classLoader).install() }
            .onFailure { Log.e(Const.TAG, "install hooks failed", it) }
            .getOrElse { "install threw: ${it.javaClass.simpleName}: ${it.message}" }

        Diagnostics.reportInstalled(hookResult, configNote)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // 作用域只有 android，正常不会走到这里；
        // 万一用户把模块勾到了别的应用上，也不要产生任何副作用。
        Log.i(Const.TAG, "onPackageLoaded(${param.packageName}) ignored")
    }

    /**
     * 热重载：拒绝"原地重载"，让框架按"需要重启进程"处理。
     *
     * 本模块的状态全在 system_server 里（已注册的 hook、UID→包名缓存、remote prefs 的监听器），
     * 原地重载既无法可靠地撤掉旧 hook，又可能留下旧监听器。返回 false 更安全。
     */
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean = false

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        Log.i(Const.TAG, "hot reloaded (api=${apiVersion}, priority=${XposedInterface.PRIORITY_HIGHEST})")
    }
}
