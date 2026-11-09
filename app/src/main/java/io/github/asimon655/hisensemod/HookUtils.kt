package io.github.asimon655.hisensemod

import android.app.Application
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method

/*
 * Thin Kotlin helpers over the libxposed API 102 interceptor model.
 * Every install helper swallows and logs failures so one missing method
 * (e.g. after a firmware update) never breaks the rest of the module.
 */

fun ClassLoader.findClass(name: String): Class<*>? =
    runCatching { loadClass(name) }.onFailure { Mod.warn("class not found: $name") }.getOrNull()

fun Class<*>.method(name: String, vararg params: Class<*>): Method? =
    runCatching { getDeclaredMethod(name, *params).apply { isAccessible = true } }
        .onFailure { Mod.warn("method not found: ${this.name}.$name") }
        .getOrNull()

fun Class<*>.methodsNamed(name: String): List<Method> =
    declaredMethods.filter { it.name == name }.onEach { it.isAccessible = true }

/** Runs [block] before the original; [block] may return replacement args or null to keep them. */
fun hookBefore(m: Executable?, block: (Chain) -> Array<Any?>?) {
    if (m == null) return
    safeHook(m) { chain ->
        val newArgs = block(chain)
        if (newArgs != null) chain.proceed(newArgs) else chain.proceed()
    }
}

/** Runs [block] after the original; returns what [block] returns (default: original result). */
fun hookAfter(m: Executable?, block: (Chain, Any?) -> Any?) {
    if (m == null) return
    safeHook(m) { chain -> block(chain, chain.proceed()) }
}

/** Replaces the method. Return [CallOriginal] from [block] to fall through to the original. */
fun hookReplace(m: Executable?, block: (Chain) -> Any?) {
    if (m == null) return
    safeHook(m) { chain ->
        val r = block(chain)
        if (r === CallOriginal) chain.proceed() else r
    }
}

object CallOriginal

private fun safeHook(m: Executable, interceptor: (Chain) -> Any?) {
    runCatching {
        Mod.module.hook(m).intercept { chain -> interceptor(chain) }
    }.onSuccess {
        Mod.log("hooked ${m.declaringClass.simpleName}.${m.name}")
    }.onFailure {
        Mod.warn("failed to hook ${m.declaringClass.name}.${m.name}", it)
    }
}

fun installSafely(what: String, block: () -> Unit) {
    runCatching(block).onFailure { Mod.warn("could not install '$what'", it) }
}

// --- reflection ---------------------------------------------------------------------------

private fun Class<*>.findFieldRecursive(name: String): Field {
    var c: Class<*>? = this
    while (c != null) {
        runCatching { return c.getDeclaredField(name).apply { isAccessible = true } }
        c = c.superclass
    }
    throw NoSuchFieldException("${this.name}.$name")
}

@Suppress("UNCHECKED_CAST")
fun <T> Any.field(name: String): T = javaClass.findFieldRecursive(name).get(this) as T

fun Any.setField(name: String, value: Any?) = javaClass.findFieldRecursive(name).set(this, value)

fun Any.callMethod(name: String, vararg args: Any?): Any? {
    var c: Class<*>? = javaClass
    while (c != null) {
        c.declaredMethods.firstOrNull { it.name == name && it.parameterCount == args.size }?.let {
            it.isAccessible = true
            return it.invoke(this, *args)
        }
        c = c.superclass
    }
    throw NoSuchMethodException("${javaClass.name}.$name/${args.size}")
}

/** The hooked process's Application (works in every app process, not system_server). */
fun currentApplication(): Application? = runCatching {
    Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Application?
}.getOrNull()
