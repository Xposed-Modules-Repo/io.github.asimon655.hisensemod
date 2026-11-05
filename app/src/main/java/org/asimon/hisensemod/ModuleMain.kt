package org.asimon.hisensemod

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import org.asimon.hisensemod.hooks.EinkNotificationHooks
import org.asimon.hisensemod.hooks.LauncherHooks
import org.asimon.hisensemod.hooks.LockscreenNotifications
import org.asimon.hisensemod.hooks.MemoHooks
import org.asimon.hisensemod.hooks.NetworkControlHooks
import org.asimon.hisensemod.hooks.PowerManagerHooks
import org.asimon.hisensemod.hooks.RecentsHooks
import org.asimon.hisensemod.hooks.SystemUiHooks

/**
 * Entry point (listed in META-INF/xposed/java_init.list).
 *
 * Combines the former MelloSystemUI (v4.3), einkify and NotificationObsrv projects.
 */
class ModuleMain : XposedModule() {

    /** Packages hooked in this process, kept so hooks can be re-installed after a hot reload. */
    private val loaded = mutableListOf<Pair<String, ClassLoader>>()

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        Mod.module = this
        install(SYSTEM, param.classLoader)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        Mod.module = this
        // Only the main package of each process; skip packages loaded into it as plugins.
        if (!param.isFirstPackage) return
        install(param.packageName, param.classLoader)
    }

    private fun install(pkg: String, cl: ClassLoader) {
        loaded += pkg to cl
        Mod.log("installing hooks for $pkg (framework $frameworkName $frameworkVersion, API $apiVersion)")
        when (pkg) {
            SYSTEM -> EinkNotificationHooks.installSystemServer(cl)
            "com.android.systemui" -> {
                SystemUiHooks.install(cl)
                LockscreenNotifications.install(cl)
            }
            "com.hmct.recents" -> RecentsHooks.install(cl)
            "com.hmct.vision" -> LauncherHooks.install(cl)
            "com.hmct.hsmemo" -> MemoHooks.install(cl)
            "com.android.networkcontrol" -> NetworkControlHooks.install(cl)
            "com.android.powermanager" -> PowerManagerHooks.install(cl)
            // Any other app the user adds to the scope gets the per-app e-ink notification hook.
            else -> EinkNotificationHooks.installInApp()
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        // Hand the target class loaders (framework objects, not ours) to the next generation.
        param.setSavedInstanceState(ArrayList(loaded.map { arrayOf<Any>(it.first, it.second) }))
        Mod.shutdown()
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        Mod.module = this
        param.oldHookHandles.forEach { it.unhook() }
        @Suppress("UNCHECKED_CAST")
        val saved = param.savedInstanceState as? List<Array<Any>> ?: return
        for (entry in saved) install(entry[0] as String, entry[1] as ClassLoader)
        Mod.log("hot reloaded in ${param.processName}")
    }

    companion object {
        const val SYSTEM = "system"
    }
}

/** Process-wide access to the module instance plus small logging/prefs helpers. */
object Mod {
    const val TAG = "HisenseMod"

    lateinit var module: XposedModule

    private var prefsCache: SharedPreferences? = null
    private val prefListeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val shutdownActions = mutableListOf<() -> Unit>()

    /** Remote (read-only) preferences written by the settings app; null if unsupported. */
    val prefs: SharedPreferences?
        get() {
            prefsCache?.let { return it }
            return runCatching { module.getRemotePreferences(Prefs.GROUP) }
                .onFailure { log("remote preferences unavailable: $it") }
                .getOrNull()
                ?.also { prefsCache = it }
        }

    fun onPrefsChanged(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        val p = prefs ?: return
        p.registerOnSharedPreferenceChangeListener(listener)
        prefListeners += listener
    }

    /** Run when this module generation is retired (hot reload). */
    fun onShutdown(action: () -> Unit) {
        shutdownActions += action
    }

    fun shutdown() {
        prefsCache?.let { p -> prefListeners.forEach { p.unregisterOnSharedPreferenceChangeListener(it) } }
        prefListeners.clear()
        shutdownActions.forEach { runCatching(it) }
        shutdownActions.clear()
        prefsCache = null
    }

    fun log(msg: String) = module.log(Log.INFO, TAG, msg)
    fun warn(msg: String, t: Throwable? = null) = module.log(Log.WARN, TAG, msg, t)
}
