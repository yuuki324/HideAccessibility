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

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList

/**
 * 应用内语言切换。
 *
 * Android 13（API 33）起有 per-app language：`LocaleManager.setApplicationLocales()`，
 * 由系统持久化、只影响本应用，系统设置里的「应用语言」也会同步。
 *
 * 低版本（minSdk 27 ~ 32）没有这个 API。`Locale.setDefault()` 单独用是无效的
 * ——`Activity.recreate()` 后新 Activity 仍按系统配置解析资源，需要连 Configuration
 * 一起更新才会生效，而跨 Activity 生效还得刷 Resources。所以这里**只支持 33+ 真正切换**：
 * 低版本上入口置灰并说明原因，不提供"看起来能选、实际不生效"的假开关。
 */
object LocaleHelper {

    private const val PREFS = "ui_locale"
    private const val KEY_LANGUAGE = "language"

    /** 语言标签；空串表示跟随系统 */
    const val FOLLOW_SYSTEM = ""
    const val CHINESE_SIMPLIFIED = "zh-CN"
    const val CHINESE_TRADITIONAL = "zh-TW"
    const val ENGLISH = "en"

    /**
     * 可选项，按"跟随系统 → 简体 → 繁體 → English"排列。
     *
     * 繁體给一项 `zh-TW`。资源侧同时提供 `values-zh-rTW` 与 `values-zh-rHK`（内容相同），
     * 因为 Android 以「语言+地区」精确匹配、不跨地区回退：香港用户跟随系统时命中的是
     * `values-zh-rHK`，这里手动选繁體时命中的是 `zh-TW`，两边都有完整翻译。
     */
    val options: List<String> =
        listOf(FOLLOW_SYSTEM, CHINESE_SIMPLIFIED, CHINESE_TRADITIONAL, ENGLISH)

    /** 本机是否支持应用内语言切换（Android 13+） */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun currentTag(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, FOLLOW_SYSTEM).orEmpty()

    /**
     * 应用语言选择。仅在 [isSupported] 为 true 时真正生效。
     *
     * **不手动调用 `recreate()`**：`setApplicationLocales()` 本身就会让系统重建 Activity，
     * 再补一次会与系统那次撞车——新 Activity 起来时资源配置可能还没切过去，
     * 导致界面只有一部分文本跟着变。重画由 `MainActivity.onResume()` 兜底。
     */
    fun apply(activity: Activity, tag: String) {
        prefs(activity).edit().putString(KEY_LANGUAGE, tag).apply()
        if (!isSupported) return
        runCatching {
            val manager = activity.getSystemService(LocaleManager::class.java)
            manager.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList()
                else LocaleList.forLanguageTags(tag)
        }.onFailure {
            // 某些 ROM 会裁掉 LocaleManager；此时清掉选择，让界面回到"跟随系统"
            prefs(activity).edit().remove(KEY_LANGUAGE).apply()
        }
    }

    /**
     * 选项在界面上的显示文案。
     *
     * 一律走资源，**跟随当前界面语言**：
     * 「跟随系统」在简中是「跟随系统」、繁中是「跟隨系統」、英文是 `Follow system`。
     *
     * 三个语言名（简体中文 / 繁體中文 / English）各自用本语言的写法，中英混排时也自明；
     * `language_follow_system` 在三个语言包里都带了英文（见各语言包），
     * 所以即使界面语言被设成了看不懂的语言，也仍能认出这一项。
     */
    fun labelOf(context: Context, tag: String): String = when (tag) {
        CHINESE_SIMPLIFIED -> "简体中文"
        CHINESE_TRADITIONAL -> "繁體中文"
        ENGLISH -> "English"
        else -> context.getString(app.hide.accessibility.R.string.language_follow_system)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
