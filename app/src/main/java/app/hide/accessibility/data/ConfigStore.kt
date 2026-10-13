package app.hide.accessibility.data

import android.content.Context
import android.content.SharedPreferences
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
        return runCatching {
            remotePrefs = service.getRemotePreferences(Const.CONFIG_PREFS_NAME)
            channelState = "remote preferences(${Const.CONFIG_PREFS_NAME})"
            true
        }.onFailure {
            remotePrefs = null
            channelState = "取 remote preferences 失败: ${it.message}"
            Log.e(Const.TAG, "getRemotePreferences failed", it)
        }.getOrDefault(false)
    }

    /** 被 hook 进程侧登记句柄（system_server 由框架注入） */
    fun listen(prefs: SharedPreferences) {
        remote = prefs
        reload(prefs)
        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == Const.KEY_BLOCK_APPS) reload(prefs)
        }
    }

    /** 在 system_server 里读到的隐藏列表 */
    @Volatile
    private var remote: SharedPreferences? = null

    /** 当前是否拿得到配置通道 */
    val isWritable: Boolean get() = appWriter != null

    /**
     * App 侧用来写入的句柄。
     *
     * 关键点：**优先用 remote preferences**；只有在框架服务不可用时才退回本地文件，
     * 并且把这件事写进 [channelState]，让界面能提示"配置不会生效"。
     */
    @Volatile
    private var appWriter: SharedPreferences? = null

    @Volatile
    private var appFallback: SharedPreferences? = null

    fun attachApp(context: Context) {
        appFallback = context.getSharedPreferences(Const.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** App 侧当前隐藏列表 */
    fun read(context: Context): Set<String> {
        val src = remotePrefs ?: appFallback
            ?: context.getSharedPreferences(Const.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        return runCatching { src.getStringSet(Const.KEY_BLOCK_APPS, null) }.getOrNull()?.toHashSet()
            ?: defaultBlocked
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
    fun blockedSnapshot(): Set<String> = HashSet(blocked)

    fun isBlocked(packageName: String?): Boolean =
        packageName != null && packageName.isNotEmpty() && blocked.contains(packageName)

    // ------------------------------------------------------------------ system_server 侧状态

    private val blocked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 兜底默认值：只隐藏模块自己 */
    private val defaultBlocked: Set<String> = setOf(Const.MODULE_PACKAGE)

    private fun reload(prefs: SharedPreferences) {
        val snapshot = runCatching { prefs.getStringSet(Const.KEY_BLOCK_APPS, defaultBlocked) }
            .getOrNull() ?: defaultBlocked
        blocked.clear()
        blocked.addAll(snapshot)
        Log.i(Const.TAG, "block list reloaded: ${blocked.size} package(s)")
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
