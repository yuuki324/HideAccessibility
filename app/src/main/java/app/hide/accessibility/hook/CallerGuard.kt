package app.hide.accessibility.hook

import app.hide.accessibility.data.ConfigStore
import app.hide.accessibility.utils.PackageNames
import java.util.concurrent.atomic.AtomicLong

/**
 * 「这次 Binder 调用的发起方，是否在被隐藏列表里」的唯一判定点。
 *
 * 三个 hook 都调用这里，所有判据集中在一处。
 *
 * 判定链：`Binder.getCallingUid()` → `IPackageManager.getPackagesForUid(appId)` →
 * 逐个包名与隐藏列表求交。**任何一环失败都返回 null（不拦截）**——
 * 宁可漏拦也不能误拦：误拦会让未被勾选的应用也被骗。
 */
object CallerGuard {

    /** 累计决策计数，用于 logcat 诊断 */
    val queries = AtomicLong()
    val hits = AtomicLong()

    /** 最近一次命中的包名，用于 logcat 诊断 */
    @Volatile
    var lastHit: String? = null
        private set

    /** 最近一次实际问到的调用方包名（不管有没有命中），用于 logcat 诊断 */
    @Volatile
    var lastCaller: String? = null
        private set

    /**
     * 调用方在被隐藏列表里时返回它的包名，否则返回 null。
     *
     * 用「任一包名命中」而不是「第一个包名命中」：共享 UID 下
     * `getNameForUid()` 只给一个名字，容易漏判；`getPackagesForUid()` 给全部。
     */
    fun blockedCallerOrNull(): String? {
        queries.incrementAndGet()
        val packages = PackageNames.callerPackages()
        if (packages.isEmpty()) return null
        lastCaller = packages.first()
        for (pkg in packages) {
            if (ConfigStore.isBlocked(pkg)) {
                hits.incrementAndGet()
                lastHit = pkg
                return pkg
            }
        }
        return null
    }

    /** 诊断读数。注意 [ConfigStore.blockedSnapshot] 就是 system_server 侧当前真正生效的列表。 */
    fun debugSnapshot(): String = buildString {
        append("queries=").append(queries.get())
        append("  hits=").append(hits.get()).append('\n')
        append("lastCaller=").append(lastCaller ?: "-").append('\n')
        append("lastHit=").append(lastHit ?: "-").append('\n')
        append("blocked(").append(ConfigStore.blockedSnapshot().size).append(")=")
        append(ConfigStore.blockedSnapshot().sorted().joinToString(","))
    }
}
