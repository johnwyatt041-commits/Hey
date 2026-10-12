package com.kooo.hey.audio

import android.content.Context
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * One-shot entry point for `app_process` to access CarAudioManager with elevated privileges.
 *
 * Usage (via ADB shell):
 *   export CLASSPATH="$CAR_JAR:$APK"
 *   app_process /system/bin com.kooo.hey.audio.VolumeShellMain <command> [args...]
 *
 * Commands:
 *   dump                         - List all volume groups
 *   get <groupId>                - Get volume info for a group
 *   set <groupId> <volume>       - Set volume for a group
 *   usage <usageId>              - Find volume group for a usage
 */
object VolumeShellMain {

    private fun err(msg: String) = System.err.println(msg)

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run(args)
        } catch (e: Throwable) {
            println("ERROR=${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace(System.err)
        } finally {
            System.exit(0)
        }
    }

    private fun run(args: Array<String>) {
        if (args.isEmpty()) {
            println("ERROR=no_command")
            return
        }

        try { Looper.prepareMainLooper() } catch (_: Throwable) {}

        val ht = HandlerThread("car-conn").apply { start() }

        val context = createSystemContext()
        err("context=${context?.javaClass?.name ?: "null"}")
        if (context == null) {
            println("ERROR=failed_to_create_context")
            return
        }

        val cam = getCarAudioManager(context, ht)
        if (cam == null) {
            println("ERROR=failed_to_get_car_audio_manager")
            return
        }
        err("cam=${cam.javaClass.name}")

        when (args[0]) {
            "dump" -> dumpAll(cam)
            "get" -> {
                val gid = args.getOrNull(1)?.toIntOrNull()
                if (gid == null) { println("ERROR=missing_group_id"); return }
                getGroupInfo(cam, gid)
            }
            "set" -> {
                val gid = args.getOrNull(1)?.toIntOrNull()
                val vol = args.getOrNull(2)?.toIntOrNull()
                if (gid == null || vol == null) { println("ERROR=missing_args"); return }
                setGroupVolume(cam, gid, vol)
            }
            "usage" -> {
                val usage = args.getOrNull(1)?.toIntOrNull()
                if (usage == null) { println("ERROR=missing_usage_id"); return }
                getGroupForUsage(cam, usage)
            }
            "device_group" -> {
                val address = args.getOrNull(1)
                if (address.isNullOrBlank()) { println("ERROR=missing_device_address"); return }
                findGroupForDevice(address)
            }
            else -> println("ERROR=unknown_command")
        }

        ht.quitSafely()
    }

    // ==================== System Context ====================

    private fun createSystemContext(): Context? {
        // Strategy 1: ActivityThread.systemMain()
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = atClass.getMethod("systemMain").invoke(null)
            val ctx = atClass.getMethod("getSystemContext").invoke(at) as? Context
            if (ctx != null) { err("ctx strategy=systemMain"); return ctx }
        } catch (e: Throwable) { err("systemMain failed: $e") }

        // Strategy 2: new ActivityThread + attach(false)
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = atClass.getDeclaredConstructor().newInstance()
            try {
                val m = atClass.getDeclaredMethod("attach", Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType)
                m.isAccessible = true
                m.invoke(at, false, 0L)
            } catch (_: NoSuchMethodException) {
                val m = atClass.getDeclaredMethod("attach", Boolean::class.javaPrimitiveType)
                m.isAccessible = true
                m.invoke(at, false)
            }
            val ctx = atClass.getMethod("getSystemContext").invoke(at) as? Context
            if (ctx != null) { err("ctx strategy=attach"); return ctx }
        } catch (e: Throwable) { err("attach failed: $e") }

        return null
    }

    // ==================== Car Audio Manager (direct binder) ====================

    private fun getCarAudioManager(context: Context, ht: HandlerThread): Any? {
        // Step 1: get car_service binder from ServiceManager
        val binder: IBinder?
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            binder = smClass.getMethod("getService", String::class.java)
                .invoke(null, "car_service") as? IBinder
            err("ServiceManager.getService(car_service)=${binder?.javaClass?.name ?: "null"}")
        } catch (e: Throwable) {
            err("ServiceManager failed: $e")
            return tryCreateCarFallback(context, ht)
        }
        if (binder == null) {
            err("car_service binder is null, trying fallback")
            return tryCreateCarFallback(context, ht)
        }

        // Step 2: create ICar proxy
        val iCar: Any?
        try {
            val iCarStubClass = Class.forName("android.car.ICar\$Stub")
            iCar = iCarStubClass.getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
            err("ICar.Stub.asInterface=${iCar?.javaClass?.name ?: "null"}")
        } catch (e: Throwable) {
            err("ICar.Stub.asInterface failed: $e")
            return tryCreateCarFallback(context, ht)
        }
        if (iCar == null) return tryCreateCarFallback(context, ht)

        // Step 3: create Car instance via private constructor(Context, ICar, Handler)
        val car: Any?
        try {
            val carClass = Class.forName("android.car.Car")
            val iCarClass = Class.forName("android.car.ICar")
            val handler = android.os.Handler(ht.looper)

            val ctor = carClass.getDeclaredConstructor(
                Context::class.java, iCarClass, android.os.Handler::class.java
            )
            ctor.isAccessible = true
            car = ctor.newInstance(context, iCar, handler)
            err("Car created via ctor, isConnected=${isConnected(car)}")
        } catch (e: Throwable) {
            err("Car ctor failed: $e")
            e.printStackTrace(System.err)
            return tryCreateCarFallback(context, ht)
        }

        // Step 4: get CarAudioManager from Car
        return getAudioManagerFromCar(car)
    }

    private fun tryCreateCarFallback(context: Context, ht: HandlerThread): Any? {
        val carClass = Class.forName("android.car.Car")
        val handler = android.os.Handler(ht.looper)

        // Fallback 1: createCar(Context, Handler)
        try {
            err("trying createCar(Context, Handler)...")
            val m = carClass.getMethod("createCar", Context::class.java, android.os.Handler::class.java)
            val car = m.invoke(null, context, handler)
            err("createCar(ctx,handler) result=${car?.javaClass?.name ?: "null"}, connected=${car?.let { isConnected(it) }}")
            if (car != null && isConnected(car)) return getAudioManagerFromCar(car)
        } catch (e: Throwable) { err("createCar(ctx,handler) failed: $e") }

        // Fallback 2: createCar(Context)
        try {
            err("trying createCar(Context)...")
            val m = carClass.getMethod("createCar", Context::class.java)
            val car = m.invoke(null, context)
            err("createCar(ctx) result=${car?.javaClass?.name ?: "null"}, connected=${car?.let { isConnected(it) }}")
            if (car != null && isConnected(car)) return getAudioManagerFromCar(car)
            if (car != null) {
                try {
                    car.javaClass.getMethod("connect").invoke(car)
                    Thread.sleep(3000)
                    err("after connect+sleep, connected=${isConnected(car)}")
                    if (isConnected(car)) return getAudioManagerFromCar(car)
                } catch (e2: Throwable) { err("connect() failed: $e2") }
            }
        } catch (e: Throwable) { err("createCar(ctx) failed: $e") }

        return null
    }

    private fun getAudioManagerFromCar(car: Any?): Any? {
        if (car == null) return null
        return try {
            val cam = car.javaClass.getMethod("getCarManager", String::class.java).invoke(car, "audio")
            err("getCarManager(audio)=${cam?.javaClass?.name ?: "null"}")
            cam
        } catch (e: Throwable) {
            err("getCarManager(audio) failed: $e")
            null
        }
    }

    private fun isConnected(car: Any): Boolean {
        return try {
            car.javaClass.getMethod("isConnected").invoke(car) as? Boolean ?: false
        } catch (_: Throwable) { false }
    }

    // ==================== Volume Operations ====================

    private fun dumpAll(cam: Any) {
        try {
            val count = cam.javaClass.getMethod("getVolumeGroupCount").invoke(cam) as Int
            println("GROUP_COUNT=$count")
            for (i in 0 until count) {
                val cur = cam.javaClass.getMethod("getGroupVolume", Int::class.javaPrimitiveType).invoke(cam, i) as Int
                val max = cam.javaClass.getMethod("getGroupMaxVolume", Int::class.javaPrimitiveType).invoke(cam, i) as Int
                val min = try {
                    cam.javaClass.getMethod("getGroupMinVolume", Int::class.javaPrimitiveType).invoke(cam, i) as Int
                } catch (_: Throwable) { 0 }
                println("GROUP=$i|CUR=$cur|MIN=$min|MAX=$max")
            }
            println("OK")
        } catch (e: Throwable) {
            val root = if (e is java.lang.reflect.InvocationTargetException) e.targetException ?: e else e
            println("ERROR=${root.javaClass.simpleName}: ${root.message}")
        }
    }

    private fun getGroupInfo(cam: Any, groupId: Int) {
        try {
            val cur = cam.javaClass.getMethod("getGroupVolume", Int::class.javaPrimitiveType).invoke(cam, groupId) as Int
            val max = cam.javaClass.getMethod("getGroupMaxVolume", Int::class.javaPrimitiveType).invoke(cam, groupId) as Int
            val min = try {
                cam.javaClass.getMethod("getGroupMinVolume", Int::class.javaPrimitiveType).invoke(cam, groupId) as Int
            } catch (_: Throwable) { 0 }
            println("GROUP=$groupId|CUR=$cur|MIN=$min|MAX=$max")
            println("OK")
        } catch (e: Throwable) {
            val root = if (e is java.lang.reflect.InvocationTargetException) e.targetException ?: e else e
            println("ERROR=${root.javaClass.simpleName}: ${root.message}")
        }
    }

    private fun setGroupVolume(cam: Any, groupId: Int, volume: Int) {
        try {
            cam.javaClass.getMethod("setGroupVolume",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).invoke(cam, groupId, volume, 0)
            val actual = cam.javaClass.getMethod("getGroupVolume", Int::class.javaPrimitiveType).invoke(cam, groupId) as Int
            println("SET=$groupId|VOL=$volume|ACTUAL=$actual")
            println("OK")
        } catch (e: Throwable) {
            val root = if (e is java.lang.reflect.InvocationTargetException) e.targetException ?: e else e
            println("ERROR=${root.javaClass.simpleName}: ${root.message}")
        }
    }

    private fun getGroupForUsage(cam: Any, usage: Int) {
        try {
            val groupId = cam.javaClass.getMethod("getVolumeGroupIdForUsage", Int::class.javaPrimitiveType)
                .invoke(cam, usage) as Int
            println("USAGE=$usage|GROUP=$groupId")
            if (groupId >= 0) getGroupInfo(cam, groupId)
            else println("OK")
        } catch (e: Throwable) {
            val root = if (e is java.lang.reflect.InvocationTargetException) e.targetException ?: e else e
            println("ERROR=${root.javaClass.simpleName}: ${root.message}")
        }
    }

    // ==================== Device → Volume Group Mapping ====================

    private fun findGroupForDevice(address: String) {
        val candidates = listOf(
            "/vendor/etc/car_audio_configuration.xml",
            "/system/etc/car_audio_configuration.xml",
            "/odm/etc/car_audio_configuration.xml"
        )
        val xmlFile = candidates.map { java.io.File(it) }.firstOrNull { it.exists() }
        if (xmlFile == null) {
            println("ERROR=car_audio_config_not_found")
            err("tried: $candidates")
            return
        }
        err("parsing ${xmlFile.path}")

        try {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlFile)

            val zones = doc.getElementsByTagName("zone")
            var primaryZone: Element? = null
            for (i in 0 until zones.length) {
                val zone = zones.item(i) as? Element ?: continue
                if (zone.getAttribute("isPrimary") == "true") {
                    primaryZone = zone
                    break
                }
            }
            if (primaryZone == null) primaryZone = zones.item(0) as? Element
            if (primaryZone == null) {
                println("ERROR=no_zone_in_config")
                return
            }

            val volumeGroups = primaryZone.getElementsByTagName("volumeGroups").item(0) as? Element
            if (volumeGroups == null) {
                println("ERROR=no_volume_groups_in_config")
                return
            }

            val groups = volumeGroups.childNodes
            val matched = mutableListOf<Int>()
            var groupIndex = 0
            for (i in 0 until groups.length) {
                val group = groups.item(i) as? Element ?: continue
                if (group.tagName != "group") continue
                val devices = group.getElementsByTagName("device")
                for (j in 0 until devices.length) {
                    val dev = devices.item(j) as? Element ?: continue
                    if (dev.getAttribute("address") == address) {
                        matched.add(groupIndex)
                        break
                    }
                }
                groupIndex++
            }

            if (matched.isEmpty()) {
                println("DEVICE_GROUP=$address|GROUPS=none")
            } else {
                println("DEVICE_GROUP=$address|GROUPS=${matched.joinToString(",")}")
            }
            println("OK")
        } catch (e: Throwable) {
            println("ERROR=${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
