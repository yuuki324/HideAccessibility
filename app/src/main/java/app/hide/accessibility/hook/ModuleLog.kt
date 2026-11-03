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

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * hook 侧日志门面：同一条日志**同时**写 logcat 和框架日志。
 *
 * ## 为什么要写两份
 *
 * logcat 只写进当前进程的环形缓冲区，而本模块的关键日志都在 `system_server` 里——
 * 普通用户既没有 root 也没有 adb，根本读不到。结果就是用户报"模块不生效"时，
 * 我们能拿到的 LSPosed 日志里**看不到任何本模块的输出**，无法判断 hook 到底装上没有。
 *
 * libxposed 的 [XposedInterface.log] 会把日志交给框架，框架再写进它自己导出的日志文件
 * （`modules_*.log`）。所以用户只要"重启 + 导出 LSPosed 日志"，我们的日志就跟着出来了，
 * 不需要 root、不需要 adb、也不需要他现场敲命令。
 *
 * ## 两种日志的分工
 *
 *  - logcat：本机 `adb logcat` 调试用，进程内即时可见；
 *  - 框架日志：给远程排障用，随 LSPosed 日志包一起带走。
 *
 * 框架日志写入失败（[attach] 还没调用、或框架实现拒绝）时静默忽略：日志本身不该
 * 成为故障源，而 logcat 那一份已经在上面写过了。
 */
object ModuleLog {

    @Volatile
    private var iface: XposedInterface? = null

    /** 由 [ModuleEntry] 在被框架加载时登记；登记前所有调用只写 logcat */
    fun attach(xposed: XposedInterface) {
        iface = xposed
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        runCatching { iface?.log(Log.INFO, tag, message) }
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        runCatching { iface?.log(Log.WARN, tag, message) }
    }

    fun e(tag: String, message: String, tr: Throwable? = null) {
        if (tr == null) Log.e(tag, message) else Log.e(tag, message, tr)
        runCatching { iface?.log(Log.ERROR, tag, message, tr) }
    }
}
