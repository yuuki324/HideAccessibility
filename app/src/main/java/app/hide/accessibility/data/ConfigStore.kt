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

package app.hide.accessibility.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.service.XposedService
import app.hide.accessibility.Const
import java.util.concurrent.ConcurrentHashMap

/**
 * 隐藏列表的**唯一**存储通道。
 *
 * ## 为什么必须是这一条
 *
 * 被注入的 system_server 侧只能用 libxposed 的 `getRemotePreferences(group)` 读配置，
 * 它读的是**框架管理的那一个文件**，不是模块自己的 `shared_prefs/<名字>.xml`。
 *
 * 注意 `Context.getSharedPreferences("config")` 与 `getRemotePreferences("config")`
 * 看着同名，实际是**两个不同的文件**：若 App 侧用前者写、system_server 用后者读，
 * system_server 永远只能拿到兜底默认值。所以 App 侧也必须走
 * `XposedService.getRemotePreferences()` 读写同一个组名。
 *
 * 这条通道需要框架服务已绑定；拿不到服务时**不做静默降级**
 * （降级等于写了个没人读的文件），而是把状态暴露给界面。
 */
object ConfigStore {

    /** 框架服务的 remote preferences；只有拿到服务后才可用 */
    @Volatile
    private var remotePrefs: SharedPreferences? = null

    /** 通道状态，失败时用于界面提示与 logcat 诊断 */
    @Volatile
    var channelState: String = "未初始化"
        private set

    /**
     * 绑定框架服务（App 侧在拿到 `XposedService` 后调用）。
     * 返回是否成功——失败时界面应当提示，而不是假装配置生效了。
     */
    fun bindService(service: XposedService?): Boolean {
        if (service == null) {
            remotePrefs = null
            channelState = "框架服务未绑定 → 无法读写配置"
            return false
        }
        val ok = runCatching {
            remotePrefs = service.getRemotePreferences(Const.CONFIG_PREFS_NAME)
            channelState = "remote preferences(${Const.CONFIG_PREFS_NAME})"
            true
        }.onFailure {
            remotePrefs = null
            channelState = "取 remote preferences 失败: ${it.message}"
            Log.e(Const.TAG, "getRemotePreferences failed", it)
        }.getOrDefault(false)
        // 上一次进程里的自检可能没跑完（临时把模块自己加进了隐藏列表就再没回来），
        // 接上通道时还原它。**只在第一次成功绑定时做**：绑定会被反复调用（每次回前台都走一遍
        // observe），而自检进行中也可能正好走到这里——那时还原等于把自检的半成品当成用户设置。
        if (ok && !recoveryAttempted) {
            recoveryAttempted = true
            recoverInterruptedSelfCheck()
        }
        return ok
    }

    @Volatile
    private var recoveryAttempted = false

    /**
     * 被 hook 进程侧登记句柄（system_server 由框架注入）。
     *
     * 传的是**取用的动作**而不是一个现成的 SharedPreferences：框架的变更通知不保证一定送到
     * 被注入的进程（见 [forceReload]），所以这里必须留着"重新向框架要一次"的能力。
     *
     * 返回是否拿到了配置通道——拿不到时界面与日志要能看出"这份配置根本没人读"。
     */
    fun listen(provider: () -> SharedPreferences): Boolean {
        remoteProvider = provider
        val prefs = runCatching { provider() }
            .onFailure { Log.e(Const.TAG, "getRemotePreferences failed", it) }
            .getOrNull() ?: return false
        remote = prefs
        reload(prefs)
        runCatching {
            prefs.registerOnSharedPreferenceChangeListener { _, key ->
                // 变更通知线程和 Binder 线程都会重读，重读必须在同一把锁里做：
                // reload 内部是"清空 + 重新填充"，两个线程交错会让某次判定读到半个列表。
                if (key == null || key == Const.KEY_BLOCK_APPS) {
                    synchronized(reloadLock) { reload(freshRemote() ?: prefs) }
                }
            }
        }.onFailure { Log.w(Const.TAG, "listener registration failed: ${it.message}") }
        return true
    }

    /** 取 remote preferences 的动作（hook 进程侧由框架注入） */
    @Volatile
    private var remoteProvider: (() -> SharedPreferences)? = null

    /** 在 system_server 里读到的隐藏列表 */
    @Volatile
    private var remote: SharedPreferences? = null

    /**
     * App 侧是否已经接上框架通道。
     *
     * 只有接上了写入才会真的到达 system_server；没接上时最多写本地兜底文件（没有人读它），
     * 所以涉及"临时改隐藏列表"的自检必须先问这个，宁可放弃自检也不能写一份没人读的配置。
     */
    val hasChannel: Boolean get() = remotePrefs != null

    @Volatile
    private var appFallback: SharedPreferences? = null

    fun attachApp(context: Context) {
        appFallback = context.getSharedPreferences(Const.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * App 侧当前隐藏列表。
     *
     * 读不到就是**空集**。这里以前返回 `{模块自己的包名}` 兜底，结果是：
     *
     *  - App 侧把"从没配置过"显示成"模块自己已被勾选"，用户点一下取消勾选，写出去的是空集；
     *  - system_server 侧又用同一个默认值把空集当成"没有这个键"，
     *  - 于是模块自己始终留在生效列表里，主界面两条读数**永远**是"没开无障碍"，
     *    用户怎么勾怎么取消都看不出变化——而这正是那段自检读数存在的意义。
     *
     * 空集与"没有这个键"语义一致，两侧不再有歧义；要不要隐藏模块自己由用户自己勾。
     */
    fun read(context: Context): Set<String> {
        val src = remotePrefs ?: appFallback
            ?: context.getSharedPreferences(Const.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        return runCatching { src.getStringSet(Const.KEY_BLOCK_APPS, null) }.getOrNull()?.toHashSet()
            ?: emptySet()
    }

    /**
     * App 侧写入隐藏列表。返回是否**真的**写进了框架通道。
     *
     * 只写进本地兜底文件时返回 false——那种情况下 system_server 读不到，
     * 界面必须提示，不能让用户以为改动生效了。
     */
    fun write(context: Context, packages: Set<String>): Boolean {
        val snapshot = HashSet(packages)
        val target = remotePrefs
        if (target != null) {
            val ok = runCatching {
                target.edit().putStringSet(Const.KEY_BLOCK_APPS, snapshot).commit()
            }.getOrDefault(false)
            if (ok) return true
        }
        // 通道不可用：写本地兜底，让用户至少不会丢勾选，但明确报告未生效
        appFallback?.edit()?.putStringSet(Const.KEY_BLOCK_APPS, snapshot)?.apply()
        return false
    }

    /** system_server 侧当前生效的隐藏列表 */
    fun blockedSnapshot(): Set<String> = HashSet(freshBlocked())

    fun isBlocked(packageName: String?): Boolean =
        packageName != null && packageName.isNotEmpty() && freshBlocked().contains(packageName)

    // ------------------------------------------------------------------ system_server 侧状态

    private val blocked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * 兜底默认值：什么都不隐藏。
     *
     * 这里以前是 `{模块自己的包名}`，导致"从没配置过"和"用户把模块自己加了进去"在
     * system_server 侧无法区分，模块自己于是被永久当成拦截对象（详见 [read] 的说明）。
     */
    private val defaultBlocked: Set<String> = emptySet()

    /**
     * 生效列表的最长可信时间。
     *
     * 配置变更靠框架的 SharedPreferences 变更通知送达，但**不能假设它一定送达**：
     * 只要有一条没到，system_server 就会一直按旧列表拦截，而界面上显示的勾选状态
     * 来自 App 侧自己的读取——两者不一致时，用户看到的是"我明明取消了勾选，
     * 读数却还是没开无障碍"。所以判定时顺带检查一下有效期，过期就重读一次。
     *
     * 400ms 的取舍：够短，自检的轮询（100ms 一次、2s 超时）能在窗口内看到变化；
     * 又够长，正常使用时最多每 400ms 读一次 remote preferences，不至于在
     * Binder 调用路径上引入可观开销。
     */
    private const val RELOAD_TTL_MS = 400L

    @Volatile
    private var loadedAt = 0L

    private val reloadLock = Any()

    /**
     * 拿当前生效的隐藏列表，必要时重读。
     *
     * 双检：先做一次无锁的过期判断（绝大多数调用都走这条路，只是两个 volatile 读），
     * 过期了才进锁重读，避免多个 Binder 线程同时读同一份 prefs。
     */
    private fun freshBlocked(): Set<String> {
        if (remote == null && remoteProvider == null) return blocked
        if (SystemClock.elapsedRealtime() - loadedAt < RELOAD_TTL_MS) return blocked
        synchronized(reloadLock) {
            if (SystemClock.elapsedRealtime() - loadedAt < RELOAD_TTL_MS) return blocked
            val prefs = freshRemote() ?: return blocked
            reload(prefs)
        }
        return blocked
    }

    /**
     * 强制重新向框架取一次配置并重读。**由模块 App 侧主动触发**（它每次问自检探针时顺带来一发）。
     *
     * 为什么需要这条路：配置变更靠框架的 SharedPreferences 变更通知送达，通知一旦没到，
     * system_server 就会一直按旧列表拦截，而模块 App 读自己的写入永远是"成功"的——
     * 表现就是"界面里明明取消了勾选，被隐藏的应用（包括模块自己）却还是看不到无障碍状态"。
     * 与其等一个不保证会到的通知，不如让 App 每次打开界面/跑自检时就催一次。
     */
    fun forceReload() {
        val prefs = freshRemote() ?: return
        synchronized(reloadLock) { reload(prefs) }
        Log.i(Const.TAG, "config reloaded on request: ${blocked.size} package(s)")
    }

    /** 重新向框架要一次 remote preferences；拿不到就退回上次那份 */
    private fun freshRemote(): SharedPreferences? =
        runCatching { remoteProvider?.invoke() }.getOrNull() ?: remote

    private fun reload(prefs: SharedPreferences) {
        val snapshot = runCatching { prefs.getStringSet(Const.KEY_BLOCK_APPS, defaultBlocked) }
            .getOrNull() ?: defaultBlocked
        blocked.clear()
        blocked.addAll(snapshot)
        loadedAt = SystemClock.elapsedRealtime()
        Log.i(Const.TAG, "block list reloaded: ${blocked.size} package(s)")
    }

    // ------------------------------------------------------------------ 自检期间临时改列表

    /**
     * 自检备份键。
     *
     * 写在 remote preferences 里而不是内存里，就是为了跨进程被杀也能还原；
     * 前缀用下划线，与用户数据的键区分开，`exportXml` 也只导出 [Const.KEY_BLOCK_APPS]。
     */
    private const val KEY_SELF_CHECK_BACKUP = "_self_check_backup"

    /**
     * 开始一次自检：把**用户当前的列表**备份进 remote preferences，同时换上自检要用的临时列表。
     *
     * 备份不是可有可无的：自检必须临时把模块自己加进隐藏列表，而这一步一旦在还原之前
     * 进程被杀（用户划掉后台、系统回收），用户自己的列表就永久留在被改过的状态。
     * 备份写在同一份 remote preferences 里，下次启动就能还原（见 [recoverInterruptedSelfCheck]）。
     *
     * 返回 false 表示通道不可用（或写入失败），调用方必须放弃自检——**绝不能**只写本地兜底文件。
     */
    fun beginSelfCheck(userList: Set<String>, probeList: Set<String>): Boolean {
        val target = remotePrefs ?: return false
        return runCatching {
            target.edit()
                .putStringSet(KEY_SELF_CHECK_BACKUP, HashSet(userList))
                .putStringSet(Const.KEY_BLOCK_APPS, HashSet(probeList))
                .commit()
        }.onFailure { Log.e(Const.TAG, "beginSelfCheck failed", it) }
            .getOrDefault(false)
    }

    /** 结束自检：写回用户列表，并清掉备份。返回是否成功写回。 */
    fun endSelfCheck(userList: Set<String>): Boolean {
        val target = remotePrefs ?: return false
        return runCatching {
            target.edit()
                .putStringSet(Const.KEY_BLOCK_APPS, HashSet(userList))
                .remove(KEY_SELF_CHECK_BACKUP)
                .commit()
        }.onFailure { Log.e(Const.TAG, "endSelfCheck failed", it) }
            .getOrDefault(false)
    }

    /**
     * 还原被中断的自检。返回是否**真的**还原过（调用方据此打日志）。
     *
     * 判据就是那份备份：只有自检走到了写备份那一步、却没走到清理，它才会存在。
     */
    fun recoverInterruptedSelfCheck(): Boolean {
        val target = remotePrefs ?: return false
        val backup = runCatching { target.getStringSet(KEY_SELF_CHECK_BACKUP, null) }.getOrNull()
            ?: return false
        val restored = runCatching {
            target.edit()
                .putStringSet(Const.KEY_BLOCK_APPS, HashSet(backup))
                .remove(KEY_SELF_CHECK_BACKUP)
                .commit()
        }.onFailure { Log.e(Const.TAG, "recoverInterruptedSelfCheck failed", it) }
            .getOrDefault(false)
        if (restored) {
            Log.w(Const.TAG, "restored the hidden list from an interrupted self-check (${backup.size} package(s))")
        }
        return restored
    }

    // ------------------------------------------------------------------ 导入导出

    fun exportXml(packages: Set<String>): String = buildString {
        append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n")
        append("<map>\n")
        append("    <set name=\"${Const.KEY_BLOCK_APPS}\">\n")
        packages.forEach { append("        <string>${escapeXml(it)}</string>\n") }
        append("    </set>\n")
        append("</map>\n")
    }

    fun parseXml(parser: org.xmlpull.v1.XmlPullParser): MutableSet<String> {
        val result = HashSet<String>()
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "string") {
                val text = parser.nextText()
                if (text.isNotBlank()) result.add(text.trim())
            }
            event = parser.next()
        }
        return result
    }

    fun newParser() = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()

    private fun escapeXml(value: String): String = buildString(value.length) {
        value.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}
