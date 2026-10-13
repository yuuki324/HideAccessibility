package app.hide.accessibility.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import app.hide.accessibility.data.ConfigStore

/**
 * 模块激活状态检测。
 *
 * libxposed 的 service 通过 `XposedServiceHelper` 回调把框架服务给到模块 App，
 * 能拿到 service 就说明"框架在跑且本模块已被激活"。作用域里有 `system`（或 `android`）
 * 由 `XposedService.getScope()` 回答。
 *
 * ## 为什么要三态
 *
 * 冷启动时框架服务的绑定是**异步**的：框架把 Binder 投递进 `XposedProvider` 之后，
 * `XposedServiceHelper` 才会回调 `onServiceBind`。若只有"有服务 / 没服务"两态，
 * 首次取值必然拿到 null，界面会先画一遍「模块未激活」，等绑定完成再翻成「已激活」。
 *
 * 根因是把「**还不知道**」当成了「**未激活**」。这里显式区分 [State.UNKNOWN]，
 * 调用方在 UNKNOWN 时应保持"检测中"，而不是下结论。
 */
object Activation {

    enum class State { UNKNOWN, ACTIVE, INACTIVE }

    @Volatile
    private var service: XposedService? = null

    /** 最近一次得到确定状态（激活或断开）的时刻 */
    @Volatile
    private var observedAt = 0L

    /** 首次注册监听的时刻；用来给 UNKNOWN 兜底超时 */
    @Volatile
    private var firstObserveAt = 0L

    private var listener: ((XposedService?) -> Unit)? = null

    @Volatile
    private var registered = false

    /**
     * 缓存最长可信时间：重进 Activity / 旋转屏幕时立刻拿到上次的确定状态（不闪），
     * 但超过这个时间就重新按 UNKNOWN 处理，避免进程长期存活时显示陈旧结论。
     */
    private const val CACHE_TRUST_MS = 30_000L

    /**
     * UNKNOWN 的最长等待时间。
     *
     * 冷启动时框架绑定服务是异步的，所以第一帧不能直接判"未激活"（会闪）。
     * 但如果框架没把服务给我们（模块没启用 / 作用域没勾 / 没重启），
     * 那就**永远**等不到回调——没有这个超时，界面会一直停在"正在检测"。
     * 超时后落到"未激活"；之后服务真绑上来，[markActive] 照样能翻回已激活。
     *
     * 正常路径下 Binder 投递在一瞬间完成（`ModuleApp.onCreate` 已提前注册监听），
     * 所以这个值只需覆盖"异常路径下别让人干等"。
     */
    private const val UNKNOWN_TIMEOUT_MS = 1_500L

    /**
     * 注册监听。回调会立即用当前已知状态触发一次，之后每次服务绑定/断开都会再触发。
     *
     * **回调参数为 null 不代表未激活**，要看 [state]。
     */
    fun observe(onChange: (XposedService?) -> Unit) {
        listener = onChange
        if (!registered) {
            registered = true
            firstObserveAt = SystemClock.elapsedRealtime()
            runCatching {
                XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                    override fun onServiceBind(service: XposedService) {
                        markActive(service)
                        listener?.invoke(service)
                    }

                    override fun onServiceDied(service: XposedService) {
                        // 服务断开是确定状态（框架进程没了），不是"未知"
                        Activation.service = null
                        observedAt = SystemClock.elapsedRealtime()
                        ConfigStore.bindService(null)
                        listener?.invoke(null)
                    }
                })
            }.onFailure {
                // 注册失败 = 确定拿不到状态，按未激活处理
                observedAt = SystemClock.elapsedRealtime()
                listener?.invoke(null)
            }
        }
        // registerListener 内部会把已缓存的服务同步补发（遍历 mCache 调 onServiceBind），
        // 所以上面那步之后 service 通常就有了；这里再同步一次，覆盖"注册前已绑定"的情况。
        service?.let { ConfigStore.bindService(it) }
        onChange(service)
    }

    private fun markActive(svc: XposedService) {
        service = svc
        observedAt = SystemClock.elapsedRealtime()
        // 配置通道依赖框架服务：绑定时立刻接上，界面里的勾选才会真的生效
        ConfigStore.bindService(svc)
    }

    fun current(): XposedService? = service

    /**
     * 当前状态。
     *
     * [State.UNKNOWN] 只用于"第一次问、框架还没答复"这一小段窗口，超过
     * [UNKNOWN_TIMEOUT_MS] 就落到 [State.INACTIVE]，否则框架压根不给服务时
     * 界面会永远停在"正在检测"。
     */
    val state: State
        get() {
            if (service != null) return State.ACTIVE

            val now = SystemClock.elapsedRealtime()
            if (observedAt != 0L) {
                // 有过确定答复：短期内直接复用，长期则重新判定
                return if (now - observedAt < CACHE_TRUST_MS) State.INACTIVE else State.UNKNOWN
            }
            // 还从没得到过答复：只在超时窗口内算"未知"
            if (firstObserveAt == 0L) return State.UNKNOWN
            return if (now - firstObserveAt < UNKNOWN_TIMEOUT_MS) State.UNKNOWN else State.INACTIVE
        }

    /** UNKNOWN 是否已超时（界面可据此安排一次重绘，把"检测中"换成结论） */
    fun unknownTimedOut(): Boolean = state == State.INACTIVE

    /** 框架是否已激活，且作用域包含 system_server（`system`，兼容 `android`） */
    fun hasSystemScope(service: XposedService): Boolean = runCatching {
        service.scope.any { it == "system" || it == "android" }
    }.getOrDefault(false)

    /** 读一个应用的名字，取不到就用包名 */
    fun labelOf(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 33)
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
        else
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
    }.map { it.loadLabel(context.packageManager).toString() }.getOrDefault(packageName)
}
