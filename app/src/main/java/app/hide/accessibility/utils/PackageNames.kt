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

import android.os.Binder
import android.util.Log
import app.hide.accessibility.Const
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 把 Binder 调用方 UID 翻译成包名。
 *
 * 这是在 system_server 进程里跑的，两条可行路径（都不需要 Context）：
 *   1. `android.app.AppGlobals.getPackageManager()`（hidden API，Android 全版本都有）；
 *   2. `android.app.ActivityThread.getPackageManager()`（Android 13 及以前存在的静态便捷方法）。
 *
 * 两个类都是 hidden API：编译期 android.jar 里没有，只能按类名反射拿，
 * 所以这里全部用 `Class.forName` 而不是直接 import —— 直接 import 在编译期就过不了。
 *
 * 用 `getPackagesForUid()` 而不是 `getNameForUid()`：
 *  - 后者对共享 UID（`sharedUserId`、系统应用）可能返回 null 或只返回其中一个包名；
 *  - 前者返回 `String[]`，能覆盖"该 UID 下的所有包"，判断隐藏列表时不会漏。
 */
object PackageNames {

    private const val TAG = "${Const.TAG}.PackageNames"

    /** 解析结果缓存的有效期：安装/卸载后最多 3 秒内自愈，避免缓存脏数据 */
    private const val CACHE_TTL_MS = 3_000L

    /** `UserHandle.PER_USER_RANGE`：一个用户占用的 UID 数量，取不到时按 100000 兜底 */
    private const val DEFAULT_PER_USER_RANGE = 100000

    private class Entry(val packages: Array<String>, val at: Long)

    private val cache = ConcurrentHashMap<Int, Entry>()

    @Volatile
    private var holder: Any? = null

    @Volatile
    private var getPackagesForUid: Method? = null

    /** 解析通道是否可用，用于 logcat 诊断 */
    @Volatile
    var resolutionPath: String = "unresolved"
        private set

    /** 最近一次解析失败的原因，用于 logcat 诊断 */
    @Volatile
    var lastError: String? = null
        private set

    private val perUserRange: Int by lazy {
        runCatching { Class.forName("android.os.UserHandle").getField("PER_USER_RANGE").getInt(null) }
            .getOrDefault(DEFAULT_PER_USER_RANGE)
    }

    /** 当前 Binder 事务的调用方包名列表；拿不到时返回空数组（调用方必须按"不拦截"处理） */
    fun callerPackages(): Array<String> {
        val uid = runCatching { Binder.getCallingUid() }.getOrNull() ?: return emptyArray()
        val appId = uid % perUserRange
        val now = System.currentTimeMillis()
        cache[appId]?.let { if (now - it.at < CACHE_TTL_MS) return it.packages }
        val resolved = resolve(appId) ?: return cache[appId]?.packages ?: emptyArray()
        cache[appId] = Entry(resolved, now)
        return resolved
    }

    /** 兼容用法：只要一个名字（诊断/日志用） */
    fun callerPackageName(): String? = callerPackages().firstOrNull()

    /** 安装/卸载/UID 复用后清缓存 */
    fun invalidate() {
        cache.clear()
    }

    /** 诊断读数 */
    fun debugSnapshot(): String =
        "resolver=$resolutionPath\ncachedUids=${cache.size}" + (lastError?.let { "\nlastError=$it" } ?: "")

    private fun resolve(appId: Int): Array<String>? {
        val (pm, method) = packageManager() ?: return null
        return runCatching {
            // 内部会做 Binder 调用；清掉 calling identity，免得受当前 Binder 事务的调用方身份干扰。
            val restored = Binder.clearCallingIdentity()
            try {
                @Suppress("UNCHECKED_CAST")
                method.invoke(pm, appId) as? Array<String>
            } finally {
                Binder.restoreCallingIdentity(restored)
            }
        }.onFailure { lastError = it.toString() }.getOrNull()
    }

    @Synchronized
    private fun packageManager(): Pair<Any, Method>? {
        val cachedMethod = getPackagesForUid
        val cachedHolder = holder
        if (cachedMethod != null && cachedHolder != null) return cachedHolder to cachedMethod

        resolveHolder("android.app.AppGlobals")?.let { pm ->
            bind(pm, "AppGlobals.getPackageManager()")?.let { return pm to it }
        }
        resolveHolder("android.app.ActivityThread")?.let { pm ->
            bind(pm, "ActivityThread.getPackageManager()")?.let { return pm to it }
        }
        if (resolutionPath == "unresolved") {
            resolutionPath = "UNAVAILABLE"
            Log.w(TAG, "cannot resolve UID to package name; nothing will be hidden")
        }
        return null
    }

    private fun resolveHolder(className: String): Any? = runCatching {
        Class.forName(className).getMethod("getPackageManager").invoke(null)
    }.getOrNull()

    private fun bind(pm: Any, via: String): Method? = runCatching {
        val method = pm.javaClass.getMethod("getPackagesForUid", Int::class.javaPrimitiveType)
        holder = pm
        getPackagesForUid = method
        resolutionPath = via
        Log.i(TAG, "resolver ready via $via")
        method
    }.onFailure { lastError = "bind($via): $it" }.getOrNull()
}
