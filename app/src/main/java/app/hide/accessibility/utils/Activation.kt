/*
 * Copyright (C) 2026 yuuki324 <https://github.com/yuuki324/HideAccessibility>
 * Copyright (C) Nitsuya <https://github.com/Nitsuya/DoNotTryAccessibility>
 *
 * This file is part of HideAccessibility, a port of DoNotTryAccessibility.
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this
 * program. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package app.hide.accessibility.utils

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import app.hide.accessibility.Const
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

    private const val TAG = "${Const.TAG}.Activation"

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

    /**
     * 模块 APK 是否在**本次开机之后**安装或更新过。
     *
     * LSPosed 只在**进程启动**时加载模块，而 system_server 是开机后常驻的进程：模块一旦
     * 安装或更新，必须重启设备，新代码才会被加载。在那之前界面可能显示「模块已激活」——
     * 模块 App 能拿到框架服务，那条路走 Provider + 框架投递，与"system_server 里到底有没有
     * 加载这个模块"无关，所以从界面上完全看不出问题。
     *
     * 判据是 `PackageInfo.lastUpdateTime`（首次安装时它等于安装时刻，所以两种情况都覆盖）
     * 晚于本次开机时刻。开机时刻用「当前时间 - 开机以来的毫秒数」反推，不需要额外权限。
     *
     * 注意这跟「作用域没勾」是两回事，后者由 [hasSystemScope] 判定。
     */
    fun installedAfterBoot(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val installedAt = if (Build.VERSION.SDK_INT >= 33)
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L)).lastUpdateTime
        else
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0).lastUpdateTime
        val bootedAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        installedAt > bootedAt
    }.getOrDefault(false)

    // ------------------------------------------------------------------ 进程内缓存的刷新

    /**
     * 拿一份**状态位是最新的** [AccessibilityManager]。
     *
     * `isEnabled` 读的是注册 client 时由 `addClient` 返回值定下的进程内缓存
     * （`AccessibilityManager#setStateLocked`），隐藏列表怎么变它都不会自己更新——这正是
     * "勾选后回到主页读数还是旧的、冷启动应用才对"的原因。要看现在的状态，必须先让它重新注册一次。
     * 两条路：
     *
     *  1. 把到 system_server 的连接字段写掉：`AccessibilityManager` 里凡是走 `getServiceLocked()`
     *     的方法都会发现没有连接，于是 `tryConnectToServiceLocked()` → 重新 `addClient`；
     *  2. 字段写不掉时（hidden API 限制反射写、或字段被改名）**构造一个新实例**——它在构造里
     *     就会 `tryConnectToServiceLocked()`，拿到的状态位必定是新的。
     *
     * 以前只做了第 1 条，而且不管成没成都往下走：一旦字段没写掉，读数就永远停在进程启动那一刻
     * 的状态位，界面上看着像"勾选完全没反应"。
     *
     * 返回 null 表示两条路都没走通，调用方按"读不到"处理（不要假装读到了）。
     */
    fun freshAccessibilityManager(context: Context): AccessibilityManager? {
        val existing = runCatching { context.getSystemService(AccessibilityManager::class.java) }.getOrNull()
        if (existing != null && clearServiceField(existing)) {
            // 任意一个走 getServiceLocked() 的公开调用都会触发重连；顺手把列表也读新
            runCatching { existing.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK) }
            return existing
        }
        return newAccessibilityManager(context)
    }

    /** 把到 system_server 的连接字段置空；返回是否**确实**置空了（写不掉时不能假装重连成功） */
    private fun clearServiceField(am: AccessibilityManager): Boolean = runCatching {
        val field = AccessibilityManager::class.java.declaredFields
            .firstOrNull { it.type.name == "android.view.accessibility.IAccessibilityManager" }
            ?: return false
        field.isAccessible = true
        field.set(am, null)
        field.get(am) == null
    }.getOrDefault(false)

    /**
     * 反射构造一个 [AccessibilityManager]。
     *
     * 构造签名（AOSP 各版本都是这个）：
     * `AccessibilityManager(Context context, IAccessibilityManager service, int userId)`，
     * 传 `service = null` 时它自己会去拿系统服务并 `addClient`。
     * 它是 `@hide public`，编译期看不到，所以只能反射。
     */
    private fun newAccessibilityManager(context: Context): AccessibilityManager? = runCatching {
        val ctor = AccessibilityManager::class.java.getDeclaredConstructor(
            Context::class.java,
            Class.forName("android.view.accessibility.IAccessibilityManager"),
            Int::class.javaPrimitiveType,
        )
        ctor.isAccessible = true
        // 用户 id 的换算自己算：UserHandle.myUserId()/getUserId() 都是 @hide，编译期看不到。
        // UID 的编码就是 userId * PER_USER_RANGE + appId，整除即得用户 id。
        ctor.newInstance(context, null, Process.myUid() / PER_USER_RANGE) as AccessibilityManager
    }.onFailure { Log.w(TAG, "could not rebuild AccessibilityManager: ${it.message}") }
        .getOrNull()

    // ------------------------------------------------------------------ 直连读数

    /**
     * 直连 SETTINGS 提供者读一条 secure 设置。
     *
     * 刻意**不用** `Settings.Secure.getString`：那条路在应用进程里有一层 `NameValueCache`，
     * 一旦某次读到真值就会连同 generation 一起缓存下来；之后即使把本应用加进隐藏列表，
     * 读到的仍是缓存里的真值——自检会因此误判成"hook 没生效"，界面也会一直显示旧读数。
     * `ContentResolver.call` 每次都真的跨进程问一次（走的正是模块在 system_server 里拦的
     * 那条 `ContentProvider$Transport#call`），拿到的就是拦不拦之后的当前值。
     *
     * 直连要是拿不到（极少数 ROM 的 SETTINGS 提供者不接受这种调用），退回常规读法：
     * 那时读数可能命中进程内缓存，但至少不会让整块读数变成 `<null>`。
     */
    fun readSecureDirect(context: Context, key: String): String? {
        val direct = runCatching {
            context.contentResolver.call(SETTINGS_AUTHORITY, CALL_METHOD_GET_SECURE, key, null)
                ?.getString(VALUE_KEY)
        }.getOrNull()
        if (direct != null) return direct
        return runCatching { Settings.Secure.getString(context.contentResolver, key) }.getOrNull()
    }

    /**
     * 两条被拦截通道现在报告的"无障碍开着吗"。
     *
     * 判据是"启用标志为 1 **或** 启用列表非空"：模块生效时两者都会被改成 `0` / 空，
     * 而设备真的开着无障碍时至少有一个是有值的。
     */
    fun accessibilityVisible(context: Context): Boolean {
        val enabled = readSecureDirect(context, Settings.Secure.ACCESSIBILITY_ENABLED)
        val services = readSecureDirect(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled == "1" || !services.isNullOrEmpty()
    }

    /**
     * 问一次 system_server 侧的 hook：你在不在、当前生效的隐藏列表是什么。
     *
     * 返回 null = **没有任何 hook 回答这次询问**：模块没被注入 system_server，或者 hook 没装上。
     * 这条通道（[Const.PROBE_KEY]）只在 system_server 的 `Transport#call` hook 里被认领，
     * 所以"有回答"就等价于"那个 hook 确实在跑"。
     *
     * 存在的理由见 [Const.PROBE_KEY]：界面上的勾选来自 App 侧的读取，真正执行拦截的列表在
     * system_server 里，两者不一致时只靠界面读数永远分不清"没生效"和"配置没送过去"。
     */
    fun probeSystemSide(context: Context): String? = runCatching {
        context.contentResolver.call(SETTINGS_AUTHORITY, CALL_METHOD_GET_SECURE, Const.PROBE_KEY, null)
            ?.getString(VALUE_KEY)
    }.getOrNull()

    /**
     * 探针回答的结构化版本。
     *
     * system_server 侧把 `hook=`、`pid=`、`blocked=`、`queries=`、`hits=`、`resolver=`
     * 拼成一段多行文本回传（见 `FrameworkHooks.probeBundle`）。界面上直接展示原文也能看，
     * 但"有没有回答""hook 是否健康"这两件事需要由代码判断，所以在这里先拆成字段——
     * 这样标题、状态行、诊断文本都能用同一份结论，不必各自去截字符串。
     *
     * [answered] 为 false 是"模块看起来已激活、实际却没生效"最直接的信号：它说明
     * system_server 里那条 `Transport#call` hook 没在跑——模块没被加载，或 hook 没装上。
     * 这与"模块 App 能不能连上框架服务"是两条独立的链路（见 [Activation.state] 的说明）。
     */
    data class ProbeReport(
        /** system_server 侧有没有回答这次询问 */
        val answered: Boolean,
        /** 原始回答，供"复制诊断信息"原样带出 */
        val raw: String?,
        /** 每个 hook 的安装结果，分号分隔，例如 `AMS:addClient:ok,...;Transport:ok` */
        val hook: String?,
        /** system_server 的 pid */
        val pid: Int?,
        /** system_server 侧当前生效的隐藏列表大小 */
        val blockedCount: Int?,
        /** 模块自己是否在该列表里（自检期间会临时为 true） */
        val blockedSelf: Boolean,
        val queries: Long?,
        val hits: Long?,
        /** UID→包名解析走的哪条路 */
        val resolver: String?,
    ) {
        /**
         * 安装结果是否每一项都成功。
         *
         * 没有 `hook` 字段时按"不健康"处理：宁可多提示一次，也不要拿一个空字段报"正常"。
         */
        val hookHealthy: Boolean
            get() {
                val text = hook ?: return false
                return text.isNotBlank() &&
                    !text.contains("MISSING") &&
                    !text.contains("FAILED") &&
                    !text.contains("not found") &&
                    !text.contains("not installed")
            }

        /** `addClient` 的安装状态：`ok` / `MISSING` / `FAILED`… */
        val addClientStatus: String? get() = hookStatus("addClient")

        /** `getEnabledAccessibilityServiceList` 的安装状态 */
        val serviceListStatus: String? get() = hookStatus("getEnabledAccessibilityServiceList")

        /** `Transport#call` 的安装状态；成功时是 `call(...)` 而不是 `ok` */
        val transportStatus: String? get() = hookStatus("Transport")

        /**
         * 从 `hook` 字符串里取出某个方法的安装状态。
         *
         * 原始格式是 `accessibility=addClient:ok,getEnabledAccessibilityServiceList:ok;transport=Transport:call(...)`。
         * Transport 那一段的方法签名本身带逗号（`call(AttributionSource,String,...)`），
         * 不能按逗号切，只能单独按 `;` 截到结尾。
         */
        private fun hookStatus(name: String): String? {
            val text = hook ?: return null
            val pattern = if (name == "Transport") {
                Regex("Transport:\\s*([^;]+)")
            } else {
                Regex(Regex.escape(name) + ":\\s*([^,;]+)")
            }
            return pattern.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    fun probeReport(context: Context): ProbeReport {
        val raw = probeSystemSide(context)
        if (raw.isNullOrBlank()) {
            return ProbeReport(false, raw, null, null, null, false, null, null, null)
        }
        val fields = raw.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null
                else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
            }
            .toMap()
        val blocked = fields["blocked"]
        return ProbeReport(
            answered = true,
            raw = raw,
            hook = fields["hook"],
            pid = fields["pid"]?.toIntOrNull(),
            blockedCount = blocked?.let { text -> text.split(',').count { it.isNotBlank() } },
            blockedSelf = fields["blockedSelf"] == "1",
            queries = fields["queries"]?.toLongOrNull(),
            hits = fields["hits"]?.toLongOrNull(),
            resolver = fields["resolver"],
        )
    }

    // ------------------------------------------------------------------ hook 是否真的生效

    /**
     * 自检：模块的隐藏能力到底有没有生效。
     *
     * 返回 **true** = 确认生效；**false** = 确认没生效；**null** = 无法判断。
     *
     * 判据全部来自 [accessibilityVisible] 的**实测读数**，而不是"App 侧以为的配置"：
     * 后者只说明我们写了什么，说明不了 system_server 有没有照它执行——两者不一致时，
     * 界面会显示"已勾选/已取消"却始终看不到读数变化。
     *
     * 流程：
     *  1. 先读一次基线。读到"无障碍没开"时**无法判断**（设备本来就没开，与已经被拦住，
     *     读数一模一样），直接返回 null，不去动用户的配置。
     *  2. 基线是"开着"，而模块自己**已经在**隐藏列表里：它能读到"开着"，说明 hook 没拦住它，
     *     返回 false。
     *  3. 基线是"开着"且模块自己不在列表里：临时把自己加进隐藏列表，轮询等 system_server
     *     生效；读到"没开"即确认生效，等到超时仍是"开着"就是没生效。
     *
     * 临时写入前先备份用户列表（[ConfigStore.beginSelfCheck]），结束后无论结果如何都写回，
     * 并确认读数回到了基线。宁可少一个自检能力，也不能把用户的隐藏列表改坏。
     */
    fun runSelfCheck(context: Context): Boolean? = runCatching {
        val pkg = context.packageName
        val probe = probeSystemSide(context)
        // 探针只是把 system_server 侧的状态带到 logcat 里，判定仍旧看实测读数：
        // 探针不响应也可能只是这条通道在特定 ROM 上没被认领，不该据此下结论。
        Log.i(TAG, "self-check: system_server probe -> ${probe ?: "no answer"}")
        if (!accessibilityVisible(context)) {
            Log.i(TAG, "self-check: accessibility already reads as off; " +
                "can't tell a working hook from a device that has it off")
            return null
        }
        val userList = ConfigStore.read(context)
        Log.i(TAG, "self-check: user list(${userList.size})=${userList.sorted()}")
        if (userList.contains(pkg)) {
            Log.i(TAG, "self-check: $pkg is in the hidden list yet still reads as enabled -> hook not effective")
            return false
        }
        if (!ConfigStore.hasChannel) {
            Log.i(TAG, "self-check: no framework channel; leaving the hidden list alone")
            return null
        }
        if (!ConfigStore.beginSelfCheck(userList, HashSet(userList).apply { add(pkg) })) {
            Log.i(TAG, "self-check: could not write the probe list")
            return null
        }
        var effective = false
        try {
            effective = waitForReading(context, wantVisible = false, timeoutMs = PROBE_TIMEOUT_MS)
            val verdict = if (effective) {
                "changed the reading -> hook effective"
            } else {
                "did not change the reading -> hook NOT effective"
            }
            Log.i(TAG, "self-check: hiding $pkg $verdict")
        } finally {
            // 还原并确认：写回用户列表之后，读数必须回到基线（基线是"开着"，所以期望"开着"）
            if (!ConfigStore.endSelfCheck(userList)) {
                Log.e(TAG, "self-check: writing the user's hidden list back failed")
            } else if (!waitForReading(context, wantVisible = true, timeoutMs = PROBE_TIMEOUT_MS)) {
                Log.w(TAG, "self-check: reading did not return to the user's list within ${PROBE_TIMEOUT_MS}ms")
            }
        }
        effective
    }.getOrNull()

    /**
     * 轮询等读数变成期望值。
     *
     * 配置写到 system_server 生效是**异步**的（框架的变更通知，或 [ConfigStore] 里那个
     * 400ms 兜底重读），所以只能等：这里每 [PROBE_POLL_MS] 读一次，最多等 [timeoutMs]。
     */
    private fun waitForReading(context: Context, wantVisible: Boolean, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (accessibilityVisible(context) == wantVisible) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            if (Thread.currentThread().isInterrupted) return false
            runCatching { Thread.sleep(PROBE_POLL_MS) }
        }
    }

    /** SETTINGS 提供者的 authority 与读取方法名，与 system_server 侧 hook 的那条通道一致 */
    private const val SETTINGS_AUTHORITY = "settings"
    private const val CALL_METHOD_GET_SECURE = "GET_secure"
    private const val VALUE_KEY = "value"

    /** 自检的等待窗口与轮询间隔：要盖住配置生效的异步延迟，又不能让人干等 */
    private const val PROBE_TIMEOUT_MS = 2_000L
    private const val PROBE_POLL_MS = 100L

    /** 每个用户占用的 UID 数量（`UserHandle.PER_USER_RANGE`，各版本都是这个值） */
    private const val PER_USER_RANGE = 100_000
}
