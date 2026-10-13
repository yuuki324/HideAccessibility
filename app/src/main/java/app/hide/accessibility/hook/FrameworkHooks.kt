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

package app.hide.accessibility.hook

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.BaseBundle
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import app.hide.accessibility.Const
import app.hide.accessibility.data.ConfigStore
import java.lang.reflect.Executable
import java.util.concurrent.atomic.AtomicInteger

/**
 * 真正做拦截的地方：system_server 进程内的 framework hook。
 *
 * 拦截点只有三个——都是"应用自己去问系统：我这儿开了无障碍吗？"的入口：
 *
 *  1. `AccessibilityManagerService#addClient(IAccessibilityManagerClient, int)`
 *     应用进程第一次用 `AccessibilityManager` 时会注册一个 client，拿到的 long 是
 *     "你该看到的状态位掩码"。返回 0 等于告诉它：无障碍没开、也没有服务在跑。
 *  2. `AccessibilityManagerService#getEnabledAccessibilityServiceList(int, int)`
 *     直接返回空列表。
 *  3. `ContentProvider$Transport#call(..., "settings", "GET_secure", key, ...)`
 *     应用通过 `Settings.Secure.getString(ENABLED_ACCESSIBILITY_SERVICES/ACCESSIBILITY_ENABLED)`
 *     读设置时，把返回值改掉（只对 `_secure` 命名空间、只对这两个 key、只对被隐藏的调用方）。
 *
 * 判断"是否隐藏"一律看 `Binder.getCallingUid()` 对应的包名 —— 也就是"谁在问就骗谁"，
 * 系统自己的无障碍服务、其他未被勾选的应用完全不受影响。
 *
 * 稳定性约定：每个 hook 体都自己吞异常并回落到原实现。system_server 里抛出去一个异常
 * 可能直接带走整个系统进程，代价远大于"这次没拦成"。
 */
class FrameworkHooks(
    private val module: XposedModule,
    private val classLoader: ClassLoader,
) {

    private val tag = "${Const.TAG}.Hooks"

    /** GET_secure 的探测日志只打前 3 条，避免刷 log */
    private val probeBudget = AtomicInteger(3)

    /** 装上 hook，并返回一份人类可读的安装结果（写进 logcat 诊断日志） */
    fun install(): String {
        val a11y = hookAccessibilityManagerService()
        val transport = hookContentProviderTransport()
        return "accessibility=$a11y\ntransport=$transport"
    }

    // ------------------------------------------------------------ AccessibilityManagerService

    private fun hookAccessibilityManagerService(): String {
        val clazz = findClass("com.android.server.accessibility.AccessibilityManagerService")
        if (clazz == null) {
            Log.e(tag, "AccessibilityManagerService not found; hooks NOT installed")
            return "AMS: class not found"
        }

        val results = mutableListOf<String>()

        // addClient(IAccessibilityManagerClient client, int userId) -> long
        results += hookMethod(
            clazz, "addClient",
            paramCount = 2,
            accept = { types -> types[0].name == "android.view.accessibility.IAccessibilityManagerClient" && isInt(types[1]) },
        ) { chain ->
            val caller = blockedCallerOrNull()
            if (caller != null) {
                Diagnostics.reportFirstHit(caller, "addClient")
                0L
            } else {
                chain.proceed()
            }
        }

        // getEnabledAccessibilityServiceList(int feedbackType, int userId) -> List<AccessibilityServiceInfo>
        results += hookMethod(
            clazz, "getEnabledAccessibilityServiceList",
            paramCount = 2,
            accept = { types -> isInt(types[0]) && isInt(types[1]) },
        ) { chain ->
            val caller = blockedCallerOrNull()
            if (caller != null) {
                Diagnostics.reportFirstHit(caller, "getEnabledAccessibilityServiceList")
                emptyList<AccessibilityServiceInfo>()
            } else {
                chain.proceed()
            }
        }
        return results.joinToString(",")
    }

    // ------------------------------------------------------------ Settings.Secure 读取路径

    private fun hookContentProviderTransport(): String {
        val clazz = findClass("android.content.ContentProvider\$Transport")
        if (clazz == null) {
            Log.e(tag, "ContentProvider\$Transport not found; Settings.Secure hook NOT installed")
            return "Transport: class not found"
        }
        val candidates = clazz.declaredMethods.filter { it.name == "call" && it.returnType == Bundle::class.java }
        if (candidates.isEmpty()) {
            Log.e(tag, "ContentProvider\$Transport#call not found")
            return "Transport: no call()"
        }
        Log.i(tag, "Transport#call candidates: " + candidates.joinToString { signatureOf(it) })
        val hooked = mutableListOf<String>()
        candidates.forEach { method ->
            if (method.parameterTypes.none { it == String::class.java }) return@forEach
            if (hookExecutable(method) { chain -> onSettingsCall(chain) }) hooked += signatureOf(method)
        }
        return if (hooked.isEmpty()) "Transport: hook failed" else "Transport: " + hooked.joinToString(",")
    }

    /**
     * `Transport#call` 的 after 逻辑。
     *
     * 参数形状有两种（Android 10 起多了 AttributionSource）：
     *   call(AttributionSource, String authority, String method, String arg, Bundle extras)  -> 6 参含 this
     *   call(String authority, String method, String arg, Bundle extras)                      -> 5 参含 this
     * 不靠位置硬猜，直接从 `chain.args` 里按类型取第一个 String 当 authority、第二个当 method、
     * 第三个当 arg。
     */
    private fun onSettingsCall(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        val strings = chain.args.filterIsInstance<String>()
        if (strings.size < 3) return result
        val authority = strings[0]
        val method = strings[1]
        val key = strings[2]
        if (authority != SETTINGS_AUTHORITY || method != CALL_METHOD_GET_SECURE) return result

        val bundle = result as? Bundle ?: return result
        val replacement = when (key) {
            ENABLED_ACCESSIBILITY_SERVICES -> ""
            ACCESSIBILITY_ENABLED -> "0"
            else -> return result
        }

        val caller = blockedCallerOrNull()
        if (probeBudget.getAndUpdate { if (it > 0) it - 1 else it } > 0) {
            Log.i(tag, "GET_secure key=$key caller=${CallerGuard.lastCaller ?: "?"} blocked=${caller != null}")
        }
        if (caller == null) return result

        // 直接改返回的 Bundle：既要改 value，也要让 libcore 的 generation index 失效，
        // 否则 Settings.Secure 侧可能仍用自己缓存里的旧值。
        (bundle as BaseBundle).putString(VALUE_KEY, replacement)
        bundle.putInt(GENERATION_INDEX_KEY, -1)
        Diagnostics.reportFirstHit(caller, "GET_secure($key)")
        return bundle
    }

    // ------------------------------------------------------------ 工具

    /** 调用方在被隐藏列表里时返回它的包名，否则返回 null（含解析失败 → 不拦截） */
    private fun blockedCallerOrNull(): String? = CallerGuard.blockedCallerOrNull()

    private fun findClass(name: String): Class<*>? = runCatching { Class.forName(name, false, classLoader) }
        .onFailure { Log.w(tag, "Class.forName($name) failed: ${it.message}") }
        .getOrNull()

    private inline fun hookMethod(
        clazz: Class<*>,
        name: String,
        paramCount: Int,
        accept: (Array<Class<*>>) -> Boolean,
        crossinline body: (XposedInterface.Chain) -> Any?,
    ): String {
        val match = clazz.declaredMethods.firstOrNull { it.name == name && it.parameterCount == paramCount && accept(it.parameterTypes) }
        if (match == null) {
            Log.e(tag, "$name/$paramCount not found in ${clazz.name}; candidates: " +
                clazz.declaredMethods.filter { it.name == name }.joinToString { signatureOf(it) })
            return "$name:MISSING"
        }
        return if (hookExecutable(match) { body(it) }) "$name:ok" else "$name:FAILED"
    }

    private fun hookExecutable(executable: Executable, body: (XposedInterface.Chain) -> Any?): Boolean {
        try {
            module.hook(executable)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        body(chain)
                    } catch (t: Throwable) {
                        // 兜底：拦截逻辑出错时退回原实现，绝不让异常冒到 framework
                        Log.e(tag, "hook body failed for ${signatureOf(executable)}", t)
                        chain.proceed()
                    }
                }
            Log.i(tag, "hooked ${signatureOf(executable)}")
            return true
        } catch (t: Throwable) {
            Log.e(tag, "hook ${signatureOf(executable)} failed", t)
            return false
        }
    }

    private fun signatureOf(executable: Executable): String =
        executable.name + "(" + executable.parameterTypes.joinToString(",") { it.simpleName } + ")"

    private fun isInt(type: Class<*>): Boolean = type == Int::class.javaPrimitiveType || type == Integer::class.java

    private companion object {
        const val SETTINGS_AUTHORITY = "settings"
        const val CALL_METHOD_GET_SECURE = "GET_secure"
        const val ENABLED_ACCESSIBILITY_SERVICES = "enabled_accessibility_services"
        const val ACCESSIBILITY_ENABLED = "accessibility_enabled"
        const val VALUE_KEY = "value"
        const val GENERATION_INDEX_KEY = "_generation_index"
    }
}
