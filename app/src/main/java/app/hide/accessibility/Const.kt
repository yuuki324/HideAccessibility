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

    /**
     * 自检探针键。
     *
     * 模块 App 拿它当 `Settings.Secure` 的键去读一次（`GET_secure`），system_server 侧的
     * hook 认出这个键后**直接回一份自己的状态**：hook 装上了什么、当前生效的隐藏列表、
     * UID→包名解析结果。系统设置里并不存在这个键，所以这条调用不会读到、也不会改到任何
     * 真实设置；hook 侧只回答模块自己（其余调用方一律放行）。
     *
     * 存在的理由：界面上的勾选状态来自 App 侧的读取，而**真正执行拦截的那份列表在
     * system_server 里**。两者不一致时，光看界面永远分不清"没生效"和"配置没送过去"。
     */
    const val PROBE_KEY = "_hide_accessibility_probe"

    /** 日志 TAG */
    const val TAG = "HideAccessibility"
}
