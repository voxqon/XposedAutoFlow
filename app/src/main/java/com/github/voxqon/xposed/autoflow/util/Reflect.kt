package com.github.voxqon.xposed.autoflow.util

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 反射查找工具。
 *
 * 查找策略说明（对应 README「常见失败点与降级方案」）：
 * 1. 对系统私有类/方法，永远使用 [findClassIfExists] / [findMethodExactIfExists] /
 *    [hookAllMethodsSafe] 这类“存在才用”的入口，找不到只记日志，绝不抛异常。
 * 2. 对签名不确定的点，用 XposedBridge.hookAllMethods 按方法名挂全部重载，
 *    以适配 AOSP / MIUI / HyperOS 各版本签名差异。
 * 3. 多候选时按“优先级从高到低”依次尝试，第一个命中即注册（见各 Hook 注册器内注释）。
 */
object Reflect {

    fun findClassIfExists(name: String, cl: ClassLoader): Class<*>? =
        runCatching { Class.forName(name, false, cl) }.getOrNull()

    /** 依次尝试多个类名候选，返回第一个存在的类。 */
    fun findFirstClass(vararg names: String, cl: ClassLoader): Class<*>? {
        for (n in names) {
            val c = findClassIfExists(n, cl)
            if (c != null) return c
        }
        return null
    }

    fun findMethodExactIfExists(clazz: Class<*>, name: String, vararg parameterTypes: Class<*>): Method? =
        runCatching {
            val m = clazz.getDeclaredMethod(name, *parameterTypes)
            m.isAccessible = true
            m
        }.getOrNull()

    /** 在类及其父类链上按名称+参数个数查找方法（用于签名不确定时）。 */
    fun findMethodByName(clazz: Class<*>, name: String, paramCount: Int = -1): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            val current = c
            for (m in runCatching { current.declaredMethods }.getOrDefault(emptyArray())) {
                if (m.name == name && (paramCount < 0 || m.parameterTypes.size == paramCount)) {
                    m.isAccessible = true
                    return m
                }
            }
            c = c.superclass
        }
        return null
    }

    fun findFieldIfExists(clazz: Class<*>, name: String): Field? = runCatching {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        null
    }.getOrNull()

    fun getField(target: Any?, name: String): Any? {
        if (target == null) return null
        return runCatching {
            findFieldIfExists(target.javaClass, name)?.get(target)
        }.getOrNull()
    }

    fun getIntField(target: Any?, name: String, def: Int = Int.MIN_VALUE): Int {
        val v = getField(target, name)
        return if (v is Int) v else def
    }

    fun isStatic(f: Field): Boolean = Modifier.isStatic(f.modifiers)
}
