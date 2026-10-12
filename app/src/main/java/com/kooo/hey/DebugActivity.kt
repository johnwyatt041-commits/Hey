package com.kooo.hey

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.kooo.hey.audio.AmpManager
import com.kooo.hey.audio.AudioEngine
import com.kooo.hey.audio.AudioUsageNames
import com.kooo.hey.audio.CarVolumeHelper
import com.kooo.hey.audio.EcarxWorkMode
import com.kooo.hey.config.AppConfig
import com.kooo.hey.databinding.ActivityDebugBinding
import com.kooo.hey.databinding.IncludeDebugLeftBinding
import com.kooo.hey.log.AppLog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugBinding
    private lateinit var bindingLeft: IncludeDebugLeftBinding
    private lateinit var config: AppConfig
    private lateinit var audioEngine: AudioEngine
    private lateinit var carVolumeHelper: CarVolumeHelper
    private lateinit var adbHelper: AdbPermissionHelper
    private val logBuilder = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bindingLeft = binding.leftPanel

        // Edge-to-edge: content draws behind system bars, background extends to status bar
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Set status bar appearance based on system theme
        val isLightTheme = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView)?.apply {
            isAppearanceLightStatusBars = isLightTheme
            isAppearanceLightNavigationBars = isLightTheme
        }

        // Get the root ScrollView and apply bottom padding only for navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }

        config = AppConfig(this)
        audioEngine = AudioEngine(this)
        audioEngine.onLog = { msg -> appendLog(msg) }
        audioEngine.onLevel = { input, output ->
            binding.waveformView.pushLevel(input, output)
        }
        carVolumeHelper = CarVolumeHelper(this)
        carVolumeHelper.onLog = { msg -> appendLog(msg) }

        adbHelper = AdbPermissionHelper(this)

        // Connect EcarxWorkMode log callback
        EcarxWorkMode.onLog = { msg -> appendLog(msg) }

        loadConfigToUI()
        setupButtons()
        setupAdbButtons()
        setupVolumeGroup()
        setupAmpControl()
        setupLogScroll()
        setupAutoSave()

        appendLog("调试面板已打开")
    }

    override fun onResume() {
        super.onResume()
        AmpManager.acquire(this, AmpManager.SESSION_FOREGROUND)
        audioEngine.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        AmpManager.release(this, AmpManager.SESSION_FOREGROUND)
        audioEngine.onAppBackground()
    }

    override fun onDestroy() {
        super.onDestroy()
        audioEngine.destroy()
        audioEngine.stopTestTone()
        carVolumeHelper.disconnect()
    }

    // ==================== Load / Save Config ====================

    private fun loadConfigToUI() {
        // Shortcut settings
        bindingLeft.checkLogcatTrigger.isChecked = config.enableLogcatTrigger
        bindingLeft.editLogcatKeyword.setText(config.logcatTriggerKeyword)
        bindingLeft.layoutLogcatParams.visibility = if (config.enableLogcatTrigger) View.VISIBLE else View.GONE
        bindingLeft.checkFloatingWindow.isChecked = config.enableFloatingWindow
        bindingLeft.checkAutoTalkOnForeground.isChecked = config.enableAutoTalkOnForeground
        setupLaunchActionRadio()

        bindingLeft.seekFloatingSize.progress = config.floatingWindowSize - 32
        bindingLeft.textFloatingSize.text = "${config.floatingWindowSize}dp"
        bindingLeft.seekFloatingAlpha.progress = ((config.floatingWindowAlpha - 0.1f) / 0.1f).toInt()
        bindingLeft.textFloatingAlpha.text = String.format("%.1f", config.floatingWindowAlpha)
        bindingLeft.editMusicFolder.setText(config.musicFolderPath)

        // Audio settings
        bindingLeft.editAudioUsage.setText(config.audioUsage.toString())
        bindingLeft.editAmpTxnCode.setText(config.ampTransactionCode.toString())
        bindingLeft.editAudioSource.setText(config.audioSource.toString())
        bindingLeft.editInputDeviceId.setText(config.inputDeviceId.toString())
        bindingLeft.editOutputDeviceId.setText(config.outputDeviceId.toString())
        bindingLeft.checkNoiseGate.isChecked = config.enableNoiseGate
        bindingLeft.layoutNoiseGateStrength.visibility = if (config.enableNoiseGate) View.VISIBLE else View.GONE
        bindingLeft.seekNoiseGateStrength.progress = (config.noiseGateStrength * 100f).toInt().coerceIn(0, 100)
        bindingLeft.textNoiseGateStrength.text = "${bindingLeft.seekNoiseGateStrength.progress}%"
        bindingLeft.checkAgc.isChecked = config.enableAgc
        bindingLeft.checkBandpass.isChecked = config.enableBandpass
        bindingLeft.layoutBandpass.visibility = if (config.enableBandpass) View.VISIBLE else View.GONE
        bindingLeft.sliderBandpass.values = listOf(
            config.highPassCutoff.toFloat().coerceIn(100f, 8000f),
            config.lowPassCutoff.toFloat().coerceIn(100f, 8000f)
        )
        bindingLeft.textBandpassRange.text = "${config.highPassCutoff}Hz ~ ${config.lowPassCutoff}Hz"
        bindingLeft.checkFreqShift.isChecked = config.enableFreqShift
        bindingLeft.layoutFreqShift.visibility = if (config.enableFreqShift) View.VISIBLE else View.GONE
        bindingLeft.seekFreqShift.progress = ((config.freqShiftHz - 1f) * 2).toInt()
        bindingLeft.textFreqShift.text = String.format("%.1fHz", config.freqShiftHz)
        setupReverbRadio()
        bindingLeft.editMinGainLimit.setText(config.minGainLimit.toInt().toString())
        bindingLeft.editMaxGainLimit.setText(config.maxGainLimit.toInt().toString())
    }

    private fun saveConfigFromUI() {
        try {
            // Shortcut settings
            config.enableLogcatTrigger = bindingLeft.checkLogcatTrigger.isChecked
            config.logcatTriggerKeyword = bindingLeft.editLogcatKeyword.text.toString().trim()
            config.enableFloatingWindow = bindingLeft.checkFloatingWindow.isChecked
            config.enableAutoTalkOnForeground = bindingLeft.checkAutoTalkOnForeground.isChecked
            config.floatingWindowSize = bindingLeft.seekFloatingSize.progress + 32
            config.floatingWindowAlpha = bindingLeft.seekFloatingAlpha.progress * 0.1f + 0.1f
            val musicFolder = bindingLeft.editMusicFolder.text.toString().trim()
            if (musicFolder.isNotEmpty()) config.musicFolderPath = musicFolder

            // Audio settings
            bindingLeft.editAudioUsage.text.toString().trim().toIntOrNull()?.let {
                config.audioUsage = it.coerceAtLeast(0)
            }
            bindingLeft.editAmpTxnCode.text.toString().trim().toIntOrNull()?.let {
                config.ampTransactionCode = it.coerceIn(1, 255)
            }
            config.enableNoiseGate = bindingLeft.checkNoiseGate.isChecked
            config.noiseGateStrength = bindingLeft.seekNoiseGateStrength.progress / 100f
            config.enableAgc = bindingLeft.checkAgc.isChecked
            config.enableBandpass = bindingLeft.checkBandpass.isChecked
            val bpValues = bindingLeft.sliderBandpass.values
            config.highPassCutoff = bpValues[0].toInt()
            config.lowPassCutoff = bpValues[1].toInt()
            config.enableFreqShift = bindingLeft.checkFreqShift.isChecked
            config.freqShiftHz = bindingLeft.seekFreqShift.progress / 2f + 1f

            // Gain range limits
            bindingLeft.editMaxGainLimit.text.toString().trim().toFloatOrNull()?.let {
                config.maxGainLimit = it
            }
            bindingLeft.editMinGainLimit.text.toString().trim().toFloatOrNull()?.let {
                config.minGainLimit = it
            }

            // Save audio source
            bindingLeft.editAudioSource.text.toString().trim().toIntOrNull()?.let {
                config.audioSource = it.coerceAtLeast(0)
            }

            // Save input device ID (empty treated as 0)
            config.inputDeviceId = (bindingLeft.editInputDeviceId.text.toString().trim().toIntOrNull() ?: 0).coerceAtLeast(0)

            // Save output device ID (empty treated as 0)
            config.outputDeviceId = (bindingLeft.editOutputDeviceId.text.toString().trim().toIntOrNull() ?: 0).coerceAtLeast(0)
        } catch (_: Exception) {
        }
    }

    /** Wire up listeners so every UI change auto-saves to SharedPreferences. */
    private fun setupAutoSave() {
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { saveConfigFromUI() }
        }

        val floatingSeekListener = object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                when (seekBar?.id) {
                    R.id.seekFloatingSize -> bindingLeft.textFloatingSize.text = "${progress + 32}dp"
                    R.id.seekFloatingAlpha -> bindingLeft.textFloatingAlpha.text = String.format("%.1f", progress * 0.1f + 0.1f)
                }
                saveConfigFromUI()
                FloatingWindowService.instance?.refreshAppearance()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        }

        bindingLeft.seekFloatingSize.setOnSeekBarChangeListener(floatingSeekListener)
        bindingLeft.seekFloatingAlpha.setOnSeekBarChangeListener(floatingSeekListener)
        bindingLeft.editAudioUsage.addTextChangedListener(textWatcher)
        bindingLeft.editAmpTxnCode.addTextChangedListener(textWatcher)
        bindingLeft.editAudioSource.addTextChangedListener(textWatcher)
        bindingLeft.editInputDeviceId.addTextChangedListener(textWatcher)
        bindingLeft.editOutputDeviceId.addTextChangedListener(textWatcher)
        bindingLeft.editMinGainLimit.addTextChangedListener(textWatcher)
        bindingLeft.editMaxGainLimit.addTextChangedListener(textWatcher)
        bindingLeft.editMusicFolder.addTextChangedListener(textWatcher)

        bindingLeft.btnBrowseMusicFolder.setOnClickListener {
            val currentPath = bindingLeft.editMusicFolder.text.toString().trim()
            com.kooo.hey.ui.FolderPickerDialog(this, currentPath) { selectedPath ->
                bindingLeft.editMusicFolder.setText(selectedPath)
                saveConfigFromUI()
            }.show()
        }

        bindingLeft.checkLogcatTrigger.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // READ_LOGS is a development permission, must be granted via ADB
                if (checkSelfPermission(android.Manifest.permission.READ_LOGS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("需要日志读取权限")
                        .setMessage("请通过 ADB 授权日志读取权限：\n\nadb shell pm grant ${packageName} android.permission.READ_LOGS")
                        .setPositiveButton("知道了", null)
                        .show()
                    bindingLeft.checkLogcatTrigger.isChecked = false
                    return@setOnCheckedChangeListener
                }
            }
            bindingLeft.layoutLogcatParams.visibility = if (isChecked) View.VISIBLE else View.GONE
            saveConfigFromUI()
            LogcatTriggerService.syncWithConfig(this)
        }
        bindingLeft.editLogcatKeyword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                saveConfigFromUI()
                LogcatTriggerService.syncWithConfig(this@DebugActivity)
            }
        })
        bindingLeft.checkNoiseGate.setOnCheckedChangeListener { _, isChecked ->
            bindingLeft.layoutNoiseGateStrength.visibility = if (isChecked) View.VISIBLE else View.GONE
            saveConfigFromUI()
            audioEngine.liveNoiseGateEnabled = isChecked
        }
        bindingLeft.seekNoiseGateStrength.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                bindingLeft.textNoiseGateStrength.text = "${progress}%"
                saveConfigFromUI()
                audioEngine.liveNoiseGateStrength = progress / 100f
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        bindingLeft.checkAgc.setOnCheckedChangeListener { _, _ -> saveConfigFromUI() }
        bindingLeft.checkFreqShift.setOnCheckedChangeListener { _, isChecked ->
            bindingLeft.layoutFreqShift.visibility = if (isChecked) View.VISIBLE else View.GONE
            saveConfigFromUI()
            audioEngine.liveFreqShiftEnabled = isChecked
        }
        bindingLeft.seekFreqShift.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val hz = progress / 2f + 1f
                bindingLeft.textFreqShift.text = String.format("%.1fHz", hz)
                saveConfigFromUI()
                audioEngine.liveFreqShiftHz = hz
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        bindingLeft.checkBandpass.setOnCheckedChangeListener { _, isChecked ->
            bindingLeft.layoutBandpass.visibility = if (isChecked) View.VISIBLE else View.GONE
            saveConfigFromUI()
            audioEngine.liveBandpassEnabled = isChecked
        }
        bindingLeft.sliderBandpass.addOnChangeListener { slider, _, _ ->
            val values = slider.values
            val hp = values[0].toInt()
            val lp = values[1].toInt()
            bindingLeft.textBandpassRange.text = "${hp}Hz ~ ${lp}Hz"
            saveConfigFromUI()
            audioEngine.liveHighPassCutoff = hp
            audioEngine.liveLowPassCutoff = lp
        }
        bindingLeft.checkAutoTalkOnForeground.setOnCheckedChangeListener { _, _ -> saveConfigFromUI() }
        bindingLeft.checkFloatingWindow.setOnCheckedChangeListener { _, _ ->
            saveConfigFromUI()
            applyFloatingWindowConfig()
        }
    }

    /** Start/stop/refresh floating window service based on current config. */
    private fun applyFloatingWindowConfig() {
        if (config.enableFloatingWindow) {
            if (!Settings.canDrawOverlays(this)) {
                appendLog("⚠ 需要悬浮窗权限，正在打开设置…")
                Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
                return
            }
            // Start or refresh the service
            if (FloatingWindowService.instance != null) {
                FloatingWindowService.instance?.refreshAppearance()
                appendLog("✓ 悬浮窗已更新 (${config.floatingWindowSize}dp, " +
                        "透明度${config.floatingWindowAlpha})")
            } else {
                startForegroundService(Intent(this, FloatingWindowService::class.java))
                appendLog("✓ 悬浮窗已启动")
            }
        } else {
            // Stop the service
            if (FloatingWindowService.instance != null) {
                stopService(Intent(this, FloatingWindowService::class.java))
                appendLog("✓ 悬浮窗已关闭")
            }
        }
    }

    // ==================== Launch Action Radio ====================

    private fun setupLaunchActionRadio() {
        val checkedId = when (config.launchAction) {
            AppConfig.LAUNCH_ACTION_MUSIC_PANEL -> R.id.radioLaunchMusic
            else -> R.id.radioLaunchTalk
        }
        bindingLeft.radioLaunchAction.check(checkedId)
        refreshLaunchSubSettings()

        bindingLeft.radioLaunchAction.setOnCheckedChangeListener { _, id ->
            config.launchAction = when (id) {
                R.id.radioLaunchMusic -> AppConfig.LAUNCH_ACTION_MUSIC_PANEL
                else -> AppConfig.LAUNCH_ACTION_TALK
            }
            refreshLaunchSubSettings()
        }

        bindingLeft.checkLaunchAutoPlay.isChecked = config.launchAutoPlay
        bindingLeft.checkLaunchAutoPlay.setOnCheckedChangeListener { _, isChecked ->
            config.launchAutoPlay = isChecked
            refreshLaunchSubSettings()
        }

        bindingLeft.btnPickLaunchItem.setOnClickListener { showItemPickerDialog() }
    }

    private fun refreshLaunchSubSettings() {
        val isMusicMode = config.launchAction == AppConfig.LAUNCH_ACTION_MUSIC_PANEL
        bindingLeft.layoutLaunchAutoPlay.visibility = if (isMusicMode) View.VISIBLE else View.GONE

        val showPicker = isMusicMode && config.launchAutoPlay
        bindingLeft.btnPickLaunchItem.visibility = if (showPicker) View.VISIBLE else View.GONE
        if (showPicker) updatePickerButtonText()
    }

    private fun updatePickerButtonText() {
        val tracks = scanMusicTrackNames()
        val idx = config.launchMusicIndex
        bindingLeft.btnPickLaunchItem.text = if (idx in tracks.indices)
            "音乐: ${tracks[idx]}" else "点击选择音乐曲目…"
    }

    private fun showItemPickerDialog() {
        val tracks = scanMusicTrackNames()
        if (tracks.isEmpty()) {
            Toast.makeText(this, "音乐文件夹为空: ${config.musicFolderPath}", Toast.LENGTH_SHORT).show()
            return
        }
        val currentIdx = config.launchMusicIndex.coerceIn(0, tracks.size - 1)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("选择启动时播放的音乐")
            .setSingleChoiceItems(tracks.toTypedArray(), currentIdx) { dialog, which ->
                config.launchMusicIndex = which
                updatePickerButtonText()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun scanMusicTrackNames(): List<String> {
        val folder = java.io.File(config.musicFolderPath)
        if (!folder.exists() || !folder.isDirectory) return emptyList()
        val exts = setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "wma")
        return folder.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in exts }
            ?.sortedBy { it.name.lowercase() }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }

    // ==================== Reverb Mode Radio ====================

    private fun setupReverbRadio() {
        val checkedId = when (config.reverbMode) {
            1 -> R.id.radioReverbMedium
            2 -> R.id.radioReverbStrong
            else -> R.id.radioReverbOff
        }
        bindingLeft.radioReverbMode.check(checkedId)
        bindingLeft.radioReverbMode.setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                R.id.radioReverbMedium -> 1
                R.id.radioReverbStrong -> 2
                else -> 0
            }
            config.reverbMode = mode
        }
    }

    // ==================== Microphone Enumeration ====================

    private fun enumMicrophones() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        appendLog("══ 输入设备 (麦克风) ══  共 ${inputs.size} 个")
        for (dev in inputs) {
            val typeName = inputDeviceTypeName(dev.type)
            val channels = dev.channelCounts.let { if (it.isEmpty()) "默认" else it.toList().toString() }
            val rates = dev.sampleRates.let { if (it.isEmpty()) "默认" else it.toList().toString() }
            appendLog("─────────────────────")
            appendLog("  ID: ${dev.id}  类型: ${dev.type} ($typeName)")
            appendLog("  地址: '${dev.address}'")
            appendLog("  通道数: $channels")
            appendLog("  采样率: $rates")
            appendLog("  isSource: ${dev.isSource}")
        }
        appendLog("══ 输出设备 (扬声器) ══")
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        appendLog("共 ${outputs.size} 个")
        for (dev in outputs) {
            val typeName = outputDeviceTypeName(dev.type)
            appendLog("  ID:${dev.id} 类型:${dev.type}($typeName) 地址:'${dev.address}'")
        }
        appendLog("══════════════════════")
    }

    private fun inputDeviceTypeName(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙A2DP"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB设备"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB耳机"
        AudioDeviceInfo.TYPE_TELEPHONY -> "电话"
        AudioDeviceInfo.TYPE_IP -> "IP网络"
        AudioDeviceInfo.TYPE_BUS -> "BUS总线"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "远程混音"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM调谐器"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
        else -> "未知($type)"
    }

    private fun outputDeviceTypeName(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "内置扬声器"
        AudioDeviceInfo.TYPE_BUS -> "BUS总线"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙SCO"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB设备"
        AudioDeviceInfo.TYPE_AUX_LINE -> "AUX线路"
        else -> "未知($type)"
    }

    // ==================== Buttons ====================

    private fun setupButtons() {
        binding.btnClose.setOnClickListener { finish() }

        binding.btnClearLog.setOnClickListener {
            logBuilder.clear()
            binding.textLog.text = ""
        }

        binding.btnCopyLog.setOnClickListener {
            val text = logBuilder.toString()
            if (text.isEmpty()) {
                Toast.makeText(this, "日志为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Hey! Log", text))
            Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        bindingLeft.btnEnumMics.setOnClickListener { enumMicrophones() }

        // ---- Audio test buttons ----

        binding.btnTestTone.setOnClickListener {
            val usage = config.audioUsage
            val outDev = audioEngine.findOutputDeviceById(config.outputDeviceId)
            appendLog("── 测试播放 ── usage=$usage, outputDeviceId=${config.outputDeviceId}")
            audioEngine.playTestTone(usage, outDev)
        }

        binding.btnTestMic.setOnClickListener {
            if (audioEngine.running) {
                appendLog("已在运行中")
                return@setOnClickListener
            }
            val usage = config.audioUsage
            val src = config.audioSource
            val gain = config.volumeGain
            val agc = config.enableAgc
            val reverb = config.enableReverb
            val mix = config.reverbMix
            appendLog("── 测试喊话 ── usage=$usage, src=$src, gain=${gain}x, AGC=$agc, 混响=$reverb($mix)")
            audioEngine.start(
                audioUsage = usage,
                outputDevice = audioEngine.findOutputDeviceById(config.outputDeviceId),
                inputDevice = audioEngine.findInputDevice(config.inputDeviceId),
                audioSource = src,
                volumeGain = gain,
                enableNoiseGate = config.enableNoiseGate,
                noiseGateStrength = config.noiseGateStrength,
                enableAgc = agc,
                enableReverb = reverb,
                reverbMix = mix,
                enableHighPass = config.enableHighPass,
                highPassCutoff = config.highPassCutoff,
                enableLowPass = config.enableLowPass,
                lowPassCutoff = config.lowPassCutoff,
                enableFreqShift = config.enableFreqShift,
                freqShiftHz = config.freqShiftHz,
                dspMode = config.dspMode,
            )
        }

        binding.btnStopTest.setOnClickListener {
            appendLog("── 停止 ──")
            audioEngine.stop()
            audioEngine.stopTestTone()
            audioEngine.stopUsageSweep()
            binding.waveformView.clear()
        }

        bindingLeft.btnUsageSweep.setOnClickListener {
            appendLog("── Usage 遍历测试 ──")
            audioEngine.startUsageSweep(toneDurationMs = 800)
        }

        bindingLeft.btnStopSweep.setOnClickListener {
            appendLog("── 停止遍历 ──")
            audioEngine.stopUsageSweep()
        }
    }

    // ==================== 音量组设置 ====================

    private var volumeGroupId: Int
        get() = config.volumeGroupId
        set(value) { config.volumeGroupId = value }

    private fun setupVolumeGroup() {
        bindingLeft.seekVolume.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                bindingLeft.textVolumeValue.text = "$progress"
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        if (volumeGroupId >= 0) {
            bindingLeft.editVolumeGroupId.setText(volumeGroupId.toString())
        }

        bindingLeft.btnVolumeAutoMatch.setOnClickListener {
            autoMatchVolumeGroup()
        }

        bindingLeft.btnVolumeDump.setOnClickListener {
            val manualGroupId = bindingLeft.editVolumeGroupId.text.toString().trim().toIntOrNull()
            if (manualGroupId != null) rememberGroupId(manualGroupId)
            val cmd = if (manualGroupId != null) {
                appendLog("══ 查询音量组 $manualGroupId ══")
                "get $manualGroupId"
            } else {
                appendLog("══ 查询全部音量组 ══")
                "dump"
            }
            runVolumeShell(cmd) { output ->
                parseVolumeOutput(output, manualGroupId)
            }
        }

        bindingLeft.btnVolumeSet.setOnClickListener {
            val targetVol = bindingLeft.seekVolume.progress
            val manualGroupId = bindingLeft.editVolumeGroupId.text.toString().trim().toIntOrNull()
            if (manualGroupId != null) rememberGroupId(manualGroupId)
            val gid = manualGroupId ?: volumeGroupId
            if (gid < 0) {
                appendLog("请先查询或手动填入音量组 ID")
                return@setOnClickListener
            }
            appendLog("设置音量组 $gid → $targetVol")
            runVolumeShell("set $gid $targetVol") { output ->
                for (line in output.lines()) {
                    when {
                        line.startsWith("SET=") -> {
                            val actual = line.substringAfter("ACTUAL=", "?")
                            appendLog("设置完成，实际音量 = $actual")
                        }
                        line.startsWith("ERROR=") -> appendLog("设置失败: ${line.substringAfter("ERROR=")}")
                        line == "OK" -> {}
                    }
                }
            }
        }
    }

    private fun rememberGroupId(gid: Int) {
        volumeGroupId = gid
        bindingLeft.editVolumeGroupId.setText(gid.toString())
    }

    /**
     * Auto-match volume group from current audio config:
     * - outputDeviceId > 0 → resolve bus address → find group in car_audio_configuration.xml
     * - outputDeviceId == 0 → use audioUsage → CarAudioManager.getVolumeGroupIdForUsage()
     */
    private fun autoMatchVolumeGroup() {
        val deviceId = config.outputDeviceId
        if (deviceId > 0) {
            autoMatchByDevice(deviceId)
        } else {
            autoMatchByUsage(config.audioUsage)
        }
    }

    private fun autoMatchByUsage(usage: Int) {
        appendLog("══ 自动匹配音量组 ══  usage=$usage")
        runVolumeShell("usage $usage") { output ->
            var matched = false
            for (line in output.lines()) {
                when {
                    line.startsWith("USAGE=") -> {
                        val parts = line.split("|").associate {
                            val (k, v) = it.split("=", limit = 2)
                            k to v
                        }
                        val groupId = parts["GROUP"]?.toIntOrNull()
                        if (groupId != null && groupId >= 0) {
                            appendLog("匹配成功: usage $usage → 音量组 $groupId")
                            rememberGroupId(groupId)
                            matched = true
                        } else {
                            appendLog("usage $usage 没有对应的音量组")
                        }
                    }
                    line.startsWith("GROUP=") && matched -> {
                        parseVolumeOutput(line + "\n", volumeGroupId)
                    }
                    line.startsWith("ERROR=") -> {
                        appendLog("匹配失败: ${line.substringAfter("ERROR=")}")
                    }
                }
            }
            if (matched) {
                runVolumeShell("get $volumeGroupId") { volOutput ->
                    parseVolumeOutput(volOutput, volumeGroupId)
                }
            }
        }
    }

    private fun autoMatchByDevice(deviceId: Int) {
        val device = audioEngine.findOutputDeviceById(deviceId)
        if (device == null) {
            appendLog("找不到输出设备 ID=$deviceId")
            return
        }
        val address = device.address
        if (address.isNullOrBlank()) {
            appendLog("输出设备 ID=$deviceId 没有地址信息")
            return
        }
        appendLog("══ 自动匹配音量组 ══  设备ID=$deviceId  地址=$address")
        runVolumeShell("device_group $address") { output ->
            var matched = false
            for (line in output.lines()) {
                when {
                    line.startsWith("DEVICE_GROUP=") -> {
                        val parts = line.split("|").associate {
                            val (k, v) = it.split("=", limit = 2)
                            k to v
                        }
                        val groups = parts["GROUPS"] ?: "none"
                        if (groups == "none") {
                            appendLog("未找到 $address 对应的音量组")
                        } else {
                            val groupIds = groups.split(",").mapNotNull { it.trim().toIntOrNull() }
                            if (groupIds.size == 1) {
                                appendLog("匹配成功: $address → 音量组 ${groupIds[0]}")
                            } else {
                                appendLog("匹配到多个音量组: $address → ${groupIds.joinToString(", ") { "组$it" }}，使用第一个")
                            }
                            val gid = groupIds.first()
                            rememberGroupId(gid)
                            matched = true
                            runVolumeShell("get $gid") { volOutput ->
                                parseVolumeOutput(volOutput, gid)
                            }
                        }
                    }
                    line.startsWith("ERROR=") -> {
                        appendLog("匹配失败: ${line.substringAfter("ERROR=")}")
                    }
                }
            }
            if (!matched && !output.contains("ERROR=") && !output.contains("DEVICE_GROUP=")) {
                appendLog("匹配未返回结果，请检查日志")
            }
        }
    }

    // ==================== 功放控制（HwBinder 直调）====================

    private fun setupAmpControl() {
        bindingLeft.btnAmpOn.setOnClickListener {
            appendLog("══ 扬声器上电 ══")
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val err = AmpManager.manualPowerOn(this@DebugActivity)
                runOnUiThread {
                    appendLog(if (err == null) "✓ 上电成功" else "✗ 上电失败: $err")
                }
            }
        }
        bindingLeft.btnAmpOff.setOnClickListener {
            appendLog("══ 扬声器断电 ══")
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val err = AmpManager.manualPowerOff(this@DebugActivity)
                runOnUiThread {
                    appendLog(if (err == null) "✓ 断电成功" else "✗ 断电失败: $err")
                }
            }
        }
        bindingLeft.btnAmpStatus.setOnClickListener {
            appendLog(AmpManager.statusText())
        }
    }

    private fun runVolumeShell(volumeCmd: String, onResult: (String) -> Unit) {
        val shellCmd = buildString {
            append("APK=\$(pm path ${packageName} 2>/dev/null | head -1 | sed 's/package://'); ")
            append("CAR_JAR=''; ")
            append("for p in /system/framework/android.car.jar /system/framework/android.car/android.car.jar; do ")
            append("if [ -f \"\$p\" ]; then CAR_JAR=\"\$p\"; break; fi; done; ")
            append("if [ -n \"\$CAR_JAR\" ]; then export CLASSPATH=\"\$CAR_JAR:\$APK\"; ")
            append("else export CLASSPATH=\"\$APK\"; fi; ")
            append("app_process /system/bin com.kooo.hey.audio.VolumeShellMain $volumeCmd")
        }

        lifecycleScope.launch {
            adbHelper.executeShellCommandAsync(shellCmd, 30000, object : AdbPermissionHelper.Callback {
                private val output = StringBuilder()

                override fun onLog(message: String) {
                    output.appendLine(message)
                }

                override fun onComplete(allSuccess: Boolean) {
                    runOnUiThread {
                        if (!allSuccess) {
                            appendLog("连接失败")
                        }
                        onResult(output.toString())
                    }
                }
            })
        }
    }

    private fun parseVolumeOutput(output: String, queryGroupId: Int?) {
        var foundAny = false
        for (line in output.lines()) {
            when {
                line.startsWith("GROUP_COUNT=") -> {
                    appendLog("音量组数: ${line.substringAfter("GROUP_COUNT=")}")
                }
                line.startsWith("GROUP=") -> {
                    val parts = line.split("|").associate {
                        val (k, v) = it.split("=", limit = 2)
                        k to v
                    }
                    val gid = parts["GROUP"]?.toIntOrNull() ?: continue
                    val cur = parts["CUR"]?.toIntOrNull() ?: 0
                    val min = parts["MIN"]?.toIntOrNull() ?: 0
                    val max = parts["MAX"]?.toIntOrNull() ?: 0
                    appendLog("组$gid  $cur / $max  (min $min)")
                    foundAny = true

                    if (queryGroupId != null && gid == queryGroupId) {
                        rememberGroupId(gid)
                        bindingLeft.seekVolume.max = max
                        bindingLeft.seekVolume.progress = cur
                        bindingLeft.textVolumeValue.text = "$cur"
                    }
                }
                line.startsWith("USAGE=") -> {
                    val parts = line.split("|").associate {
                        val (k, v) = it.split("=", limit = 2)
                        k to v
                    }
                    val usage = parts["USAGE"]
                    val group = parts["GROUP"]
                    appendLog("usage $usage → 组$group")
                }
                line.startsWith("ERROR=") -> {
                    appendLog("错误: ${line.substringAfter("ERROR=")}")
                }
                line == "OK" -> { /* no-op */ }
            }
        }
    }

    // ==================== ADB DSP Buttons ====================

    private fun setupAdbButtons() {
        bindingLeft.btnAdbSetBypass.setOnClickListener {
            setWorkmodeViaAdb(EcarxWorkMode.BYPASS)
        }

        bindingLeft.btnAdbSetNoise.setOnClickListener {
            setWorkmodeViaAdb(EcarxWorkMode.NOISE_CLEAN)
        }

        bindingLeft.btnAdbSetEcho.setOnClickListener {
            setWorkmodeViaAdb(EcarxWorkMode.ECHO_CANCEL)
        }

        bindingLeft.btnAdbSetWakeup.setOnClickListener {
            setWorkmodeViaAdb(EcarxWorkMode.VOICE_WAKEUP)
        }

        bindingLeft.btnAdbGetWorkmode.setOnClickListener {
            getWorkmodeViaAdb()
        }
    }

    private fun setWorkmodeViaAdb(mode: Int) {
        val modeName = when (mode) {
            EcarxWorkMode.BYPASS -> "关闭"
            EcarxWorkMode.NOISE_CLEAN -> "噪音抑制"
            EcarxWorkMode.ECHO_CANCEL -> "回声消除"
            EcarxWorkMode.VOICE_WAKEUP -> "唤醒优化"
            else -> "未知"
        }

        lifecycleScope.launch {
            adbHelper.executeShellCommandAsync("settings put system workmode $mode", object : AdbPermissionHelper.Callback {
                override fun onLog(message: String) {
                    // Don't expose ADB connection details in UI
                }

                override fun onComplete(allSuccess: Boolean) {
                    runOnUiThread {
                        if (allSuccess) {
                            android.widget.Toast.makeText(
                                this@DebugActivity,
                                "DSP 模式已设置为 $modeName",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            android.widget.Toast.makeText(
                                this@DebugActivity,
                                "设置失败，可能是系统不支持",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            })
        }
    }

    private fun getWorkmodeViaAdb() {
        lifecycleScope.launch {
            adbHelper.executeShellCommandAsync("settings get system workmode", object : AdbPermissionHelper.Callback {
                override fun onLog(message: String) {
                    // Parse the result value but don't expose ADB details
                    val value = message.trim().toIntOrNull()
                    if (value != null) {
                        val modeName = when (value) {
                            EcarxWorkMode.BYPASS -> "关闭"
                            EcarxWorkMode.NOISE_CLEAN -> "噪音抑制"
                            EcarxWorkMode.ECHO_CANCEL -> "回声消除"
                            EcarxWorkMode.VOICE_WAKEUP -> "唤醒优化"
                            else -> "未知($value)"
                        }
                        runOnUiThread {
                            android.widget.Toast.makeText(
                                this@DebugActivity,
                                "当前模式: $modeName",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

                override fun onComplete(allSuccess: Boolean) {
                    // Result already shown in onLog
                }
            })
        }
    }

    private fun setupLogScroll() {
        // Make the log area independently scrollable inside the page scroll container.
        // Without this, the parent ScrollView/NestedScrollView may steal touch events.
        var lastRawY = 0f
        binding.logScroll.setOnTouchListener { v, event ->
            val parent = v.parent
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawY = event.rawY
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - lastRawY
                    lastRawY = event.rawY

                    // Finger moves down => user tries to scroll up (towards earlier logs).
                    // Finger moves up   => user tries to scroll down (towards later logs).
                    val canScrollUp = v.canScrollVertically(-1)
                    val canScrollDown = v.canScrollVertically(1)
                    val shouldChildHandle = if (dy > 0) canScrollUp else canScrollDown
                    parent?.requestDisallowInterceptTouchEvent(shouldChildHandle)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    // ==================== Log ====================

    private fun appendLog(message: String) {
        // Also capture in the local persistent log.
        AppLog.i("Debug", message)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "$time  $message\n"
        runOnUiThread {
            // Only auto-scroll when user is already near the bottom; otherwise allow manual scrolling.
            val shouldAutoScroll = isLogNearBottom()
            logBuilder.append(line)
            binding.textLog.text = logBuilder.toString()
            if (shouldAutoScroll) {
                binding.logScroll.post { binding.logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    private fun isLogNearBottom(thresholdDp: Int = 120): Boolean {
        val scroll = binding.logScroll
        val child = scroll.getChildAt(0) ?: return true
        val thresholdPx = (thresholdDp * resources.displayMetrics.density).toInt()
        val viewport = scroll.height - scroll.paddingTop - scroll.paddingBottom
        val diff = child.bottom - (viewport + scroll.scrollY)
        return diff <= thresholdPx
    }
}
