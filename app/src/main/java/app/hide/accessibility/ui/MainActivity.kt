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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.hide.accessibility.BuildConfig
import app.hide.accessibility.Const
import app.hide.accessibility.R
import app.hide.accessibility.data.ConfigStore
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
    private lateinit var statusServer: TextView
    private lateinit var switchHideIcon: Switch
    private lateinit var languageRow: TextView

    // 这些文本若只靠布局里的 @string 静态绑定，切语言时是否跟着变取决于 Activity
    // 重建后拿到的 Resources 是否已是新 locale，会出现"一部分变了、一部分没变"。
    // 所以统一改成每次 onResume 用 applicationContext 取串后重设（见 renderTexts）。
    private lateinit var buttonApps: android.widget.Button
    private lateinit var buttonCopyDiag: android.widget.Button
    private lateinit var linkProject: TextView
    private lateinit var textAttribution: TextView
    private lateinit var labelSelfCheck: TextView
    private lateinit var labelAccessibility: TextView
    private lateinit var labelSettings: TextView
    private lateinit var labelProbe: TextView
    private lateinit var labelHideIcon: TextView
    private lateinit var labelLanguageRow: TextView
    private lateinit var warningReboot: TextView
    private val statusHandler = Handler(Looper.getMainLooper())

    /** 最近一次探针回答：复制诊断信息时直接复用，避免再打一次 Binder 调用 */
    private var lastProbe: Activation.ProbeReport? = null

    /**
     * 最近一次自检的结论：框架服务在，但 hook 看起来并没生效。
     *
     * 与「作用域没勾」是两码事：那个能从 `XposedService.scope` 直接读出来，而这个要靠让
     * 模块自己问一次无障碍状态才能判断。详见 [Activation.runSelfCheck]。
     */
    private var hookCheckFailed = false

    /** 是否已有自检在跑：自检要读设备状态，重叠起来没有意义，还会让两次结果互相覆盖 */
    @Volatile
    private var selfCheckRunning = false

    override fun onCreate() {
        setContentView(R.layout.activity_main)

        statusTitle = findViewById(R.id.status_title)
        statusFramework = findViewById(R.id.status_framework)
        statusModule = findViewById(R.id.status_module)
        statusSystem = findViewById(R.id.status_system)
        statusServer = findViewById(R.id.status_server)
        switchHideIcon = findViewById(R.id.switch_hide_icon)

        buttonApps = findViewById(R.id.button_apps)
        buttonCopyDiag = findViewById(R.id.button_copy_diag)
        linkProject = findViewById(R.id.link_project)
        textAttribution = findViewById(R.id.text_attribution)
        labelSelfCheck = findViewById(R.id.label_self_check)
        labelAccessibility = findViewById(R.id.label_accessibility)
        labelSettings = findViewById(R.id.label_settings)
        labelProbe = findViewById(R.id.label_probe)
        labelHideIcon = findViewById(R.id.label_hide_icon)
        labelLanguageRow = findViewById(R.id.label_language_row)
        warningReboot = findViewById(R.id.warning_reboot)

        // 语言相关文本统一在 renderTexts() 里渲染，onCreate 与 onResume 都调一次：
        // 迁到前台时补一次，能盖住"系统没重建 Activity"和"重建时资源还是旧 locale"两种情况。
        renderTexts()

        buttonApps.setOnClickListener { openApps() }
        buttonCopyDiag.setOnClickListener { copyDiagnostics() }
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

        // 自检区的读数由 renderSelfCheck() 负责刷新（它必须紧挨着读取做刷新，
        // 否则 `isEnabled` 那个进程内缓存会让人看到旧值），这里不再单独重连一次。
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
        // 模块在本次开机之后安装/更新过：LSPosed 只在进程启动时加载模块，所以要么还没加载、
        // 要么 system_server 里还是更新前的旧实例。两种情况都需要重启，但说法不一样：
        //  - 探针无回答：当前系统还没加载它；
        //  - 探针有回答：旧实例还在跑，隐藏列表/自检读数可能与新界面不同步。
        // 以前这里只按 installedAfterBoot 显示同一句话，会把"旧实例还在跑"误报成"没加载"；
        // 但也不能反过来因为"有回答"就把提示整个藏掉——那样重装后用户根本不知道要重启。
        val probeNow = Activation.probeReport(this)
        lastProbe = probeNow
        val installedAfterBoot = Activation.installedAfterBoot(this)
        warningReboot.visibility = if (installedAfterBoot) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        warningReboot.text = applicationContext.getString(
            if (probeNow.answered) R.string.warning_need_reboot_loaded else R.string.warning_need_reboot,
        )
        // 先按当前状态画一次自检区；自检跑完还会再画一次（那时读数才是准的）
        renderSelfCheck()
        startSelfCheck()
    }

    /**
     * 起一次 hook 自检。
     *
     * 自检要临时把模块自己放进隐藏列表、等 system_server 同步、再还原，全程同步等待，
     * 所以**不能放在主线程**。跑完回主线程刷新状态标题与自检区。
     *
     * 用 [selfCheckRunning] 挡住重叠：自检会临时改配置，两次重叠会互相覆盖对方的还原动作。
     */
    private fun startSelfCheck() {
        if (selfCheckRunning) return
        selfCheckRunning = true
        Thread {
            val working = try {
                Activation.runSelfCheck(applicationContext)
            } finally {
                selfCheckRunning = false
            }
            runOnUiThread {
                // 只有**确认**没生效（false）才提示；无法判断（null）时不妄下结论，
                // 否则会在"设备本来就没开无障碍"这类情况下误报。
                hookCheckFailed = working == false
                renderStatus(Activation.state, Activation.current()?.let { Activation.hasSystemScope(it) } == true)
                // 自检期间临时改过隐藏列表；回来后重画自检区，此时读到的已经是还原后的真值
                renderSelfCheck()
            }
        }.start()
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
        buttonCopyDiag.text = app.getString(R.string.button_copy_diagnostics)
        linkProject.text = app.getString(R.string.link_project)
        textAttribution.text = app.getString(R.string.about_attribution)
        labelSelfCheck.text = app.getString(R.string.group_self_check)
        labelAccessibility.text = app.getString(R.string.label_accessibility_state)
        labelSettings.text = app.getString(R.string.label_settings_secure)
        labelProbe.text = app.getString(R.string.label_system_server_side)
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
        // 探针回答是"system_server 里到底有没有在拦"的唯一直接证据：
        // 它能回答，说明 Transport#call hook 正在跑；没有回答，说明模块没被加载进
        // system_server（或 hook 没装上）——这与"App 能不能连上框架服务"是两条独立链路。
        val probe = Activation.probeReport(this)
        lastProbe = probe
        when (state) {
            Activation.State.UNKNOWN -> {
                statusTitle.text = app.getString(R.string.status_checking)
                statusTitle.setTextColor(getColor(R.color.text_secondary))
            }
            Activation.State.ACTIVE -> when {
                // 作用域里没有 system_server：配置层面的问题，先提示这个
                !hasScope -> {
                    statusTitle.text = app.getString(R.string.status_scope_missing)
                    statusTitle.setTextColor(getColor(R.color.warn))
                }
                // 框架服务在，system_server 却没有任何回应——这是"显示已激活、
                // 全都不生效"最常见的样子：模块根本没被加载进 system_server。
                !probe.answered -> {
                    statusTitle.text = app.getString(R.string.status_server_unreachable)
                    statusTitle.setTextColor(getColor(R.color.warn))
                }
                // 有回应，但某个 hook 的安装结果是 MISSING/FAILED。
                !probe.hookHealthy -> {
                    statusTitle.text = app.getString(R.string.status_hook_failed)
                    statusTitle.setTextColor(getColor(R.color.warn))
                }
                // 框架服务在，可是模块自己问无障碍仍然得到"开着"——hook 没真的装上。
                // 这时候绝不能报「已激活」，那是假激活：用户会以为已经生效了。
                hookCheckFailed -> {
                    statusTitle.text = app.getString(R.string.status_self_check_failed)
                    statusTitle.setTextColor(getColor(R.color.warn))
                }
                else -> {
                    statusTitle.text = app.getString(R.string.status_activated)
                    statusTitle.setTextColor(getColor(R.color.ok))
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
        // 单独一行显示 system_server 侧的状态：标题可能因为其它原因变红，
        // 但"system_server 有没有加载"这一条必须始终可见，不能藏在下面的自检区里。
        statusServer.text = if (probe.answered) {
            app.getString(R.string.label_server_state, app.getString(R.string.server_loaded, probe.pid ?: -1))
        } else {
            app.getString(R.string.label_server_state, app.getString(R.string.server_not_loaded))
        }
    }

    /** 把设备真实状态打出来：被隐藏的应用跑这个界面时应当看到"空"，而模块自己始终看到真实值 */
    private fun renderSelfCheck() {
        // 两条读数都必须先绕开本进程里的缓存，否则"取消勾选后读数不变"会被误读成 hook 失效：
        //  - AccessibilityManager.isEnabled 是进程内缓存（注册 client 时由 addClient 的返回值
        //    定下），所以先要一份重新注册过的实例；
        //  - Settings.Secure.getString 另有一层 NameValueCache，改成直连 SETTINGS 提供者读。
        val am = Activation.freshAccessibilityManager(this)
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
            append(Activation.readSecureDirect(this@MainActivity, Settings.Secure.ACCESSIBILITY_ENABLED) ?: "<null>")
            append('\n')
            append("enabled_accessibility_services:\n")
            val services = Activation.readSecureDirect(
                this@MainActivity,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            if (services.isNullOrEmpty()) {
                append("  <empty>\n")
            } else {
                services.split(':')
                    .filter { it.isNotBlank() }
                    .sorted()
                    .forEach { append("  ").append(it).append('\n') }
            }
        }
        findViewById<TextView>(R.id.check_settings).text = secureText

        // 上一块读的是"模块 App 问系统拿到什么"，这一块读的是"system_server 里真正在拦截的那份
        // 状态"。两者对不上（例如这里 blocked 含模块自己、界面里却没勾选）就说明配置没送达
        // system_server，而不是 hook 没生效——没有这块读数，这两种情况从界面上看不出区别。
        val probe = lastProbe ?: Activation.probeReport(this)
        lastProbe = probe
        findViewById<TextView>(R.id.check_probe).text = describeProbe(probe)
    }

    /**
     * 把探针回答渲染成给人看的多行文本。
     *
     * 没有回答时不再只丢一句英文，而是把"为什么这很重要、下一步做什么"一起写出来：
     * 这是本次反馈里最常见的情形，也是用户唯一能自己判断"是不是没重启"的地方。
     */
    private fun describeProbe(probe: Activation.ProbeReport): String {
        val app = applicationContext
        if (!probe.answered) return app.getString(R.string.probe_no_answer)
        return buildString {
            // 这里原本是一整段连续的多行等宽文本，hook、计数、原始回答混在一起，
            // 用户反馈"很乱"。现在拆成三段：hook 安装情况 / 运行状态 / 原始回答，
            // 段与段之间空一行 + 一条分界线。
            val divider = "\n$DIVIDER\n"

            // 第 1 段：三个 hook 拆成三行，每行都带上"这个 hook 是干嘛的"，
            // 这样用户不必知道 addClient 是什么方法，也能判断是哪一层没装上。
            var hookLines = 0
            probe.addClientStatus?.let {
                append(app.getString(R.string.probe_hook_add_client, hookStatusText(it))).append('\n')
                hookLines++
            }
            probe.serviceListStatus?.let {
                append(app.getString(R.string.probe_hook_service_list, hookStatusText(it))).append('\n')
                hookLines++
            }
            probe.transportStatus?.let {
                append(app.getString(R.string.probe_hook_transport, hookStatusText(it))).append('\n')
                hookLines++
            }

            // 第 2 段：运行状态
            val status = buildString {
                probe.pid?.let {
                    append(app.getString(R.string.probe_pid, it)).append('\n')
                }
                probe.blockedCount?.let {
                    append(app.getString(R.string.probe_blocked, it)).append('\n')
                }
                // 界面里的列表和 system_server 真正生效的列表各算一份，不一致就直接写出来。
                // 重装后没重启时，system_server 里还是更新前的实例，这两个数字会对不上——
                // 这正是"我明明把模块自己移出了、读数却还是没开"的根源，不写出来用户只能猜。
                val appSideCount = ConfigStore.read(this@MainActivity).size
                probe.blockedCount?.let {
                    append(app.getString(R.string.probe_list_compare, appSideCount, it)).append('\n')
                    if (appSideCount != it) {
                        append(app.getString(R.string.probe_list_out_of_sync)).append('\n')
                    }
                }
                if (probe.queries != null || probe.hits != null) {
                    append(app.getString(R.string.probe_counters, probe.queries ?: 0L, probe.hits ?: 0L)).append('\n')
                }
                probe.resolver?.takeIf { it.isNotBlank() }?.let {
                    append(app.getString(R.string.probe_resolver, it)).append('\n')
                }
            }
            if (hookLines > 0 && status.isNotEmpty()) append(divider)
            append(status)
            if (status.isNotEmpty()) append(divider)

            // 第 3 段：原始回答
            append(app.getString(R.string.probe_line_raw)).append('\n')
            append(probe.raw ?: "")
        }
    }

    /** 把 hook 的原始状态值翻成界面语言；Transport 成功时是 `call(...)`，同样算"已装上" */
    private fun hookStatusText(status: String): String {
        val app = applicationContext
        return when {
            status == "ok" -> app.getString(R.string.hook_status_ok)
            status.startsWith("call(") -> app.getString(R.string.hook_status_ok)
            status.contains("MISSING") -> app.getString(R.string.hook_status_missing)
            status.contains("FAILED") -> app.getString(R.string.hook_status_failed)
            status.contains("not found") -> app.getString(R.string.hook_status_class_missing)
            status.contains("not installed") -> app.getString(R.string.hook_status_failed)
            else -> status
        }
    }

    /**
     * 一键复制诊断信息。
     *
     * 目的就是让反馈者不必再进 LSPosed 导一个几十 MB、还可能被 logd 吞掉一半的日志包：
     * 这里把"能不能加载、加载成什么样、配置送没送到"压成一段可粘贴的文本。
     * 内容里不含隐藏列表的包名明细（那是用户隐私），只给数量与关键状态。
     */
    private fun copyDiagnostics() {
        val app = applicationContext
        val service = Activation.current()
        val probe = lastProbe ?: Activation.probeReport(this)
        lastProbe = probe
        val uptimeSeconds = SystemClock.elapsedRealtime() / 1000
        val text = buildString {
            append("HideAccessibility ").append(BuildConfig.VERSION_NAME)
            append(" (versionCode ").append(BuildConfig.VERSION_CODE).append(")\n")
            append("Android ").append(Build.VERSION.RELEASE)
            append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Build: ").append(Build.DISPLAY).append('\n')
            append("Fingerprint: ").append(Build.FINGERPRINT).append('\n')
            append("Framework: ")
            if (service == null) {
                append("not connected")
            } else {
                append(service.frameworkName).append(' ')
                    .append(service.frameworkVersion).append(" (API ").append(service.apiVersion).append(')')
            }
            append('\n')
            append("Scope system/android: ")
                .append(service != null && Activation.hasSystemScope(service)).append('\n')
            append("Uptime: ").append(uptimeSeconds / 3600).append("h ")
                .append(uptimeSeconds % 3600 / 60).append("m\n")
            append("Installed after boot: ").append(Activation.installedAfterBoot(this@MainActivity)).append('\n')
            append("Hidden list (app side): ").append(ConfigStore.read(this@MainActivity).size)
                .append(" package(s)\n")
            append("system_server probe: ").append(if (probe.answered) "answered" else "NO ANSWER").append('\n')
            if (probe.answered) {
                append("  hook=").append(probe.hook ?: "-").append('\n')
                append("  pid=").append(probe.pid ?: -1).append('\n')
                append("  blocked=").append(probe.blockedCount ?: 0).append('\n')
                append("  blockedSelf=").append(if (probe.blockedSelf) 1 else 0).append('\n')
                append("  queries=").append(probe.queries ?: 0L)
                    .append(" hits=").append(probe.hits ?: 0L).append('\n')
                append("  resolver=").append(probe.resolver ?: "-").append('\n')
            } else {
                append("  (module not loaded into system_server, or the Settings hook is not installed)\n")
            }
            append("self-check failed: ").append(hookCheckFailed).append('\n')
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            // 拿不到剪贴板就明确报失败，不能"没复制"还弹一句已复制
            toast(app.getString(R.string.diag_copy_failed, "clipboard unavailable"))
            return
        }
        runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("HideAccessibility diagnostics", text))
        }.onSuccess {
            toast(app.getString(R.string.diag_copied))
        }.onFailure {
            toast(app.getString(R.string.diag_copy_failed, it.message ?: ""))
        }
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

        /**
         * 探针区各段之间的分界线。
         *
         * 用 em dash 而不是 `─`/`=`：`—` 已经在文案里用了，字体一定有；
         * 28 个在 monospace 12sp 下接近正文宽度，又不会折行。
         */
        val DIVIDER = "—".repeat(28)

        /** 与 Activation 内部的 UNKNOWN 超时保持一致：超时后把"检测中"换成明确结论 */
        const val UNKNOWN_TIMEOUT_MS = 1_500L

        /** 语言切换后补一次重绘的延时：够 `setApplicationLocales()` 生效，又不至于让用户察觉 */
        const val LOCALE_REFRESH_DELAY_MS = 300L

        /** 延时重绘任务的标识，切换语言时用它取消上一次未执行的重绘 */
        val LOCALE_REFRESH_TOKEN = Any()
    }
}
