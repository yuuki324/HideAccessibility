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

package app.hide.accessibility.ui

import android.app.Activity
import android.os.Bundle

/**
 * 极简基类：不引入 androidx，系统栏完全交给主题。
 *
 * 用 framework 的 `Theme.DeviceDefault.DayNight`（见 res/values/themes.xml 与 values-night），
 * 深浅色跟随系统，不需要 AppCompatDelegate。
 *
 * 这里刻意**不**在代码里设置状态栏/导航栏的颜色与图标亮暗：
 *
 *  - `Window.setStatusBarColor` / `setNavigationBarColor` 自 API 35 起已弃用——系统强制
 *    edge-to-edge 后，颜色由主题决定，代码设置在较新系统上不再生效；
 *  - `View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR` 自 API 30 起已弃用。
 *
 * 主题里已有 `statusBarColor` / `navigationBarColor` / `windowLightStatusBar` /
 * `windowLightNavigationBar` 四项，随 day/night 自动切换，效果与按 API 逐个分支设置一致，
 * 而且不产生弃用调用。minSdk 27 正好满足 `windowLightNavigationBar`（API 27+）的要求。
 */
abstract class BaseActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onCreate()
    }

    /** 子类在这里 setContentView + 初始化 */
    abstract fun onCreate()
}
