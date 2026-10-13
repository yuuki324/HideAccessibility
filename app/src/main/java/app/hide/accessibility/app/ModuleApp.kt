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

package app.hide.accessibility.app

import android.app.Application
import android.content.res.Configuration
import android.util.Log
import app.hide.accessibility.Const
import app.hide.accessibility.data.ConfigStore
import app.hide.accessibility.utils.Activation

class ModuleApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 配置的读写都要经过框架通道，先登记本地兜底句柄
        ConfigStore.attachApp(this)
        // 只用于刷新激活状态、让"管理应用"入口在未激活时给出提示；
        // 绑定时会顺带接上配置通道
        runCatching { Activation.observe { service -> Log.i(Const.TAG, "framework service: $service") } }
        Log.i(Const.TAG, "module app started, blocked=${ConfigStore.read(this).size}, channel=${ConfigStore.channelState}")
    }

    /** 当前是否深色模式（用于状态栏图标明暗） */
    fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}
