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

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.hide.accessibility.BuildConfig
import app.hide.accessibility.Const
import app.hide.accessibility.R
import app.hide.accessibility.utils.Activation
import app.hide.accessibility.utils.LocaleHelper

/**
 * 主界面：激活状态、系统自检、入口。
 *
 * 「当前设备实际状态」区展示的两条对照项（AccessibilityManager / Settings.Secure）
 * 正是模块在 system_server 里拦截的两条通道，用来验证 hook 是否真的生效。
 */
class MainActivity : BaseActivity() {

    private lateinit var statusTitle: TextView
    private lateinit var statusFramework: TextView
    private lateinit var statusModule: TextView
    private lateinit var statusSystem: TextView
    private lateinit var switchHideIcon: Switch
    private lateinit var languageRow: TextView

    // 这些文本若只靠布局里的 @string 静态绑定，切语言时是否跟着变取决于 Activity
    // 重建后拿到的 Resources 是否已是新 locale，会出现"一部分变了、一部分没变"。
    // 所以统一改成每次 onResume 用 applicationContext 取串后重设（见 renderTexts）。
    private lateinit var buttonApps: android.widget.Button
    private lateinit var linkProject: TextView
    private lateinit var textAttribution: TextView
    private lateinit var labelSelfCheck: TextView
    private lateinit var labelAccessibility: TextView
    private lateinit var labelSettings: TextView
    private lateinit var labelHideIcon: TextView
    private lateinit var labelLanguageRow: TextView
    private lateinit var warningReboot: TextView
    private val statusHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        setContentView(R.layout.activity_main)

        statusTitle = findViewById(R.id.status_title)
        statusFramework = findViewById(R.id.status_framework)
        statusModule = findViewById(R.id.status_module)
        statusSystem = findViewById(R.id.status_system)
        switchHideIcon = findViewById(R.id.switch_hide_icon)

        buttonApps = findViewById(R.id.button_apps)
        linkProject = findViewById(R.id.link_project)
        textAttribution = findViewById(R.id.text_attribution)
        labelSelfCheck = findViewById(R.id.label_self_check)
        labelAccessibility = findViewById(R.id.label_accessibility)
        labelSettings = findViewById(R.id.label_settings)
        labelHideIcon = findViewById(R.id.label_hide_icon)
        labelLanguageRow = findViewById(R.id.label_language_row)
        warningReboot = findViewById(R.id.warning_reboot)

        // 语言相关文本统一在 renderTexts() 里渲染，onCreate 与 onResume 都调一次：
        // 迁到前台时补一次，能盖住"系统没重建 Activity"和"重建时资源还是旧 locale"两种情况。
        renderTexts()

        buttonApps.setOnClickListener { openApps() }
        linkProject.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL))) }
                .onFailure { toast("cannot open browser") }
        }
        languageRow = findViewById(R.id.text_language_value)
        renderLanguage()
        // 整行可点：点左边文字也生效，而不是只有右侧控件那一小块
        findViewById<android.view.View>(R.id.row_language).setOnClickListener { showLanguageDialog() }

        // 标签是「隐藏桌面图标」，所以**打开开关 = 图标隐藏**。
        // 这里取反是关键：isLauncherIconShown() 为 true 表示图标**正在显示**，
        // 直接赋给这个开关会让语义反过来（打开却显示、关掉才隐藏）。
        switchHideIcon.isChecked = isLauncherIconShown().not()
        switchHideIcon.setOnCheckedChangeListener { _, checked ->
            // 只有"用户操作"才写回系统。整行点击会改 isChecked，这里必须能区分：
            // 用 setLauncherIcon 内部比对当前实际状态，值没变就不动，天然幂等。
            setLauncherIcon(shown = !checked)
        }
        findViewById<android.view.View>(R.id.row_hide_icon).setOnClickListener {
            // 行点击 = 切换开关；OnCheckedChangeListener 负责落地
            switchHideIcon.isChecked = !switchHideIcon.isChecked
        }
    }

    override fun onResume() {
        super.onResume()
        // 语言相关文本在每次回到前台时重设一次：切语言后即便系统没有重建 Activity、
        // 或重建时拿到的还是旧 locale 的 Resources，这里也会用最新的 per-app locale 覆盖。
        renderTexts()
        // 图标状态可能被外部改动（例如从 LSPosed 管理器进来），每次回前台同步一次显示
        switchHideIcon.isChecked = isLauncherIconShown().not()
        Activation.observe { service ->
            runOnUiThread { renderStatus(Activation.state, service?.let { Activation.hasSystemScope(it) } == true) }
        }
        // observe 的回调只在"服务绑定/断开"时触发；如果框架压根不给服务，
        // 就永远不会有回调。这里补一次延时重绘，让 UNKNOWN 超时后能落到"未激活"。
        // 正常路径下服务早已绑定（ModuleApp.onCreate 提前注册过监听），这一枪是空放。
        statusHandler.removeCallbacksAndMessages(null)
        statusHandler.postDelayed({
            renderStatus(Activation.state, Activation.current()?.let { Activation.hasSystemScope(it) } == true)
        }, UNKNOWN_TIMEOUT_MS)
        // 模块在本次开机之后安装/更新过：LSPosed 只在进程启动时加载模块，必须重启才生效。
        // 这里不依赖 Activation 的回调（那条路只在服务绑定/断开时触发），每次回前台判一次。
        warningReboot.visibility = if (Activation.installedAfterBoot(this)) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        renderSelfCheck()
    }

    /**
     * 所有"跟随语言"的文本都从这里出。
     *
     * 取串一律走 `applicationContext`：per-app locale 是应用级状态，
     * `applicationContext` 始终按最新的应用语言解析，而 Activity 的 Resources
     * 在 `setApplicationLocales()` 刚生效、Activity 又被重建时可能仍是旧配置——
     * 那正是"切了语言只有一部分文本变"的原因。
     */
    private fun renderTexts() {
        val app = applicationContext
        buttonApps.text = app.getString(R.string.button_manage_apps)
        linkProject.text = app.getString(R.string.link_project)
        textAttribution.text = app.getString(R.string.about_attribution)
        labelSelfCheck.text = app.getString(R.string.group_self_check)
        labelAccessibility.text = app.getString(R.string.label_accessibility_state)
        labelSettings.text = app.getString(R.string.label_settings_secure)
        labelHideIcon.text = app.getString(R.string.switch_hide_icon)
        // 语言行的标签随界面语言变化，但每种语言都带英文（见各语言包 label_language），
        // 保证误判语言时仍能靠 "Language" 找到出口。
        labelLanguageRow.text = app.getString(R.string.label_language)

        statusModule.text = app.getString(R.string.status_module, BuildConfig.VERSION_NAME)
        statusSystem.text = app.getString(
            R.string.status_system,
            "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ${Build.DISPLAY}",
        )
        // 标题也在 renderStatus 里设置，这里不重复；语言变了标题需要重画，交给 onResume 的 observe 回调
    }

    override fun onPause() {
        statusHandler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    /**
     * 渲染激活状态。
     *
     * 注意 [Activation.State.UNKNOWN] 分支：冷启动时框架服务的绑定是异步的，
     * 此时**不能**画成"未激活"——否则会先闪一下未激活再翻成已激活。
     * UNKNOWN 应当显示"检测中"并保持中性颜色。
     */
    private fun renderStatus(state: Activation.State, hasScope: Boolean) {
        val service = Activation.current()
        // 取串走 applicationContext，理由同 renderTexts()：Activity 的 Resources
        // 在切语言后可能还是旧配置，而标题必须立刻跟上。
        val app = applicationContext
        when (state) {
            Activation.State.UNKNOWN -> {
                statusTitle.text = app.getString(R.string.status_checking)
                statusTitle.setTextColor(getColor(R.color.text_secondary))
            }
            Activation.State.ACTIVE -> {
                if (hasScope) {
                    statusTitle.text = app.getString(R.string.status_activated)
                    statusTitle.setTextColor(getColor(R.color.ok))
                } else {
                    statusTitle.text = app.getString(R.string.status_scope_missing)
                    statusTitle.setTextColor(getColor(R.color.warn))
                }
            }
            Activation.State.INACTIVE -> {
                statusTitle.text = app.getString(R.string.status_not_activated)
                statusTitle.setTextColor(getColor(R.color.warn))
            }
        }
        statusFramework.text = if (service == null) {
            app.getString(R.string.status_framework, "-", "-", 0)
        } else {
            app.getString(R.string.status_framework, service.frameworkName, service.frameworkVersion, service.apiVersion)
        }
    }

    /** 把设备真实状态打出来：被隐藏的应用跑这个界面时应当看到"空"，而模块自己始终看到真实值 */
    private fun renderSelfCheck() {
        val am = getSystemService(AccessibilityManager::class.java)
        val managerText = if (am == null) {
            "AccessibilityManager unavailable"
        } else {
            buildString {
                append("isEnabled: ").append(am.isEnabled).append('\n')
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .map { it.id }
                    .sorted()
                    .forEach { append("service: ").append(it).append('\n') }
                append("touchExplorationEnabled: ").append(am.isTouchExplorationEnabled)
            }
        }
        findViewById<TextView>(R.id.check_accessibility).text = managerText

        val secureText = buildString {
            append("accessibility_enabled: ")
            append(
                runCatching { Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED) }
                    .getOrElse { "error(${it.message})" },
            ).append('\n')
            append("enabled_accessibility_services:\n")
            runCatching { Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) }
                .getOrNull()
                ?.split(':')
                ?.filter { it.isNotBlank() }
                ?.sorted()
                ?.forEach { append("  ").append(it).append('\n') }
                ?: append("  <null>\n")
        }
        findViewById<TextView>(R.id.check_settings).text = secureText
    }

    private fun openApps() {
        val service = Activation.current()
        when {
            service == null -> toast(getString(R.string.status_not_activated))
            !Activation.hasSystemScope(service) -> toast(getString(R.string.status_scope_missing))
            else -> startActivity(Intent(this, AppsActivity::class.java))
        }
    }

    // ------------------------------------------------------------------ 桌面图标

    private val launcherComponent: ComponentName
        get() = ComponentName(Const.MODULE_PACKAGE, "${Const.MODULE_PACKAGE}.Launcher")

    private fun isLauncherIconShown(): Boolean = runCatching {
        packageManager.getComponentEnabledSetting(launcherComponent) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }.getOrDefault(true)

    /**
     * 设置桌面图标显示/隐藏。
     *
     * 幂等：先比对当前实际状态，已是目标状态就直接返回。
     * 这样"整行点击切换开关"与"开关自身回调"两条路径不会互相打架。
     */
    private fun setLauncherIcon(shown: Boolean) {
        if (isLauncherIconShown() == shown) return
        runCatching {
            packageManager.setComponentEnabledSetting(
                launcherComponent,
                if (shown) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }.onFailure { toast("cannot change launcher icon: ${it.message}") }
    }

    // ------------------------------------------------------------------ 语言

    private fun renderLanguage() {
        // 取串走 applicationContext：per-app locale 刚变时 Activity 的 Resources 可能还是旧的
        languageRow.text = LocaleHelper.labelOf(applicationContext, LocaleHelper.currentTag(this))
        // 低版本没有 per-app language，选也白选——直接说明，不给假开关
        languageRow.alpha = if (LocaleHelper.isSupported) 1f else 0.5f
    }

    private fun showLanguageDialog() {
        if (!LocaleHelper.isSupported) {
            toast(getString(R.string.language_unsupported))
            return
        }
        val app = applicationContext
        val tags = LocaleHelper.options
        // 标签一律用 applicationContext 取，否则弹窗会停在打开那一刻的旧语言
        val labels = tags.map { LocaleHelper.labelOf(app, it) }.toTypedArray()
        val current = tags.indexOf(LocaleHelper.currentTag(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            // 标题同样随界面语言变化；各语言包的 label_language 都带英文，不会认不出来
            .setTitle(R.string.label_language)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                LocaleHelper.apply(this, tags[which])
                // 系统重建 Activity 是异步的，这里先立刻按新选择重画一次，
                // 再补一次延时重绘兜底，否则切回"跟随系统"时界面会停在旧文案上。
                renderAfterLocaleChange()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 语言改动后重画一次，并在稍后再补一次。
     *
     * 第一次立即重画，让用户马上看到新语言；第二次延时重画，覆盖
     * `setApplicationLocales()` 异步生效、Activity 重建时序不确定的情况。
     */
    private fun renderAfterLocaleChange() {
        renderTexts()
        renderLanguage()
        renderStatus(Activation.state, Activation.current()?.let { Activation.hasSystemScope(it) } == true)
        statusHandler.removeCallbacksAndMessages(LOCALE_REFRESH_TOKEN)
        statusHandler.postAtTime(
            { renderTexts(); renderLanguage() },
            LOCALE_REFRESH_TOKEN,
            android.os.SystemClock.uptimeMillis() + LOCALE_REFRESH_DELAY_MS,
        )
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val PROJECT_URL = "https://github.com/yuuki324/HideAccessibility"

        /** 与 Activation 内部的 UNKNOWN 超时保持一致：超时后把"检测中"换成明确结论 */
        const val UNKNOWN_TIMEOUT_MS = 1_500L

        /** 语言切换后补一次重绘的延时：够 `setApplicationLocales()` 生效，又不至于让用户察觉 */
        const val LOCALE_REFRESH_DELAY_MS = 300L

        /** 延时重绘任务的标识，切换语言时用它取消上一次未执行的重绘 */
        val LOCALE_REFRESH_TOKEN = Any()
    }
}
