package com.kooo.hey.config

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.os.Build
import com.kooo.hey.R
import org.json.JSONObject

/**
 * Persistent configuration for Hey! app.
 * Model-specific defaults are bundled with the app and user changes are persisted locally.
 */
class AppConfig(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        applyBundledModelDefaults(context.applicationContext)
    }

    // ==================== Audio Output ====================

    /**
     * AudioAttributes usage value.
     * Default 73 = USAGE_OUT_CAR_SOUND_EFFECTS (Ecarx vendor usage, routes to bus4 → external speaker).
     * Routes directly through the configured audio usage and is not subject to speed checks.
     */
    var audioUsage: Int
        get() = prefs.getInt(KEY_AUDIO_USAGE, DEFAULT_AUDIO_USAGE)
        set(value) = prefs.edit().putInt(KEY_AUDIO_USAGE, value).apply()

    /**
     * HIDL transaction code for setLockSound in IEcarxAudioControl.
     * Its availability and offset vary by HAL build.
     * Only used when audioUsage==72 (amp management active).
     */
    var ampTransactionCode: Int
        get() = prefs.getInt(KEY_AMP_TXN_CODE, DEFAULT_AMP_TXN_CODE)
        set(value) = prefs.edit().putInt(KEY_AMP_TXN_CODE, value).apply()

    /**
     * Preferred audio output device address.
     * Empty string = let audio policy decide (recommended).
     */
    var outputDeviceAddress: String
        get() = prefs.getString(KEY_OUTPUT_DEVICE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OUTPUT_DEVICE, value).apply()

    /**
     * AudioRecord audio source.
     * 1=MIC, 6=VOICE_RECOGNITION, 7=VOICE_COMMUNICATION, 0=DEFAULT, 9=UNPROCESSED
     */
    var audioSource: Int
        get() = prefs.getInt(KEY_AUDIO_SOURCE, DEFAULT_AUDIO_SOURCE)
        set(value) = prefs.edit().putInt(KEY_AUDIO_SOURCE, value).apply()

    /**
     * Preferred audio input device ID.
     * 0 = let audio policy decide (recommended).
     * Set to a specific AudioDeviceInfo.id (e.g. 89 for built-in mic) to force input routing.
     */
    var inputDeviceId: Int
        get() = prefs.getInt(KEY_INPUT_DEVICE_ID, 0)
        set(value) = prefs.edit().putInt(KEY_INPUT_DEVICE_ID, value).apply()

    /**
     * Preferred audio output device ID.
     * 0 = let audio policy decide based on usage routing (recommended).
     * Set to a specific AudioDeviceInfo.id to force output to that device (e.g. a bus).
     */
    var outputDeviceId: Int
        get() = prefs.getInt(KEY_OUTPUT_DEVICE_ID, 0)
        set(value) = prefs.edit().putInt(KEY_OUTPUT_DEVICE_ID, value).apply()

    /** Minimum gain limit (>=0.1, <= maxGainLimit). Default 1. */
    var minGainLimit: Float
        get() = prefs.getFloat(KEY_MIN_GAIN_LIMIT, DEFAULT_MIN_GAIN_LIMIT).coerceAtLeast(0.1f)
        set(value) = prefs.edit().putFloat(KEY_MIN_GAIN_LIMIT, value.coerceIn(0.1f, maxGainLimit)).apply()

    /** Maximum gain limit (3~100, >= minGainLimit). Default 30. */
    var maxGainLimit: Float
        get() = prefs.getFloat(KEY_MAX_GAIN_LIMIT, DEFAULT_MAX_GAIN_LIMIT).coerceIn(3.0f, 100.0f)
        set(value) = prefs.edit().putFloat(KEY_MAX_GAIN_LIMIT, value.coerceIn(minGainLimit.coerceAtLeast(3.0f), 100.0f)).apply()

    /** Software volume gain multiplier, clamped to [minGainLimit, maxGainLimit]. */
    var volumeGain: Float
        get() = prefs.getFloat(KEY_VOLUME_GAIN, DEFAULT_VOLUME_GAIN).coerceIn(minGainLimit, maxGainLimit)
        set(value) = prefs.edit().putFloat(KEY_VOLUME_GAIN, value.coerceIn(minGainLimit, maxGainLimit)).apply()

    /** Music playback volume (0.0 ~ 1.0, 0.5 = 50%, 1.0 = 100%). */
    var musicVolume: Float
        get() = prefs.getFloat(KEY_MUSIC_VOLUME, DEFAULT_MEDIA_VOLUME).coerceIn(0.0f, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_MUSIC_VOLUME, value.coerceIn(0.0f, 1.0f)).apply()

    /**
     * Music / Quick sound speaker destination:
     * 0 = MUSIC_SPEAKER_IN_CABIN (USAGE_MEDIA = 1)
     * 1 = MUSIC_SPEAKER_EXTERNAL (appConfig.audioUsage = 73/72)
     */
    var musicSpeakerDestination: Int
        get() = prefs.getInt(KEY_MUSIC_SPEAKER_DEST, MUSIC_SPEAKER_EXTERNAL).coerceIn(0, 1)
        set(value) = prefs.edit().putInt(KEY_MUSIC_SPEAKER_DEST, value.coerceIn(0, 1)).apply()

    /** Software noise gate (suppress low-level noise). Default OFF. */
    var enableNoiseGate: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_NOISE_GATE, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_NOISE_GATE, value).apply()

    /** Noise gate strength 0~1 (0=passthrough, 1=full). Default 0.5. Real-time. */
    var noiseGateStrength: Float
        get() = prefs.getFloat(KEY_NOISE_GATE_STRENGTH, DEFAULT_NOISE_GATE_STRENGTH).coerceIn(0f, 1f)
        set(value) = prefs.edit().putFloat(KEY_NOISE_GATE_STRENGTH, value.coerceIn(0f, 1f)).apply()

    /** AGC (auto gain control). Default OFF. */
    var enableAgc: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_AGC, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_AGC, value).apply()

    /** Bandpass filter master switch (controls both HP and LP). Default ON. */
    var enableBandpass: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_BANDPASS, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_BANDPASS, value).apply()

    /** Low-pass filter to cut high-frequency howling. Default ON. */
    var enableLowPass: Boolean
        get() = enableBandpass
        set(value) { enableBandpass = value }

    /** High-pass filter to cut low-frequency rumble/noise. Default ON. */
    var enableHighPass: Boolean
        get() = enableBandpass
        set(value) { enableBandpass = value }

    /** High-pass cutoff frequency in Hz (50~2000). Default 300. */
    var highPassCutoff: Int
        get() = prefs.getInt(KEY_HIGH_PASS_CUTOFF, DEFAULT_HIGH_PASS_CUTOFF).coerceIn(50, 2000)
        set(value) = prefs.edit().putInt(KEY_HIGH_PASS_CUTOFF, value.coerceIn(50, 2000)).apply()

    /** Low-pass cutoff frequency in Hz (200~8000). Default 4000. */
    var lowPassCutoff: Int
        get() = prefs.getInt(KEY_LOW_PASS_CUTOFF, DEFAULT_LOW_PASS_CUTOFF).coerceIn(200, 8000)
        set(value) = prefs.edit().putInt(KEY_LOW_PASS_CUTOFF, value.coerceIn(200, 8000)).apply()

    /** Frequency shifter for anti-howling. Default OFF. */
    var enableFreqShift: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_FREQ_SHIFT, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_FREQ_SHIFT, value).apply()

    /** Frequency shift amount in Hz (1~10). Default 5. */
    var freqShiftHz: Float
        get() = prefs.getFloat(KEY_FREQ_SHIFT_HZ, DEFAULT_FREQ_SHIFT_HZ).coerceIn(1f, 10f)
        set(value) = prefs.edit().putFloat(KEY_FREQ_SHIFT_HZ, value.coerceIn(1f, 10f)).apply()

    /**
     * Ecarx DSP mode: 0=关, 1=环境噪音抑制(NOISE_CLEAN), 2=回声消除(ECHO_CANCEL).
     * DSP 同时只能跑一种模式。默认关。
     */
    var dspMode: Int
        get() = prefs.getInt(KEY_DSP_MODE, 0)
        set(value) = prefs.edit().putInt(KEY_DSP_MODE, value).apply()

    // Legacy accessors for EcarxWorkMode.apply() compatibility
    val enableHwNoiseClean: Boolean get() = dspMode == 1
    val enableHwEchoCancel: Boolean get() = dspMode == 2

    /**
     * Reverb mode: 0=无, 1=适中(0.3), 2=强(0.6). Default 1.
     */
    var reverbMode: Int
        get() = prefs.getInt(KEY_REVERB_MODE, 0)
        set(value) = prefs.edit().putInt(KEY_REVERB_MODE, value.coerceIn(0, 2)).apply()

    /** Derived: whether reverb is enabled. */
    val enableReverb: Boolean get() = reverbMode > 0

    /** Derived: reverb wet mix from mode. */
    val reverbMix: Float get() = when (reverbMode) {
        1 -> 0.3f
        2 -> 0.6f
        else -> 0f
    }

    // ==================== Music Player ====================

    /** Music folder path. Default: /storage/emulated/0/Download/music */
    var musicFolderPath: String
        get() = prefs.getString(KEY_MUSIC_FOLDER, DEFAULT_MUSIC_FOLDER) ?: DEFAULT_MUSIC_FOLDER
        set(value) = prefs.edit().putString(KEY_MUSIC_FOLDER, value).apply()

    /** Music play mode: 0=single (stop after track), 1=list loop, 2=single loop. Default 1. */
    var musicPlayMode: Int
        get() = prefs.getInt(KEY_MUSIC_PLAY_MODE, 1).coerceIn(0, 2)
        set(value) = prefs.edit().putInt(KEY_MUSIC_PLAY_MODE, value.coerceIn(0, 2)).apply()

    // ==================== Quick Sounds ====================

    /** Number of active quick sound buttons (1..12). Default 6. */
    var quickSoundCount: Int
        get() = prefs.getInt(KEY_QUICK_SOUND_COUNT, DEFAULT_QUICK_SOUND_COUNT).coerceIn(MIN_QUICK_SOUNDS, MAX_QUICK_SOUNDS)
        set(value) = prefs.edit().putInt(KEY_QUICK_SOUND_COUNT, value.coerceIn(MIN_QUICK_SOUNDS, MAX_QUICK_SOUNDS)).apply()

    fun addQuickSound(): Int {
        val current = quickSoundCount
        if (current >= MAX_QUICK_SOUNDS) return -1
        val newIndex = current
        quickSoundCount = current + 1
        return newIndex
    }

    fun removeQuickSoundAt(index: Int): Boolean {
        val current = quickSoundCount
        if (current <= MIN_QUICK_SOUNDS || index !in 0 until current) return false
        val editor = prefs.edit()
        for (i in index until current - 1) {
            val nextName = prefs.getString("quick_sound_name_${i + 1}", null)
            val nextPath = prefs.getString("quick_sound_path_${i + 1}", null)
            val nextKey = if (prefs.contains("quick_sound_key_${i + 1}")) prefs.getInt("quick_sound_key_${i + 1}", 0) else null
            val nextGesture = if (prefs.contains("quick_sound_gesture_${i + 1}")) prefs.getInt("quick_sound_gesture_${i + 1}", 0) else null

            if (nextName != null) editor.putString("quick_sound_name_$i", nextName) else editor.remove("quick_sound_name_$i")
            if (nextPath != null) editor.putString("quick_sound_path_$i", nextPath) else editor.remove("quick_sound_path_$i")
            if (nextKey != null) editor.putInt("quick_sound_key_$i", nextKey) else editor.remove("quick_sound_key_$i")
            if (nextGesture != null) editor.putInt("quick_sound_gesture_$i", nextGesture) else editor.remove("quick_sound_gesture_$i")
        }
        val last = current - 1
        editor.remove("quick_sound_name_$last")
        editor.remove("quick_sound_path_$last")
        editor.remove("quick_sound_key_$last")
        editor.remove("quick_sound_gesture_$last")

        editor.putInt(KEY_QUICK_SOUND_COUNT, current - 1)
        editor.apply()
        return true
    }

    fun getQuickSoundName(index: Int): String {
        val defaultName = when (index) {
            0 -> context.getString(R.string.quick_sound_default_1)
            1 -> context.getString(R.string.quick_sound_default_2)
            2 -> context.getString(R.string.quick_sound_default_3)
            3 -> context.getString(R.string.quick_sound_default_4)
            4 -> context.getString(R.string.quick_sound_default_5)
            5 -> context.getString(R.string.quick_sound_default_6)
            else -> "${context.getString(R.string.quick_sound_prefix)} ${index + 1}"
        }
        return prefs.getString("quick_sound_name_$index", defaultName) ?: defaultName
    }

    fun setQuickSoundName(index: Int, name: String) {
        prefs.edit().putString("quick_sound_name_$index", name).apply()
    }

    fun getQuickSoundPath(index: Int): String {
        return prefs.getString("quick_sound_path_$index", "") ?: ""
    }

    fun setQuickSoundPath(index: Int, path: String) {
        prefs.edit().putString("quick_sound_path_$index", path).apply()
    }

    fun clearQuickSound(index: Int) {
        val defaultName = when (index) {
            0 -> context.getString(R.string.quick_sound_default_1)
            1 -> context.getString(R.string.quick_sound_default_2)
            2 -> context.getString(R.string.quick_sound_default_3)
            3 -> context.getString(R.string.quick_sound_default_4)
            4 -> context.getString(R.string.quick_sound_default_5)
            5 -> context.getString(R.string.quick_sound_default_6)
            else -> "${context.getString(R.string.quick_sound_prefix)} ${index + 1}"
        }
        prefs.edit()
            .putString("quick_sound_name_$index", defaultName)
            .putString("quick_sound_path_$index", "")
            .remove("quick_sound_key_$index")
            .remove("quick_sound_gesture_$index")
            .apply()
    }

    fun getQuickSoundKeyCode(index: Int): Int {
        return prefs.getInt("quick_sound_key_$index", 0)
    }

    fun setQuickSoundKeyCode(index: Int, keyCode: Int) {
        prefs.edit().putInt("quick_sound_key_$index", keyCode).apply()
    }

    fun getQuickSoundGesture(index: Int): Int {
        return prefs.getInt("quick_sound_gesture_$index", 0) // 0=Single, 1=Double, 2=Long
    }

    fun setQuickSoundGesture(index: Int, gesture: Int) {
        prefs.edit().putInt("quick_sound_gesture_$index", gesture.coerceIn(0, 2)).apply()
    }

    fun clearQuickSoundKeyBinding(index: Int) {
        prefs.edit()
            .remove("quick_sound_key_$index")
            .remove("quick_sound_gesture_$index")
            .apply()
    }

    // ==================== Global Function Key Bindings ====================

    /** Enable background hardware/bluetooth key monitoring via AccessibilityService. Default true. */
    var enableBackgroundKeyMonitor: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_BG_KEY_MONITOR, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_BG_KEY_MONITOR, value).apply()

    var appSwitchKeyCode: Int
        get() = prefs.getInt(KEY_APP_SWITCH_KEY, 0)
        set(value) = prefs.edit().putInt(KEY_APP_SWITCH_KEY, value).apply()

    var appSwitchGesture: Int
        get() = prefs.getInt(KEY_APP_SWITCH_GESTURE, 0)
        set(value) = prefs.edit().putInt(KEY_APP_SWITCH_GESTURE, value.coerceIn(0, 2)).apply()

    fun clearAppSwitchKeyBinding() {
        prefs.edit().remove(KEY_APP_SWITCH_KEY).remove(KEY_APP_SWITCH_GESTURE).apply()
    }
    fun clearAppSwitchKey() = clearAppSwitchKeyBinding()

    var talkKeyCode: Int
        get() = prefs.getInt(KEY_TALK_KEY, 0)
        set(value) = prefs.edit().putInt(KEY_TALK_KEY, value).apply()

    var talkGesture: Int
        get() = prefs.getInt(KEY_TALK_GESTURE, 0)
        set(value) = prefs.edit().putInt(KEY_TALK_GESTURE, value.coerceIn(0, 2)).apply()

    fun clearTalkKeyBinding() {
        prefs.edit().remove(KEY_TALK_KEY).remove(KEY_TALK_GESTURE).apply()
    }
    fun clearTalkKey() = clearTalkKeyBinding()

    // ==================== Visual ====================

    /** Ripple spawn threshold (smoothed level must exceed this to create a new ripple). Default 0.03. */
    var rippleThreshold: Float
        get() = prefs.getFloat(KEY_RIPPLE_THRESHOLD, DEFAULT_RIPPLE_THRESHOLD)
        set(value) = prefs.edit().putFloat(KEY_RIPPLE_THRESHOLD, value).apply()

    // ==================== Launch Action ====================

    /** Default panel when app is launched/triggered: 0=talk, 1=Music panel. */
    var launchAction: Int
        get() {
            val v = prefs.getInt(KEY_LAUNCH_ACTION, LAUNCH_ACTION_TALK)
            return if (v == 2) LAUNCH_ACTION_MUSIC_PANEL else v.coerceIn(0, 1)
        }
        set(value) = prefs.edit().putInt(KEY_LAUNCH_ACTION, value.coerceIn(0, 1)).apply()

    /** Whether to auto-play a specific item when opening Music panel. */
    var launchAutoPlay: Boolean
        get() = prefs.getBoolean(KEY_LAUNCH_AUTO_PLAY, false)
        set(value) = prefs.edit().putBoolean(KEY_LAUNCH_AUTO_PLAY, value).apply()

    /** Index (0-based) of music track to auto-play. */
    var launchMusicIndex: Int
        get() = prefs.getInt(KEY_LAUNCH_MUSIC_INDEX, 0)
        set(value) = prefs.edit().putInt(KEY_LAUNCH_MUSIC_INDEX, value.coerceAtLeast(0)).apply()

    // ==================== Shortcut & Floating Window ====================

    /** Enable logcat keyword trigger (monitors system log, triggers talk on match). Default OFF. */
    var enableLogcatTrigger: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_LOGCAT_TRIGGER, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_LOGCAT_TRIGGER, value).apply()

    /** Logcat trigger keyword. Empty = disabled. */
    var logcatTriggerKeyword: String
        get() = prefs.getString(KEY_LOGCAT_TRIGGER_KEYWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LOGCAT_TRIGGER_KEYWORD, value).apply()

    /** Enable floating talk button overlay. Default OFF. */
    var enableFloatingWindow: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_FLOATING, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_FLOATING, value).apply()

    /** Auto start countdown talk when app is brought to foreground from launcher icon. */
    var enableAutoTalkOnForeground: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_AUTO_TALK_ON_FOREGROUND, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_AUTO_TALK_ON_FOREGROUND, value).apply()

    /** Floating window button size in dp (32~120). Default 56. */
    var floatingWindowSize: Int
        get() = prefs.getInt(KEY_FLOATING_SIZE, DEFAULT_FLOATING_SIZE)
        set(value) = prefs.edit().putInt(KEY_FLOATING_SIZE, value).apply()

    /** Floating window opacity 0.1~1.0. Default 0.7. */
    var floatingWindowAlpha: Float
        get() = prefs.getFloat(KEY_FLOATING_ALPHA, DEFAULT_FLOATING_ALPHA)
        set(value) = prefs.edit().putFloat(KEY_FLOATING_ALPHA, value).apply()

    /** Floating window X position in pixels. -1 = use default. */
    var floatingWindowX: Int
        get() = prefs.getInt(KEY_FLOATING_X, -1)
        set(value) = prefs.edit().putInt(KEY_FLOATING_X, value).apply()

    /** Floating window Y position in pixels. -1 = use default. */
    var floatingWindowY: Int
        get() = prefs.getInt(KEY_FLOATING_Y, -1)
        set(value) = prefs.edit().putInt(KEY_FLOATING_Y, value).apply()

    // ==================== Bundled Model Defaults ====================

    /**
     * Applies the configuration bundled for the exact Build.MODEL.
     * Existing preferences always win so upgrading Hey does not overwrite
     * settings the user has already changed.
     */
    private fun applyBundledModelDefaults(context: Context) {
        val marker = "$LOCAL_CONFIG_VERSION:${Build.MODEL}"
        if (prefs.getString(KEY_LOCAL_CONFIG_MARKER, null) == marker) return

        val root = runCatching {
            context.resources.openRawResource(R.raw.device_configs)
                .bufferedReader()
                .use { JSONObject(it.readText()) }
        }.getOrNull()
        val modelConfig = root?.optJSONObject(Build.MODEL) ?: root?.optJSONObject("_default")
        val editor = prefs.edit()

        if (modelConfig != null) {
            val intProps = mapOf(
                "audioUsage" to KEY_AUDIO_USAGE,
                "ampTransactionCode" to KEY_AMP_TXN_CODE,
                "audioSource" to KEY_AUDIO_SOURCE,
                "inputDeviceId" to KEY_INPUT_DEVICE_ID,
                "outputDeviceId" to KEY_OUTPUT_DEVICE_ID,
                "dspMode" to KEY_DSP_MODE,
                "reverbMode" to KEY_REVERB_MODE,
                "floatingWindowSize" to KEY_FLOATING_SIZE,
                "highPassCutoff" to KEY_HIGH_PASS_CUTOFF,
                "lowPassCutoff" to KEY_LOW_PASS_CUTOFF,
                "launchAction" to KEY_LAUNCH_ACTION,
            )
            val floatProps = mapOf(
                "volumeGain" to KEY_VOLUME_GAIN,
                "minGainLimit" to KEY_MIN_GAIN_LIMIT,
                "maxGainLimit" to KEY_MAX_GAIN_LIMIT,
                "rippleThreshold" to KEY_RIPPLE_THRESHOLD,
                "floatingWindowAlpha" to KEY_FLOATING_ALPHA,
                "noiseGateStrength" to KEY_NOISE_GATE_STRENGTH,
                "freqShiftHz" to KEY_FREQ_SHIFT_HZ,
            )
            val boolProps = mapOf(
                "enableNoiseGate" to KEY_ENABLE_NOISE_GATE,
                "enableAgc" to KEY_ENABLE_AGC,
                "enableLowPass" to KEY_ENABLE_BANDPASS,
                "enableBandpass" to KEY_ENABLE_BANDPASS,
                "enableFreqShift" to KEY_ENABLE_FREQ_SHIFT,
                "enableFloatingWindow" to KEY_ENABLE_FLOATING,
                "enableAutoTalkOnForeground" to KEY_ENABLE_AUTO_TALK_ON_FOREGROUND,
            )
            val stringProps = mapOf(
                "outputDeviceAddress" to KEY_OUTPUT_DEVICE,
            )

            for ((jsonKey, prefKey) in intProps) {
                if (modelConfig.has(jsonKey) && !prefs.contains(prefKey)) {
                    editor.putInt(prefKey, modelConfig.optInt(jsonKey))
                }
            }
            for ((jsonKey, prefKey) in floatProps) {
                if (modelConfig.has(jsonKey) && !prefs.contains(prefKey)) {
                    editor.putFloat(prefKey, modelConfig.optDouble(jsonKey).toFloat())
                }
            }
            for ((jsonKey, prefKey) in boolProps) {
                if (modelConfig.has(jsonKey) && !prefs.contains(prefKey)) {
                    editor.putBoolean(prefKey, modelConfig.optBoolean(jsonKey))
                }
            }
            for ((jsonKey, prefKey) in stringProps) {
                if (modelConfig.has(jsonKey) && !prefs.contains(prefKey)) {
                    editor.putString(prefKey, modelConfig.optString(jsonKey, ""))
                }
            }
        }

        editor.putString(KEY_LOCAL_CONFIG_MARKER, marker).apply()
    }

    // ==================== Volume Group ====================

    var volumeGroupId: Int
        get() = prefs.getInt(KEY_VOLUME_GROUP_ID, -1)
        set(value) = prefs.edit().putInt(KEY_VOLUME_GROUP_ID, value).apply()

    // ==================== Defaults ====================

    companion object {
        private const val PREFS_NAME = "hey_config"

        private const val KEY_QUICK_SOUND_COUNT = "quick_sound_count"
        const val DEFAULT_QUICK_SOUND_COUNT = 6
        const val MAX_QUICK_SOUNDS = 12
        const val MIN_QUICK_SOUNDS = 1

        private const val KEY_ENABLE_BG_KEY_MONITOR = "enable_bg_key_monitor"
        private const val KEY_APP_SWITCH_KEY = "app_switch_key"
        private const val KEY_APP_SWITCH_GESTURE = "app_switch_gesture"
        private const val KEY_TALK_KEY = "talk_key"
        private const val KEY_TALK_GESTURE = "talk_gesture"

        private const val KEY_AUDIO_USAGE = "audio_usage_custom"  // keep old key for migration
        private const val KEY_OUTPUT_DEVICE = "output_device"
        private const val KEY_AUDIO_SOURCE = "audio_source"
        private const val KEY_INPUT_DEVICE_ID = "input_device_id"
        private const val KEY_OUTPUT_DEVICE_ID = "output_device_id"
        private const val KEY_VOLUME_GAIN = "volume_gain"
        private const val KEY_MIN_GAIN_LIMIT = "min_gain_limit"
        private const val KEY_MAX_GAIN_LIMIT = "max_gain_limit"
        private const val KEY_ENABLE_NOISE_GATE = "enable_noise_gate"
        private const val KEY_NOISE_GATE_STRENGTH = "noise_gate_strength"
        private const val KEY_ENABLE_AGC = "enable_agc"
        private const val KEY_ENABLE_LOW_PASS = "enable_low_pass"
        private const val KEY_ENABLE_BANDPASS = "enable_bandpass"
        private const val KEY_ENABLE_HIGH_PASS = "enable_high_pass"
        private const val KEY_HIGH_PASS_CUTOFF = "high_pass_cutoff"
        private const val KEY_LOW_PASS_CUTOFF = "low_pass_cutoff"
        private const val KEY_ENABLE_FREQ_SHIFT = "enable_freq_shift"
        private const val KEY_FREQ_SHIFT_HZ = "freq_shift_hz"
        private const val KEY_REVERB_MODE = "reverb_mode"
        private const val KEY_ENABLE_LOGCAT_TRIGGER = "enable_logcat_trigger"
        private const val KEY_LOGCAT_TRIGGER_KEYWORD = "logcat_trigger_keyword"
        private const val KEY_ENABLE_FLOATING = "enable_floating"
        private const val KEY_ENABLE_AUTO_TALK_ON_FOREGROUND = "enable_auto_talk_on_foreground"
        private const val KEY_FLOATING_SIZE = "floating_size"
        private const val KEY_FLOATING_ALPHA = "floating_alpha"
        private const val KEY_FLOATING_X = "floating_x"
        private const val KEY_FLOATING_Y = "floating_y"
        private const val KEY_DSP_MODE = "dsp_mode"
        private const val KEY_RIPPLE_THRESHOLD = "ripple_threshold"
        private const val KEY_MUSIC_FOLDER = "music_folder"
        private const val KEY_MUSIC_VOLUME = "music_volume"
        private const val KEY_MUSIC_SPEAKER_DEST = "music_speaker_dest"
        private const val KEY_MUSIC_PLAY_MODE = "music_play_mode"
        private const val KEY_VOLUME_GROUP_ID = "volume_group_id"
        private const val KEY_LAUNCH_ACTION = "launch_action"
        private const val KEY_LAUNCH_AUTO_PLAY = "launch_auto_play"
        private const val KEY_LAUNCH_MUSIC_INDEX = "launch_music_index"
        private const val KEY_AMP_TXN_CODE = "amp_transaction_code"
        private const val KEY_LOCAL_CONFIG_MARKER = "local_config_marker"
        private const val LOCAL_CONFIG_VERSION = 1

        const val MUSIC_SPEAKER_IN_CABIN = 0
        const val MUSIC_SPEAKER_EXTERNAL = 1

        const val DEFAULT_AMP_TXN_CODE = 55
        const val DEFAULT_AUDIO_SOURCE = 7  // VOICE_COMMUNICATION (with system AEC/NS)
        const val DEFAULT_VOLUME_GAIN = 5.0f  // 5x boost
        const val DEFAULT_MIN_GAIN_LIMIT = 1.0f
        const val DEFAULT_MAX_GAIN_LIMIT = 30.0f
        const val DEFAULT_FLOATING_SIZE = 56  // dp
        const val DEFAULT_FLOATING_ALPHA = 0.7f
        const val DEFAULT_RIPPLE_THRESHOLD = 0.03f
        const val DEFAULT_AUDIO_USAGE = 73
        const val DEFAULT_HIGH_PASS_CUTOFF = 300
        const val DEFAULT_LOW_PASS_CUTOFF = 4000
        const val DEFAULT_FREQ_SHIFT_HZ = 5.0f
        private const val DEFAULT_NOISE_GATE_STRENGTH = 0.5f
        const val DEFAULT_MEDIA_VOLUME = 0.7f  // 70% default volume
        val DEFAULT_MUSIC_FOLDER: String = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/Hey/music"

        const val LAUNCH_ACTION_TALK = 0
        const val LAUNCH_ACTION_MUSIC_PANEL = 1
    }
}
