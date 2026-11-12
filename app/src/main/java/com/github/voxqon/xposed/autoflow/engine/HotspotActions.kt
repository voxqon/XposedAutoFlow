package com.github.voxqon.xposed.autoflow.engine

import android.content.Context
import com.github.voxqon.xposed.autoflow.util.XLog
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Android 11+ moved tethering out of ConnectivityService into TetheringManager. */
internal object HotspotActions {
    fun set(context: Context, enabled: Boolean): Boolean? {
        val manager = context.getSystemService("tethering") ?: return null
        return request(manager, enabled)
    }

    internal fun request(manager: Any, enabled: Boolean, timeoutMs: Long = 15_000L): Boolean? {
        if (!enabled) {
            val stop = manager.javaClass.methods.firstOrNull {
                it.name == "stopTethering" &&
                    it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            } ?: return null
            stop.invoke(manager, 0)
            XLog.i("action: hotspot -> off (TetheringManager)")
            return true
        }
        val start = manager.javaClass.methods.firstOrNull {
            it.name == "startTethering" && it.parameterTypes.size == 3 &&
                !it.parameterTypes[0].isPrimitive &&
                Executor::class.java.isAssignableFrom(it.parameterTypes[1]) &&
                it.parameterTypes[2].isInterface
        } ?: return null
        val requestClass = start.parameterTypes[0]
        val builderClass = Class.forName("${requestClass.name}\$Builder", true, requestClass.classLoader)
        val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(0)
        builderClass.methods.firstOrNull { it.name == "setShouldShowEntitlementUi" }
            ?.invoke(builder, false)
        val request = builderClass.getMethod("build").invoke(builder)
        val completed = CountDownLatch(1)
        val result = AtomicInteger(Int.MIN_VALUE)
        val callbackClass = start.parameterTypes[2]
        val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { proxy, method, args ->
            when (method.name) {
                "onTetheringStarted" -> { result.set(0); completed.countDown(); null }
                "onTetheringFailed" -> {
                    result.set(args?.firstOrNull() as? Int ?: -1)
                    completed.countDown()
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "AutoTaskLite.StartTetheringCallback"
                else -> null
            }
        }
        start.invoke(manager, request, Executor { it.run() }, callback)
        check(completed.await(timeoutMs, TimeUnit.MILLISECONDS)) { "热点开启超时，系统未返回结果" }
        check(result.get() == 0) { "系统拒绝开启热点，Tethering 错误码 ${result.get()}" }
        XLog.i("action: hotspot -> on (TetheringManager accepted)")
        return true
    }
}
