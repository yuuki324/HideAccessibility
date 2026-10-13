package app.hide.accessibility.hook

import android.util.Log
import app.hide.accessibility.Const
import app.hide.accessibility.data.ConfigStore
import app.hide.accessibility.utils.PackageNames

/**
 * system_server 侧的诊断日志，只写 logcat，不做界面回传。
 *
 * 这个模块的失效方式是**静默**的：模块没被注入 system_server 时
 * `onSystemServerStarting` 根本不执行，而模块 App 的界面照样显示「已激活」
 * （那条路径走 Provider + 框架投递，与"模块 dex 有没有被加载"无关）。
 * 这行日志让排查时能区分"模块没加载"（logcat 里什么都没有）
 * 和"加载了但没拦到"（有加载日志、无命中日志）。
 *
 * 读 system_server 的日志需要 root（非 root 的 `adb shell` 拿不到），
 * 所以这里不做 UI 回传。详见 README「调试」一节。
 */
object Diagnostics {

    private const val TAG = "${Const.TAG}.Diagnostics"

    /** 记录"模块确实被加载进 system_server 了"以及 hook 安装结果 */
    fun reportInstalled(hookResult: String, configNote: String) {
        Log.i(TAG, "system_server loaded: pid=${android.os.Process.myPid()}")
        Log.i(TAG, "hooks: $hookResult")
        Log.i(TAG, "config: $configNote")
        Log.i(TAG, "blocked(${ConfigStore.blockedSnapshot().size}): ${ConfigStore.blockedSnapshot().sorted()}")
        Log.i(TAG, PackageNames.debugSnapshot())
    }

    private var lastTouch = 0L

    /** 首次命中时记一条，证明"判定链走通了"；之后不再刷屏 */
    fun reportFirstHit(pkg: String, hook: String) {
        val now = System.currentTimeMillis()
        if (now - lastTouch < 5_000) return
        lastTouch = now
        Log.i(TAG, "$hook masked for $pkg (queries=${CallerGuard.queries.get()} hits=${CallerGuard.hits.get()})")
    }
}
