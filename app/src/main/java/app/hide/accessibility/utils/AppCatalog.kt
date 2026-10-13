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

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import app.hide.accessibility.data.ConfigStore

/** 列表用的一行数据 */
data class AppRow(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val lastUpdateTime: Long,
    val blocked: Boolean,
)

/** 枚举设备上已安装的应用并组装成列表行 */
object AppCatalog {

    fun load(context: Context, filter: Filter, keyword: String): List<AppRow> {
        val pm = context.packageManager
        val installed = installedPackages(pm)
        val blockedSet = ConfigStore.read(context)
        val key = keyword.trim()

        return installed.asSequence()
            .filter { it.packageName != "android" }
            .filter { pkg ->
                when (filter) {
                    Filter.USER -> !pkg.isSystem
                    Filter.SYSTEM -> pkg.isSystem
                    Filter.ALL -> true
                }
            }
            .filter { pkg ->
                key.isEmpty() || pkg.packageName.contains(key, ignoreCase = true) ||
                    pkg.label.contains(key, ignoreCase = true)
            }
            .map { pkg -> pkg.copy(blocked = blockedSet.contains(pkg.packageName)) }
            // 已隐藏的排前面，同一组内按最近更新时间倒序
            .sortedWith(compareByDescending<AppRow> { it.blocked }.thenByDescending { it.lastUpdateTime })
            .toList()
    }

    private fun installedPackages(pm: PackageManager): Sequence<AppRow> {
        val raw: List<PackageInfo> = runCatching {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
            else pm.getInstalledPackages(0)
        }.getOrNull() ?: emptyList()

        return raw.asSequence().mapNotNull { info ->
            val appInfo: ApplicationInfo = info.applicationInfo ?: return@mapNotNull null
            val label = runCatching { appInfo.loadLabel(pm).toString() }.getOrNull() ?: info.packageName
            AppRow(
                packageName = info.packageName,
                label = label,
                isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                lastUpdateTime = info.lastUpdateTime,
                blocked = false,
            )
        }
    }

    enum class Filter { ALL, USER, SYSTEM }
}
