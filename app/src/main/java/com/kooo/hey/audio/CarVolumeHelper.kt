package com.kooo.hey.audio

import android.content.Context
import com.kooo.hey.log.AppLog
import java.lang.reflect.Method

/**
 * Controls Android Automotive volume groups via CarAudioManager (reflection).
 * On car head units, each audio usage maps to a volume group with independent volume.
 *
 * Uses reflection to avoid compile-time dependency on android.car.
 */
class CarVolumeHelper(private val context: Context) {

    companion object {
        private const val TAG = "CarVolume"
    }

    private var car: Any? = null
    private var carAudioManager: Any? = null

    var onLog: ((String) -> Unit)? = null

    private fun log(msg: String) {
        AppLog.i(TAG, msg)
        onLog?.invoke(msg)
    }

    /** Connect to Car service — tries multiple createCar signatures */
    fun connect(): Boolean {
        if (carAudioManager != null) return true

        return try {
            val carClass = Class.forName("android.car.Car")
            log("Car class 已找到")

            var carObj: Any? = null

            // 1) Prefer createCar(Context) — synchronous, blocks until connected
            try {
                val method = carClass.getMethod("createCar", Context::class.java)
                carObj = method.invoke(null, context)
                log("createCar(ctx) 成功 (同步)")
            } catch (e: Exception) {
                log("createCar(ctx) 失败: ${e.message}")
            }

            // 2) Fallback: createCar(Context, ServiceConnection) with null
            if (carObj == null) {
                try {
                    val method = carClass.getMethod("createCar", Context::class.java, android.content.ServiceConnection::class.java)
                    carObj = method.invoke(null, context, null)
                    log("createCar(ctx, null) 成功 (异步)")
                } catch (e: Exception) {
                    log("createCar(ctx, null) 失败: ${e.message}")
                }
            }

            if (carObj == null) {
                log("✗ Car 实例创建失败")
                return false
            }

            car = carObj
            log("✓ Car 实例: ${carObj.javaClass.name}")

            // Ensure car is connected before getting managers
            try {
                val isConnected = carObj.javaClass.getMethod("isConnected").invoke(carObj) as? Boolean ?: false
                log("  Car isConnected = $isConnected")
                if (!isConnected) {
                    try {
                        carObj.javaClass.getMethod("connect").invoke(carObj)
                        log("  调用 connect() 成功")
                        // Brief wait for async connection to establish
                        Thread.sleep(500)
                        val connected2 = carObj.javaClass.getMethod("isConnected").invoke(carObj) as? Boolean ?: false
                        log("  连接后 isConnected = $connected2")
                    } catch (e: Exception) {
                        log("  connect() 失败: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                log("  isConnected 检查失败: ${e.message}")
            }

            // Get CarAudioManager
            val cam = carObj.javaClass.getMethod("getCarManager", String::class.java)
                .invoke(carObj, "audio")

            if (cam == null) {
                log("✗ CarAudioManager 为 null (服务可能未连接)")
                // Dump available managers for debugging
                try {
                    val isConn = carObj.javaClass.getMethod("isConnected").invoke(carObj) as? Boolean
                    log("  当前 isConnected = $isConn")
                } catch (_: Exception) {}
                return false
            }

            carAudioManager = cam
            log("✓ CarAudioManager: ${cam.javaClass.name}")

            // List available methods for debugging
            val methods = cam.javaClass.methods
                .filter { it.name.contains("olume", ignoreCase = true) }
                .map { "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})" }
            log("  音量相关方法: $methods")

            true
        } catch (e: Exception) {
            log("✗ Car 连接异常: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    fun disconnect() {
        try {
            car?.let { c ->
                try { c.javaClass.getMethod("disconnect").invoke(c) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        car = null
        carAudioManager = null
    }

    /** Get volume group ID for a given usage */
    fun getVolumeGroupForUsage(usage: Int): Int {
        val cam = carAudioManager ?: return -1
        return try {
            // Method 1: Try getVolumeGroupIdForUsage(int)
            try {
                val method = findMethod(cam, "getVolumeGroupIdForUsage", Int::class.javaPrimitiveType!!)
                if (method != null) {
                    val result = method.invoke(cam, usage)
                    val groupId = (result as? Int) ?: -1
                    log("getVolumeGroupIdForUsage($usage) = $groupId")
                    if (groupId >= 0) return groupId
                }
            } catch (e: Exception) {
                log("  方法1(int)失败: ${rootCause(e)}")
            }

            // Method 2: Try with AudioAttributes object
            try {
                val builderClass = Class.forName("android.media.AudioAttributes\$Builder")
                val builder = builderClass.getDeclaredConstructor().newInstance()
                builderClass.getMethod("setUsage", Int::class.javaPrimitiveType).invoke(builder, usage)
                val attrs = builderClass.getMethod("build").invoke(builder)

                val audioAttributesClass = Class.forName("android.media.AudioAttributes")
                val method = findMethod(cam, "getVolumeGroupIdForUsage", audioAttributesClass)
                if (method != null) {
                    val result = method.invoke(cam, attrs)
                    val groupId = (result as? Int) ?: -1
                    log("getVolumeGroupIdForUsage(AudioAttributes[usage=$usage]) = $groupId")
                    if (groupId >= 0) return groupId
                }
            } catch (e: Exception) {
                log("  方法2(AudioAttributes)失败: ${rootCause(e)}")
            }

            -1
        } catch (e: Exception) {
            log("✗ getVolumeGroupIdForUsage($usage) 异常: ${rootCause(e)}")
            -1
        }
    }

    fun getGroupMaxVolume(groupId: Int): Int {
        val cam = carAudioManager ?: return -1
        return try {
            val method = findMethod(cam, "getGroupMaxVolume", Int::class.javaPrimitiveType!!)
            (method?.invoke(cam, groupId) as? Int) ?: -1
        } catch (e: Exception) {
            log("✗ getGroupMaxVolume($groupId) 异常: ${rootCause(e)}")
            -1
        }
    }

    fun getGroupMinVolume(groupId: Int): Int {
        val cam = carAudioManager ?: return 0
        return try {
            val method = findMethod(cam, "getGroupMinVolume", Int::class.javaPrimitiveType!!)
            (method?.invoke(cam, groupId) as? Int) ?: 0
        } catch (e: Exception) {
            log("✗ getGroupMinVolume($groupId) 异常: ${rootCause(e)}")
            0
        }
    }

    fun getGroupVolume(groupId: Int): Int {
        val cam = carAudioManager ?: return -1
        return try {
            val method = findMethod(cam, "getGroupVolume", Int::class.javaPrimitiveType!!)
            (method?.invoke(cam, groupId) as? Int) ?: -1
        } catch (e: Exception) {
            log("✗ getGroupVolume($groupId) 异常: ${rootCause(e)}")
            -1
        }
    }

    fun setGroupVolume(groupId: Int, volume: Int): Boolean {
        val cam = carAudioManager ?: return false
        return try {
            val method = findMethod(cam, "setGroupVolume",
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            method?.invoke(cam, groupId, volume, 0)
            log("✓ setGroupVolume($groupId, $volume)")
            true
        } catch (e: Exception) {
            log("✗ setGroupVolume($groupId, $volume) 异常: ${rootCause(e)}")
            false
        }
    }

    /** Query and log all volume info for a given usage */
    fun dumpVolumeInfo(usage: Int): VolumeInfo? {
        val groupId = getVolumeGroupForUsage(usage)
        if (groupId < 0) {
            log("✗ usage=$usage 没有对应的音量组 (返回 $groupId)")
            tryDumpAllGroups()
            return null
        }
        val current = getGroupVolume(groupId)
        val max = getGroupMaxVolume(groupId)
        val min = getGroupMinVolume(groupId)
        log("音量组: groupId=$groupId, 当前=$current, 最小=$min, 最大=$max (usage=$usage)")
        return VolumeInfo(groupId, current, min, max)
    }

    /** Directly query volume info for a manually specified group ID */
    fun dumpGroupInfo(groupId: Int): VolumeInfo? {
        val current = getGroupVolume(groupId)
        val max = getGroupMaxVolume(groupId)
        val min = getGroupMinVolume(groupId)
        if (current < 0 || max < 0) {
            log("✗ 组$groupId 查询失败 (current=$current, max=$max)")
            return null
        }
        log("音量组: groupId=$groupId, 当前=$current, 最小=$min, 最大=$max")
        return VolumeInfo(groupId, current, min, max)
    }

    /** Try to enumerate volume groups for debugging */
    fun tryDumpAllGroups() {
        val cam = carAudioManager ?: return
        try {
            val countMethod = findMethod(cam, "getVolumeGroupCount")
            if (countMethod != null) {
                val count = countMethod.invoke(cam) as? Int ?: 0
                log("  共 $count 个音量组:")
                for (i in 0 until count) {
                    val vol = getGroupVolume(i)
                    val max = getGroupMaxVolume(i)
                    log("    组$i: $vol/$max")
                }
            }
        } catch (e: Exception) {
            log("  枚举音量组失败: ${rootCause(e)}")
        }
    }

    /** Set volume for usage to max */
    fun setVolumeToMax(usage: Int): Boolean {
        val info = dumpVolumeInfo(usage) ?: return false
        if (info.current < info.max) {
            log("音量 ${info.current} → ${info.max}")
            return setGroupVolume(info.groupId, info.max)
        }
        log("音量已是最大 ${info.max}")
        return true
    }

    private fun findMethod(obj: Any, name: String, vararg paramTypes: Class<*>): Method? {
        return try {
            obj.javaClass.getMethod(name, *paramTypes)
        } catch (_: NoSuchMethodException) {
            obj.javaClass.methods.find { it.name == name && it.parameterCount == paramTypes.size }
        }
    }

    private fun rootCause(e: Throwable): String {
        val root = if (e is java.lang.reflect.InvocationTargetException) e.targetException ?: e else e
        return "${root.javaClass.simpleName}: ${root.message}"
    }

    data class VolumeInfo(val groupId: Int, val current: Int, val min: Int, val max: Int)
}
