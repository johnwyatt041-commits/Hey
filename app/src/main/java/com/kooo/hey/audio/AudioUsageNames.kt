package com.kooo.hey.audio

import android.media.AudioAttributes
import java.lang.reflect.Modifier

/**
 * Utility for mapping AudioAttributes usage ints to human-readable constant names.
 *
 * Note: Vendor/custom usages (e.g. 73 on some head units) are not discoverable via AOSP
 * constants; we can only label them if we know them ahead of time.
 */
object AudioUsageNames {

    private val usageToNames: Map<Int, List<String>> by lazy {
        val map = linkedMapOf<Int, MutableList<String>>()

        // Public AOSP constants: AudioAttributes.USAGE_*
        try {
            for (f in AudioAttributes::class.java.fields) {
                if (!Modifier.isStatic(f.modifiers)) continue
                if (f.type != Int::class.javaPrimitiveType) continue
                val name = f.name
                if (!name.startsWith("USAGE_")) continue
                val value = f.getInt(null)
                map.getOrPut(value) { mutableListOf() }.add(name)
            }
        } catch (_: Throwable) {
            // Keep empty; callers will fallback to "UNKNOWN".
        }

        // Sort names per value for stable output.
        map.forEach { (_, names) -> names.sort() }
        map
    }

    /** Return usage name(s) if known, else "UNKNOWN(<usage>)". */
    fun nameOf(usage: Int): String {
        val names = usageToNames[usage]
        return if (names.isNullOrEmpty()) "UNKNOWN($usage)" else names.joinToString(" | ")
    }

    /**
     * List all known usages (AOSP constants + known vendor ones), sorted by usage value.
     */
    fun listAll(): List<Pair<Int, String>> {
        return usageToNames.entries
            .sortedBy { it.key }
            .map { (usage, names) -> usage to names.joinToString(" | ") }
    }
}
