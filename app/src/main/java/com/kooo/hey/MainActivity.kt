package com.kooo.hey

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.kooo.hey.audio.AmpManager
import com.kooo.hey.audio.AudioEngine
import com.kooo.hey.audio.AudioFocusHelper
import com.kooo.hey.audio.CarVolumeHelper
import com.kooo.hey.audio.EcarxWorkMode
import com.kooo.hey.config.AppConfig
import com.kooo.hey.databinding.ActivityMainBinding
import com.kooo.hey.ui.CountdownRingView
import com.kooo.hey.ui.MusicPlayerPanel
import com.kooo.hey.log.AppLog
import com.kooo.hey.ui.GuideOverlay
import com.kooo.hey.ui.GainSliderView
import android.graphics.Color
import android.provider.Settings
import android.view.KeyEvent
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.kooo.hey.audio.HotkeyManager
import com.kooo.hey.audio.KeyNameHelper
import com.kooo.hey.audio.MusicPlaybackController
import com.kooo.hey.audio.MusicPlayerManager
import com.kooo.hey.ui.FolderPickerDialog
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Hey!Main"
        private const val CHARGE_DELAY_MS = 400L
        private const val CHARGE_DURATION_MS = 2000L
        private const val COUNTDOWN_DURATION_MS = 60_000L
        private const val CHARGE_FRAME_MS = 16L

        @Volatile var instance: MainActivity? = null
        @Volatile var isInForeground = false
    }

    // Theme-aware colors (loaded from resources in onCreate)
    private var colorBtnIdleFill = 0
    private var colorBtnIdleStroke = 0
    private var colorBtnActiveFill = 0
    private var colorBtnActiveStroke = 0
    private var colorBtnContinuousFill = 0
    private var colorBtnContinuousStroke = 0
    private var colorPulseRed = 0
    private var colorPulseGreen = 0
    private var colorPulseYellow = 0
    private var colorBreathMin = 0
    private var colorBreathMax = 0

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: AppConfig
    private lateinit var audioEngine: AudioEngine
    private lateinit var audioFocusHelper: AudioFocusHelper
    private lateinit var carVolumeHelper: CarVolumeHelper
    private lateinit var musicPlayerPanel: MusicPlayerPanel
    private lateinit var hotkeyManager: HotkeyManager
    private var activeKeyLearnDialog: AlertDialog? = null
    private var onKeyLearnedCallback: ((keyCode: Int) -> Unit)? = null

    // ==================== State Machine ====================
    private enum class TalkState { IDLE, CHARGING, TALKING_COUNTDOWN, TALKING_CONTINUOUS, STOPPING }
    private var talkState = TalkState.IDLE

    private val handler = Handler(Looper.getMainLooper())
    private var touchDownTime = 0L
    private var chargeStartTime = 0L
    private var chargeRingVisible = false
    private var countdownTimer: CountDownTimer? = null

    // Programmatic button background (for smooth color animation)
    private lateinit var buttonDrawable: GradientDrawable
    private var btnColorAnimator: ValueAnimator? = null
    private var idleBreathAnimator: ValueAnimator? = null

    // Pulse ring color (changes with mode)
    private var currentPulseColor = 0

    // ==================== Charge Animation ====================

    private val chargeDelayRunnable = Runnable {
        if (talkState != TalkState.CHARGING) return@Runnable
        chargeRingVisible = true
        chargeStartTime = System.currentTimeMillis()
        binding.countdownRing.mode = CountdownRingView.Mode.CHARGING
        binding.countdownRing.sweepAngle = 0f
        binding.statusText.text = getString(R.string.status_charging)
        handler.post(chargeRunnable)
    }

    private val chargeRunnable = object : Runnable {
        override fun run() {
            if (talkState != TalkState.CHARGING || !chargeRingVisible) return
            val elapsed = System.currentTimeMillis() - chargeStartTime
            val chargeWindow = CHARGE_DURATION_MS - CHARGE_DELAY_MS
            val linear = (elapsed.toFloat() / chargeWindow).coerceIn(0f, 1f)
            val eased = linear * linear * linear
            binding.countdownRing.sweepAngle = eased * 360f

            if (linear >= 1f) {
                binding.countdownRing.mode = CountdownRingView.Mode.CONTINUOUS
                binding.statusText.text = getString(R.string.status_charged)
                binding.statusText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.green_ok))
            } else {
                handler.postDelayed(this, CHARGE_FRAME_MS)
            }
        }
    }

    private fun handleEngineUnexpectedStop(reason: String) {
        val wasTalking = talkState == TalkState.TALKING_COUNTDOWN || talkState == TalkState.TALKING_CONTINUOUS
        if (!wasTalking) return

        AppLog.w(TAG, "Audio engine stopped unexpectedly: $reason")
        countdownTimer?.cancel()
        countdownTimer = null
        audioFocusHelper.releaseFocus()
        EcarxWorkMode.restore(this)
        showError("音频流异常中断: $reason")
        animateToIdle()
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) AppLog.i(TAG, "Mic permission granted")
        else Toast.makeText(this, "需要麦克风权限才能使用喊话功能", Toast.LENGTH_LONG).show()
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            AppLog.i(TAG, "Storage permission granted, opening music panel")
            musicPlayerPanel.toggle()
        } else {
            AppLog.w(TAG, "Storage permission denied")
            if (shouldShowRequestPermissionRationale(getStoragePermission())) {
                Toast.makeText(this, R.string.permission_storage_denied, Toast.LENGTH_LONG).show()
            } else {
                showStoragePermissionSettingsDialog()
            }
        }
    }

    // ==================== Lifecycle ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize app-wide logging (buffer + crash handler + session rotation)
        AppLog.init(this)

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

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Only pad left/right/bottom; top has no padding so background extends under status bar
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }

        instance = this
        config = AppConfig(this)
        binding.audioGlow.setSpawnThreshold(config.rippleThreshold)
        audioEngine = AudioEngine(this)
        audioFocusHelper = AudioFocusHelper(this)
        audioFocusHelper.requestFocus()
        carVolumeHelper = CarVolumeHelper(this)

        // Load theme-aware colors
        colorBtnIdleFill = ContextCompat.getColor(this, R.color.btn_idle_fill)
        colorBtnIdleStroke = ContextCompat.getColor(this, R.color.btn_idle_stroke)
        colorBtnActiveFill = ContextCompat.getColor(this, R.color.btn_active_fill)
        colorBtnActiveStroke = ContextCompat.getColor(this, R.color.btn_active_stroke)
        colorBtnContinuousFill = ContextCompat.getColor(this, R.color.btn_continuous_fill)
        colorBtnContinuousStroke = ContextCompat.getColor(this, R.color.btn_continuous_stroke)
        colorPulseRed = ContextCompat.getColor(this, R.color.pulse_red)
        colorPulseGreen = ContextCompat.getColor(this, R.color.pulse_green)
        colorPulseYellow = ContextCompat.getColor(this, R.color.pulse_yellow)
        colorBreathMin = ContextCompat.getColor(this, R.color.breath_stroke_min)
        colorBreathMax = ContextCompat.getColor(this, R.color.breath_stroke_max)
        currentFill = colorBtnIdleFill
        currentStroke = colorBtnIdleStroke
        currentPulseColor = colorPulseRed

        // Create programmatic button background
        buttonDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorBtnIdleFill)
            setStroke(4, colorBtnIdleStroke)
        }
        binding.talkButton.background = buttonDrawable

        // Audio-reactive glow (driven by mic input level)
        audioEngine.onLevel = { inputLevel, _ ->
            runOnUiThread { updateAudioGlow(inputLevel) }
        }
        audioEngine.onUnexpectedStop = { reason ->
            runOnUiThread { handleEngineUnexpectedStop(reason) }
        }

        setupGainSlider()
        setupMusicVolumeSlider()
        setupTalkButton()
        setupSettingsButton()
        setupGuideButton()
        setupMusicPlayerPanel()
        setupQuickButtons()
        hotkeyManager = HotkeyManager(config) { index ->
            runOnUiThread { handleQuickButtonClick(index) }
        }
        LogcatTriggerService.syncWithConfig(this)
        if (config.enableFloatingWindow && Settings.canDrawOverlays(this)) startFloatingWindow()
        startIdleBreathing()

        // Request microphone permission on startup
        requestMicrophonePermissionIfNeeded()

        // Show guide on first launch
        GuideOverlay.showIfFirstTime(binding.main)

        handleLaunchIntent(intent, fromNewIntent = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent, fromNewIntent = true)
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        // Sync gain and volume sliders (may have changed in DebugActivity)
        if (::config.isInitialized) {
            binding.gainSlider.setGainRange(config.minGainLimit, config.maxGainLimit)
            binding.gainSlider.setGain(config.volumeGain)
            binding.musicVolumeSlider.setGain(config.musicVolume)
            updateQuickButtonsUi()
        }
        AmpManager.acquire(this, AmpManager.SESSION_FOREGROUND)
        if (::audioEngine.isInitialized) audioEngine.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        isInForeground = false
        if (::hotkeyManager.isInitialized) hotkeyManager.reset()
        AmpManager.release(this, AmpManager.SESSION_FOREGROUND)
        if (::audioEngine.isInitialized) audioEngine.onAppBackground()
        AppLog.saveToPersistentLog(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        AppLog.saveToPersistentLog(this)
        if (::hotkeyManager.isInitialized) hotkeyManager.reset()
        activeKeyLearnDialog?.dismiss()
        activeKeyLearnDialog = null
        onKeyLearnedCallback = null
        handler.removeCallbacksAndMessages(null)
        countdownTimer?.cancel()
        btnColorAnimator?.cancel()
        idleBreathAnimator?.cancel()
        // Always release audio resources to guarantee mic is freed,
        // regardless of talkState (covers STOPPING, TALKING_*, etc.).
        // Guard with isInitialized in case Activity creation did not complete.
        if (::audioEngine.isInitialized) {
            audioEngine.onUnexpectedStop = null
            audioEngine.destroy()
        }
        if (::audioFocusHelper.isInitialized) audioFocusHelper.releaseFocus()
        if (::musicPlayerPanel.isInitialized) musicPlayerPanel.release()
        EcarxWorkMode.restore(this)
    }

    // ==================== Talk Button Touch Logic ====================

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTalkButton() {
        binding.talkButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> onTouchDown()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onTouchUp()
            }
            true
        }
    }

    private fun onTouchDown() {
        when (talkState) {
            TalkState.IDLE -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    return
                }
                stopIdleBreathing()
                talkState = TalkState.CHARGING
                touchDownTime = System.currentTimeMillis()
                chargeRingVisible = false
                binding.talkButton.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start()
                handler.postDelayed(chargeDelayRunnable, CHARGE_DELAY_MS)
            }
            TalkState.TALKING_COUNTDOWN, TalkState.TALKING_CONTINUOUS -> {
                stopTalking()
            }
            else -> {}
        }
    }

    private fun onTouchUp() {
        if (talkState != TalkState.CHARGING) {
            binding.talkButton.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            return
        }

        handler.removeCallbacks(chargeDelayRunnable)
        handler.removeCallbacks(chargeRunnable)
        binding.talkButton.animate().scaleX(1f).scaleY(1f).setDuration(150).start()

        val totalHeld = System.currentTimeMillis() - touchDownTime

        if (totalHeld >= CHARGE_DURATION_MS && chargeRingVisible) {
            startTalking(continuous = true)
        } else {
            binding.countdownRing.mode = CountdownRingView.Mode.IDLE
            startTalking(continuous = false)
        }
    }

    // ==================== Start / Stop Talking ====================

    private fun startTalking(continuous: Boolean) {
        talkState = if (continuous) TalkState.TALKING_CONTINUOUS else TalkState.TALKING_COUNTDOWN
        FloatingWindowService.instance?.updateTalkState(true)
        animateToActive(continuous)

        lifecycleScope.launch {
            try {
                val focusGranted = audioFocusHelper.requestFocus()
                if (!focusGranted) {
                    AppLog.w(TAG, "Audio focus denied, abort startTalking")
                    showError("无法获取音频焦点")
                    animateToIdle()
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    // Apply Ecarx hardware DSP mode (noise clean / echo cancel)
                    if (config.enableHwNoiseClean || config.enableHwEchoCancel) {
                        EcarxWorkMode.apply(this@MainActivity, config.enableHwNoiseClean, config.enableHwEchoCancel)
                    }
                }

                // 在 IO 挂起期间用户可能已经停止，检查状态避免启动后无法释放麦克风
                if (talkState != TalkState.TALKING_COUNTDOWN && talkState != TalkState.TALKING_CONTINUOUS) {
                    AppLog.w(TAG, "Talk cancelled during setup, state=$talkState")
                    return@launch
                }

                audioEngine.start(
                    audioUsage = config.audioUsage,
                    outputDevice = findConfiguredOutputDevice(),
                    inputDevice = findConfiguredInputDevice(),
                    audioSource = config.audioSource,
                    volumeGain = config.volumeGain,
                    enableNoiseGate = config.enableNoiseGate,
                    noiseGateStrength = config.noiseGateStrength,
                    enableAgc = config.enableAgc,
                    enableReverb = config.enableReverb,
                    reverbMix = config.reverbMix,
                    enableHighPass = config.enableHighPass,
                    highPassCutoff = config.highPassCutoff,
                    enableLowPass = config.enableLowPass,
                    lowPassCutoff = config.lowPassCutoff,
                    enableFreqShift = config.enableFreqShift,
                    freqShiftHz = config.freqShiftHz,
                    dspMode = config.dspMode,
                )

                // AudioEngine.start() may silently fail (e.g. permission denied,
                // init error) without throwing. Detect and recover.
                if (!audioEngine.running) {
                    AppLog.w(TAG, "AudioEngine failed to start (silent failure)")
                    audioFocusHelper.releaseFocus()
                    EcarxWorkMode.restore(this@MainActivity)
                    showError("音频引擎启动失败")
                    animateToIdle()
                    return@launch
                }

                if (!continuous) startCountdownTimer()
                AppLog.i(TAG, "Talking started (continuous=$continuous)")

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "Failed to start talking", e)
                showError(e.message ?: "未知错误")
                animateToIdle()
            }
        }
    }

    private fun stopTalking() {
        val wasTalking = talkState == TalkState.TALKING_COUNTDOWN || talkState == TalkState.TALKING_CONTINUOUS
        talkState = TalkState.STOPPING
        countdownTimer?.cancel()
        countdownTimer = null

        // Release audio resources synchronously to guarantee mic is freed
        // even if the Activity is destroyed before a coroutine could run.
        if (wasTalking) {
            audioEngine.stop()
            audioFocusHelper.releaseFocus()
            EcarxWorkMode.restore(this)
        }

        lifecycleScope.launch {
            animateToIdle()
            AppLog.i(TAG, "Talking stopped")
        }
    }

    // ==================== Countdown Timer ====================

    private fun startCountdownTimer() {
        countdownTimer?.cancel()
        binding.countdownRing.mode = CountdownRingView.Mode.COUNTDOWN
        binding.countdownRing.sweepAngle = 360f
        currentPulseColor = colorPulseRed

        countdownTimer = object : CountDownTimer(COUNTDOWN_DURATION_MS, 50) {
            override fun onTick(millisUntilFinished: Long) {
                if (talkState != TalkState.TALKING_COUNTDOWN) return
                val progress = millisUntilFinished.toFloat() / COUNTDOWN_DURATION_MS
                binding.countdownRing.sweepAngle = progress * 360f

                val secs = (millisUntilFinished / 1000).toInt()
                binding.statusText.text = getString(R.string.status_countdown, secs)

                // Pulse color follows countdown: red → yellow
                currentPulseColor = lerpColor(colorPulseYellow, colorPulseRed, progress)

                if (secs <= 10 && binding.countdownRing.mode == CountdownRingView.Mode.COUNTDOWN) {
                    binding.countdownRing.startUrgentPulse()
                }
            }

            override fun onFinish() {
                if (talkState == TalkState.TALKING_COUNTDOWN) stopTalking()
            }
        }.start()
    }

    // ==================== Animation: Idle → Active ====================

    private fun animateToActive(continuous: Boolean) {
        btnColorAnimator?.cancel()

        // 1. Button color smooth transition
        val targetFill = if (continuous) colorBtnContinuousFill else colorBtnActiveFill
        val targetStroke = if (continuous) colorBtnContinuousStroke else colorBtnActiveStroke
        animateButtonColor(targetFill, targetStroke, 400)

        // 2. Mic icon bounce with overshoot
        binding.micIcon.setColorFilter(
            ContextCompat.getColor(this, R.color.mic_icon_active), PorterDuff.Mode.SRC_IN)
        binding.micIcon.scaleX = 0.5f
        binding.micIcon.scaleY = 0.5f
        binding.micIcon.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(500)
            .setInterpolator(OvershootInterpolator(2.5f))
            .start()

        // 3. Status text fade
        binding.errorText.visibility = View.GONE
        binding.statusText.alpha = 0f
        if (continuous) {
            binding.statusText.text = getString(R.string.status_continuous)
            binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.green_ok))
            currentPulseColor = colorPulseGreen
        } else {
            binding.statusText.text = getString(R.string.status_countdown, 60)
            binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.on_surface))
            currentPulseColor = colorPulseRed
        }
        binding.statusText.animate().alpha(1f).setDuration(300).setStartDelay(200).start()
    }

    // ==================== Animation: Active → Idle (wind down) ====================

    private fun animateToIdle() {
        runOnUiThread {
            talkState = TalkState.IDLE
            FloatingWindowService.instance?.updateTalkState(false)
            btnColorAnimator?.cancel()

            // 1. Button color transition back (300ms)
            animateButtonColor(colorBtnIdleFill, colorBtnIdleStroke, 300)

            // 2. Button squeeze-and-bounce back
            binding.talkButton.animate()
                .scaleX(0.92f).scaleY(0.92f)
                .setDuration(120)
                .withEndAction {
                    binding.talkButton.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(300)
                        .setInterpolator(OvershootInterpolator(1.5f))
                        .start()
                }
                .start()

            // 3. Mic icon color fade back
            binding.micIcon.setColorFilter(
                ContextCompat.getColor(this, R.color.mic_icon_idle), PorterDuff.Mode.SRC_IN)

            // 4. Status text fade out then change
            binding.statusText.animate().alpha(0f).setDuration(150).withEndAction {
                binding.statusText.text = getString(R.string.status_idle)
                binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.on_surface_dim))
                binding.statusText.animate().alpha(1f).setDuration(250).start()
            }.start()

            binding.errorText.visibility = View.GONE
            binding.countdownRing.mode = CountdownRingView.Mode.IDLE
            binding.countdownRing.stopAnimations()

            // 5. Glow fade out
            binding.audioGlow.reset()

            // 6. Restart idle breathing after wind-down
            handler.postDelayed({ startIdleBreathing() }, 600)
        }
    }

    // ==================== Button Color Transition ====================

    private var currentFill = 0
    private var currentStroke = 0

    private fun animateButtonColor(targetFill: Int, targetStroke: Int, durationMs: Long) {
        btnColorAnimator?.cancel()
        val startFill = currentFill
        val startStroke = currentStroke
        val evaluator = ArgbEvaluator()

        btnColorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            addUpdateListener { anim ->
                val t = anim.animatedFraction
                val fill = evaluator.evaluate(t, startFill, targetFill) as Int
                val stroke = evaluator.evaluate(t, startStroke, targetStroke) as Int
                buttonDrawable.setColor(fill)
                buttonDrawable.setStroke(4, stroke)
                currentFill = fill
                currentStroke = stroke
            }
            start()
        }
    }

    // ==================== Idle Breathing ====================

    private fun startIdleBreathing() {
        if (talkState != TalkState.IDLE) return
        idleBreathAnimator?.cancel()
        idleBreathAnimator = ValueAnimator.ofArgb(colorBreathMin, colorBreathMax).apply {
            duration = 2500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { anim ->
                val strokeColor = anim.animatedValue as Int
                buttonDrawable.setStroke(4, strokeColor)
            }
            start()
        }
    }

    private fun stopIdleBreathing() {
        idleBreathAnimator?.cancel()
        idleBreathAnimator = null
    }

    // ==================== Audio-Reactive Glow ====================

    private fun updateAudioGlow(level: Float) {
        if (talkState != TalkState.TALKING_COUNTDOWN && talkState != TalkState.TALKING_CONTINUOUS) return

        binding.audioGlow.setGlowColor(currentPulseColor)
        binding.audioGlow.setLevel(level)
    }

    // ==================== Helpers ====================

    private fun showError(message: String) {
        runOnUiThread {
            binding.errorText.text = message
            binding.errorText.visibility = View.VISIBLE
        }
    }

    private fun findConfiguredOutputDevice(): AudioDeviceInfo? {
        val id = config.outputDeviceId
        if (id > 0) return audioEngine.findOutputDeviceById(id)
        val addr = config.outputDeviceAddress
        if (addr.isBlank()) return null
        return audioEngine.findOutputDevice(addr)
    }

    private fun findConfiguredInputDevice(): AudioDeviceInfo? {
        val id = config.inputDeviceId
        if (id <= 0) return null
        return audioEngine.findInputDevice(id)
    }

    private fun lerpColor(c1: Int, c2: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val a = lerp((c1 shr 24) and 0xFF, (c2 shr 24) and 0xFF, tt)
        val r = lerp((c1 shr 16) and 0xFF, (c2 shr 16) and 0xFF, tt)
        val g = lerp((c1 shr 8) and 0xFF, (c2 shr 8) and 0xFF, tt)
        val b = lerp(c1 and 0xFF, c2 and 0xFF, tt)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lerp(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt().coerceIn(0, 255)

    // ==================== External Trigger: Toggle Talk ====================

    /** Handle incoming launch intents from floating window / hard key / external command. */
    private fun handleLaunchIntent(intent: Intent?, fromNewIntent: Boolean) {
        if (intent == null) return
        val action = intent.action
        when {
            action == FloatingWindowService.ACTION_START_TALK ||
            action == HeyCommandReceiver.ACTION_START_TALK -> {
                if (fromNewIntent) performDefaultAction()
                else handler.post { performDefaultAction() }
            }
            action == FloatingWindowService.ACTION_STOP_TALK ||
            action == HeyCommandReceiver.ACTION_STOP_TALK -> {
                if (fromNewIntent) stopAndMoveBack()
                else handler.post { stopAndMoveBack() }
            }
            action == FloatingWindowService.ACTION_TOGGLE_TALK ||
            action == HeyCommandReceiver.ACTION_TOGGLE_TALK -> {
                if (fromNewIntent) toggleDefaultAction()
                else handler.post { toggleDefaultAction() }
            }
            action == HeyCommandReceiver.ACTION_PLAY_QUICK_1 -> {
                if (fromNewIntent) handleQuickButtonClick(0)
                else handler.post { handleQuickButtonClick(0) }
            }
            action == HeyCommandReceiver.ACTION_PLAY_QUICK_2 -> {
                if (fromNewIntent) handleQuickButtonClick(1)
                else handler.post { handleQuickButtonClick(1) }
            }
            action == HeyCommandReceiver.ACTION_PLAY_QUICK_3 -> {
                if (fromNewIntent) handleQuickButtonClick(2)
                else handler.post { handleQuickButtonClick(2) }
            }
            action == HeyCommandReceiver.ACTION_PLAY_QUICK_4 -> {
                if (fromNewIntent) handleQuickButtonClick(3)
                else handler.post { handleQuickButtonClick(3) }
            }
            isLauncherIntent(intent) -> {
                if (config.enableAutoTalkOnForeground) {
                    // "前台唤醒自动喊话" is on → full action (including auto-play)
                    if (fromNewIntent) performDefaultAction()
                    else handler.post { performDefaultAction() }
                } else if (!fromNewIntent) {
                    // Cold start from launcher icon → show configured panel (no auto-start)
                    handler.post { showDefaultPanel() }
                }
                // Hot restart (fromNewIntent && !enableAutoTalkOnForeground): keep current state
            }
        }
    }

    private fun stopAndMoveBack() {
        if (talkState == TalkState.TALKING_COUNTDOWN || talkState == TalkState.TALKING_CONTINUOUS) {
            stopTalking()
        }
        if (::musicPlayerPanel.isInitialized && musicPlayerPanel.isVisible) musicPlayerPanel.hide()
        moveTaskToBack(true)
    }

    private fun isLauncherIntent(intent: Intent): Boolean {
        return intent.action == Intent.ACTION_MAIN &&
            intent.categories?.contains(Intent.CATEGORY_LAUNCHER) == true
    }

    /** Show the configured default panel without auto-starting any action. */
    private fun showDefaultPanel() {
        if (config.launchAction == AppConfig.LAUNCH_ACTION_MUSIC_PANEL) {
            val permission = getStoragePermission()
            if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                musicPlayerPanel.show()
            }
        }
    }

    /** Start countdown talk if currently idle. */
    private fun startTalkIfIdle() {
        if (talkState != TalkState.IDLE) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        stopIdleBreathing()
        startTalking(continuous = false)
    }

    /** Whether talk is currently active (for external callers like FloatingWindowService). */
    val isTalking: Boolean
        get() = talkState == TalkState.TALKING_COUNTDOWN || talkState == TalkState.TALKING_CONTINUOUS

    /** Stop talk from external caller without bringing activity to foreground. */
    fun requestStopTalking() {
        runOnUiThread {
            stopTalking()
            moveTaskToBack(true)
        }
    }

    /** Toggle default action on/off from external trigger (hard key, floating button). */
    private fun toggleDefaultAction() {
        val isTalkActive = talkState == TalkState.TALKING_COUNTDOWN || talkState == TalkState.TALKING_CONTINUOUS
        val isPanelActive = ::musicPlayerPanel.isInitialized && musicPlayerPanel.isVisible

        if (isTalkActive || isPanelActive) {
            if (isTalkActive) stopTalking()
            if (::musicPlayerPanel.isInitialized && musicPlayerPanel.isVisible) musicPlayerPanel.hide()
            moveTaskToBack(true)
        } else if (talkState == TalkState.IDLE) {
            performDefaultAction()
        } else {
            AppLog.d(TAG, "Toggle ignored in state: $talkState")
        }
    }

    /** Execute the configured default launch action. */
    private fun performDefaultAction() {
        if (config.launchAction == AppConfig.LAUNCH_ACTION_MUSIC_PANEL) {
            val permission = getStoragePermission()
            if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                if (config.launchAutoPlay) musicPlayerPanel.playTrackAt(config.launchMusicIndex)
                else musicPlayerPanel.show()
            } else {
                requestStoragePermissionOrOpenMusic()
            }
        } else {
            startTalkIfIdle()
        }
    }

    // Hard key/logcat monitoring is handled by foreground services.
    // They send ACTION_START_TALK to this Activity, handled in onCreate / onNewIntent.

    // ==================== Floating Window ====================

    private fun startFloatingWindow() {
        startForegroundService(Intent(this, FloatingWindowService::class.java))
    }

    // ==================== Gain & Volume Sliders ====================

    private fun setupGainSlider() {
        // Left slider: Talk Mic Gain (1.0x ~ 30.0x)
        binding.gainSlider.label = "🎤"
        binding.gainSlider.valueMode = GainSliderView.ValueMode.MULTIPLIER
        binding.gainSlider.linearMapping = true
        binding.gainSlider.setGainRange(config.minGainLimit, config.maxGainLimit)
        binding.gainSlider.setGain(config.volumeGain)

        binding.gainSlider.onGainChanged = { newGain ->
            config.volumeGain = newGain
            if (talkState == TalkState.TALKING_COUNTDOWN || talkState == TalkState.TALKING_CONTINUOUS) {
                audioEngine.liveGain = newGain
            }
        }
    }

    private fun setupMusicVolumeSlider() {
        // Right slider: Music & Quick Sound Volume (0% ~ 100%)
        binding.musicVolumeSlider.label = "🎵"
        binding.musicVolumeSlider.valueMode = GainSliderView.ValueMode.PERCENT
        binding.musicVolumeSlider.linearMapping = true
        binding.musicVolumeSlider.setGradientColors(
            intArrayOf(
                Color.rgb(33, 150, 243),  // Blue
                Color.rgb(0, 188, 212),   // Cyan
                Color.rgb(76, 175, 80)    // Green
            )
        )
        binding.musicVolumeSlider.setGainRange(0.0f, 1.0f)
        binding.musicVolumeSlider.setGain(config.musicVolume)

        binding.musicVolumeSlider.onGainChanged = { newVolume ->
            val vol = newVolume.coerceIn(0f, 1f)
            config.musicVolume = vol
            MusicPlaybackController.get(this).setVolume(vol)
            musicPlayerPanel.updatePlaybackVolume(vol)
        }
    }

    // ==================== Settings ====================

    private fun setupSettingsButton() {
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }
    }

    // ==================== Guide ====================

    private fun setupGuideButton() {
        binding.guideButton.setOnClickListener {
            GuideOverlay.show(binding.main)
        }
    }

    // ==================== Music Player Panel ====================

    private fun setupMusicPlayerPanel() {
        musicPlayerPanel = MusicPlayerPanel(
            panelRoot = binding.musicPanel.root,
            toggleButton = binding.musicButton,
            onShowRequest = { showCenterPanel(CenterMode.MUSIC) },
            onHideRequest = { restoreCenterCircle() }
        )
        // Back button closes music panel
        binding.panelBackButton.setOnClickListener {
            if (musicPlayerPanel.isVisible) musicPlayerPanel.hide()
        }
        // Override click listener to check storage permission before opening
        binding.musicButton.setOnClickListener {
            if (musicPlayerPanel.isVisible) {
                musicPlayerPanel.toggle()
            } else {
                requestStoragePermissionOrOpenMusic()
            }
        }
    }

    private fun getStoragePermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun requestStoragePermissionOrOpenMusic() {
        val permission = getStoragePermission()
        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                musicPlayerPanel.toggle()
            }
            shouldShowRequestPermissionRationale(permission) -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.permission_storage_title)
                    .setMessage(R.string.permission_storage_message)
                    .setPositiveButton(R.string.permission_dialog_ok) { _, _ ->
                        storagePermissionLauncher.launch(permission)
                    }
                    .setNegativeButton(R.string.permission_dialog_cancel, null)
                    .show()
            }
            else -> {
                storagePermissionLauncher.launch(permission)
            }
        }
    }

    private fun showStoragePermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_storage_title)
            .setMessage(R.string.permission_storage_settings)
            .setPositiveButton(R.string.permission_dialog_settings) { _, _ ->
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.fromParts("package", packageName, null)
                startActivity(intent)
            }
            .setNegativeButton(R.string.permission_dialog_cancel, null)
            .show()
    }

    // ==================== Quick Sound Buttons ====================

    private fun setupQuickButtons() {
        val buttons = listOf(
            binding.quickButton1,
            binding.quickButton2,
            binding.quickButton3,
            binding.quickButton4
        )

        buttons.forEachIndexed { index, button ->
            button.setOnClickListener {
                handleQuickButtonClick(index)
            }
            button.setOnLongClickListener {
                handleQuickButtonLongClick(index)
                true
            }
        }

        val musicManager = MusicPlaybackController.get(this)
        musicManager.addPlayStateListener {
            runOnUiThread { updateQuickButtonsUi() }
        }
        musicManager.addTrackChangedListener { _, _ ->
            runOnUiThread { updateQuickButtonsUi() }
        }

        updateQuickButtonsUi()
    }

    fun updateQuickButtonsUi() {
        val musicManager = MusicPlaybackController.get(this)
        val isPlaying = musicManager.isPlaying
        val currentPath = musicManager.currentTrack?.path

        val buttonPairs = listOf(
            Pair(binding.quickButton1, binding.quickButton1Label),
            Pair(binding.quickButton2, binding.quickButton2Label),
            Pair(binding.quickButton3, binding.quickButton3Label),
            Pair(binding.quickButton4, binding.quickButton4Label)
        )

        buttonPairs.forEachIndexed { index, (button, label) ->
            val name = config.getQuickSoundName(index)
            val path = config.getQuickSoundPath(index)
            label.text = name

            val isThisPlaying = isPlaying && path.isNotEmpty() && path == currentPath
            if (isThisPlaying) {
                button.setBackgroundResource(R.drawable.bg_func_button_active)
                button.setImageResource(R.drawable.ic_pause)
                button.setColorFilter(ContextCompat.getColor(this, R.color.on_primary))
                label.setTextColor(ContextCompat.getColor(this, R.color.red_primary))
            } else if (path.isNotEmpty()) {
                button.setBackgroundResource(R.drawable.bg_func_button_ripple)
                button.setImageResource(R.drawable.ic_play)
                button.setColorFilter(ContextCompat.getColor(this, R.color.ve_icon_tint))
                label.setTextColor(ContextCompat.getColor(this, R.color.on_surface_muted))
            } else {
                button.setBackgroundResource(R.drawable.bg_func_button_ripple)
                button.setImageResource(R.drawable.ic_add)
                button.setColorFilter(ContextCompat.getColor(this, R.color.ve_icon_tint))
                label.setTextColor(ContextCompat.getColor(this, R.color.on_surface_muted))
            }
        }
    }

    private fun handleQuickButtonClick(index: Int) {
        val permission = getStoragePermission()
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            requestStoragePermissionOrOpenMusic()
            return
        }

        val path = config.getQuickSoundPath(index)
        if (path.isEmpty()) {
            showQuickSoundSetupDialog(index)
            return
        }

        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, R.string.quick_sound_file_not_found, Toast.LENGTH_SHORT).show()
            showQuickSoundSetupDialog(index)
            return
        }

        val musicManager = MusicPlaybackController.get(this)
        val isThisPlaying = musicManager.isPlaying && musicManager.currentTrack?.path == path

        if (isThisPlaying) {
            musicManager.stop()
        } else {
            val name = config.getQuickSoundName(index)
            musicManager.playPath(path, name)
        }
    }

    private fun handleQuickButtonLongClick(index: Int) {
        val permission = getStoragePermission()
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            requestStoragePermissionOrOpenMusic()
            return
        }
        showQuickSoundSetupDialog(index)
    }

    private fun showQuickSoundSetupDialog(index: Int) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_quick_sound_setup, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val titleView = dialogView.findViewById<TextView>(R.id.dialogTitle)
        val editName = dialogView.findViewById<EditText>(R.id.editSoundName)
        val textFile = dialogView.findViewById<TextView>(R.id.selectedFileText)
        val btnSelect = dialogView.findViewById<TextView>(R.id.btnSelectFile)
        val textKeyBinding = dialogView.findViewById<TextView>(R.id.textKeyBinding)
        val btnUnbindKey = dialogView.findViewById<TextView>(R.id.btnUnbindKey)
        val btnBindKey = dialogView.findViewById<TextView>(R.id.btnBindKey)
        val rgSpeakerDest = dialogView.findViewById<RadioGroup>(R.id.rgSpeakerDest)
        val rbDestExternal = dialogView.findViewById<RadioButton>(R.id.rbDestExternal)
        val rbDestCabin = dialogView.findViewById<RadioButton>(R.id.rbDestCabin)
        val btnClear = dialogView.findViewById<TextView>(R.id.btnClear)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancel)
        val btnSave = dialogView.findViewById<TextView>(R.id.btnSave)

        if (config.musicSpeakerDestination == AppConfig.MUSIC_SPEAKER_IN_CABIN) {
            rbDestCabin.isChecked = true
        } else {
            rbDestExternal.isChecked = true
        }

        titleView.text = getString(R.string.quick_sound_dialog_title) + " " + (index + 1)
        val currentName = config.getQuickSoundName(index)
        editName.setText(currentName)
        editName.setSelection(currentName.length)

        var selectedPath = config.getQuickSoundPath(index)
        var selectedKeyCode = config.getQuickSoundKeyCode(index)
        var selectedGesture = config.getQuickSoundGesture(index)

        fun updateFileDisplay() {
            if (selectedPath.isNotEmpty()) {
                val f = File(selectedPath)
                textFile.text = f.name
                textFile.setTextColor(ContextCompat.getColor(this, R.color.on_surface))
            } else {
                textFile.text = getString(R.string.quick_sound_no_file)
                textFile.setTextColor(ContextCompat.getColor(this, R.color.on_surface_muted))
            }
        }
        updateFileDisplay()

        fun updateKeyDisplay() {
            if (selectedKeyCode > 0) {
                textKeyBinding.text = KeyNameHelper.getBindingDescription(this, selectedKeyCode, selectedGesture)
                textKeyBinding.setTextColor(ContextCompat.getColor(this, R.color.on_surface))
                btnUnbindKey.visibility = View.VISIBLE
            } else {
                textKeyBinding.text = getString(R.string.quick_sound_key_unbound)
                textKeyBinding.setTextColor(ContextCompat.getColor(this, R.color.on_surface_muted))
                btnUnbindKey.visibility = View.GONE
            }
        }
        updateKeyDisplay()

        btnUnbindKey.setOnClickListener {
            selectedKeyCode = 0
            selectedGesture = KeyNameHelper.GESTURE_SINGLE
            updateKeyDisplay()
        }

        btnBindKey.setOnClickListener {
            showLearnKeyDialog(selectedKeyCode, selectedGesture) { newCode, newGesture ->
                selectedKeyCode = newCode
                selectedGesture = newGesture
                updateKeyDisplay()
            }
        }

        btnSelect.setOnClickListener {
            showTrackSelectionDialog { chosenTrack ->
                selectedPath = chosenTrack.path
                updateFileDisplay()
                val curText = editName.text.toString().trim()
                if (curText.isEmpty() || curText.startsWith("快捷") || curText.startsWith("Quick")) {
                    editName.setText(chosenTrack.name)
                    editName.setSelection(chosenTrack.name.length)
                }
            }
        }

        btnClear.setOnClickListener {
            config.clearQuickSound(index)
            updateQuickButtonsUi()
            dialog.dismiss()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val enteredName = editName.text.toString().trim()
            val finalName = if (enteredName.isNotEmpty()) enteredName else config.getQuickSoundName(index)
            config.setQuickSoundName(index, finalName)
            config.setQuickSoundPath(index, selectedPath)
            config.setQuickSoundKeyCode(index, selectedKeyCode)
            config.setQuickSoundGesture(index, selectedGesture)
            config.musicSpeakerDestination = if (rbDestCabin.isChecked) {
                AppConfig.MUSIC_SPEAKER_IN_CABIN
            } else {
                AppConfig.MUSIC_SPEAKER_EXTERNAL
            }
            updateQuickButtonsUi()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showLearnKeyDialog(
        currentKeyCode: Int,
        currentGesture: Int,
        onConfirm: (keyCode: Int, gesture: Int) -> Unit
    ) {
        val learnView = layoutInflater.inflate(R.layout.dialog_learn_key, null)
        val textDetectedKey = learnView.findViewById<TextView>(R.id.textDetectedKey)
        val radioGroupGesture = learnView.findViewById<RadioGroup>(R.id.radioGroupGesture)
        val rbSingle = learnView.findViewById<RadioButton>(R.id.rbGestureSingle)
        val rbDouble = learnView.findViewById<RadioButton>(R.id.rbGestureDouble)
        val rbLong = learnView.findViewById<RadioButton>(R.id.rbGestureLong)
        val btnCancel = learnView.findViewById<TextView>(R.id.btnCancelLearn)
        val btnConfirm = learnView.findViewById<TextView>(R.id.btnConfirmLearn)

        when (currentGesture) {
            KeyNameHelper.GESTURE_DOUBLE -> rbDouble.isChecked = true
            KeyNameHelper.GESTURE_LONG -> rbLong.isChecked = true
            else -> rbSingle.isChecked = true
        }

        var capturedKeyCode = if (currentKeyCode > 0) currentKeyCode else 0

        fun updateDetectedKeyText() {
            if (capturedKeyCode > 0) {
                val name = KeyNameHelper.getKeyName(capturedKeyCode)
                textDetectedKey.text = getString(R.string.quick_sound_detected_key, "$name (Code $capturedKeyCode)")
                textDetectedKey.setTextColor(ContextCompat.getColor(this, R.color.green_ok))
                btnConfirm.isEnabled = true
                btnConfirm.alpha = 1.0f
            } else {
                textDetectedKey.text = getString(R.string.quick_sound_learn_hint)
                textDetectedKey.setTextColor(ContextCompat.getColor(this, R.color.on_surface_muted))
                btnConfirm.isEnabled = false
                btnConfirm.alpha = 0.5f
            }
        }
        updateDetectedKeyText()

        val learnDialog = AlertDialog.Builder(this)
            .setView(learnView)
            .create()

        learnDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        onKeyLearnedCallback = { keyCode ->
            capturedKeyCode = keyCode
            updateDetectedKeyText()
        }
        activeKeyLearnDialog = learnDialog

        learnDialog.setOnDismissListener {
            activeKeyLearnDialog = null
            onKeyLearnedCallback = null
        }

        btnCancel.setOnClickListener {
            learnDialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            if (capturedKeyCode > 0) {
                val gesture = when (radioGroupGesture.checkedRadioButtonId) {
                    R.id.rbGestureDouble -> KeyNameHelper.GESTURE_DOUBLE
                    R.id.rbGestureLong -> KeyNameHelper.GESTURE_LONG
                    else -> KeyNameHelper.GESTURE_SINGLE
                }
                onConfirm(capturedKeyCode, gesture)
                learnDialog.dismiss()
            }
        }

        learnDialog.show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (activeKeyLearnDialog?.isShowing == true) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    return super.dispatchKeyEvent(event)
                }
                onKeyLearnedCallback?.invoke(event.keyCode)
                return true
            }
            return true
        }

        val focused = currentFocus
        if (focused is EditText) {
            val kc = event.keyCode
            if ((kc in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) ||
                (kc in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) ||
                kc == KeyEvent.KEYCODE_SPACE ||
                kc == KeyEvent.KEYCODE_DEL ||
                kc == KeyEvent.KEYCODE_ENTER ||
                kc == KeyEvent.KEYCODE_DPAD_LEFT ||
                kc == KeyEvent.KEYCODE_DPAD_RIGHT
            ) {
                return super.dispatchKeyEvent(event)
            }
        }

        if (::hotkeyManager.isInitialized && hotkeyManager.handleKeyEvent(event)) {
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    private fun showTrackSelectionDialog(onSelected: (MusicPlayerManager.Track) -> Unit) {
        val musicManager = MusicPlaybackController.get(this)
        val tracks = musicManager.scanMusicFolder()

        if (tracks.isEmpty()) {
            FolderPickerDialog(this, config.musicFolderPath) { newFolder ->
                config.musicFolderPath = newFolder
                val newTracks = musicManager.scanMusicFolder()
                if (newTracks.isNotEmpty()) {
                    showTrackListDialog(newTracks, onSelected)
                } else {
                    Toast.makeText(this, R.string.music_empty_folder, Toast.LENGTH_SHORT).show()
                }
            }.show()
        } else {
            showTrackListDialog(tracks, onSelected)
        }
    }

    private fun showTrackListDialog(tracks: List<MusicPlayerManager.Track>, onSelected: (MusicPlayerManager.Track) -> Unit) {
        val items = tracks.map { it.name }.toMutableList()
        val browseOption = getString(R.string.quick_sound_browse_folder)
        items.add(browseOption)

        AlertDialog.Builder(this)
            .setTitle(R.string.quick_sound_choose_track)
            .setItems(items.toTypedArray()) { _, which ->
                if (which == items.size - 1) {
                    FolderPickerDialog(this, config.musicFolderPath) { newFolder ->
                        config.musicFolderPath = newFolder
                        val newTracks = MusicPlaybackController.get(this).scanMusicFolder()
                        if (newTracks.isNotEmpty()) {
                            showTrackListDialog(newTracks, onSelected)
                        } else {
                            Toast.makeText(this, R.string.music_empty_folder, Toast.LENGTH_SHORT).show()
                        }
                    }.show()
                } else {
                    onSelected(tracks[which])
                }
            }
            .setNegativeButton(R.string.permission_dialog_cancel, null)
            .show()
    }

    // ==================== Center Area Mode Switching ====================

    private enum class CenterMode { TALK, MUSIC }

    private fun showCenterPanel(mode: CenterMode) {
        // Hide the talk button circle area and function buttons
        binding.talkButton.visibility = View.INVISIBLE
        binding.countdownRing.visibility = View.INVISIBLE
        binding.audioGlow.visibility = View.INVISIBLE
        binding.statusText.visibility = View.INVISIBLE
        binding.functionButtonsRow.visibility = View.INVISIBLE
        binding.panelBackButton.apply {
            alpha = 0f
            translationX = -60f
            visibility = View.VISIBLE
            animate().alpha(1f).translationX(0f).setDuration(500)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    private fun restoreCenterCircle() {
        if (musicPlayerPanel.isVisible) return

        binding.talkButton.visibility = View.VISIBLE
        binding.countdownRing.visibility = View.VISIBLE
        binding.audioGlow.visibility = View.VISIBLE
        binding.statusText.visibility = View.VISIBLE
        binding.functionButtonsRow.visibility = View.VISIBLE
        binding.panelBackButton.visibility = View.GONE
    }

    // ==================== Microphone Permission ====================

    private fun requestMicrophonePermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            AppLog.w(TAG, "Microphone permission not granted, requesting...")
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            AppLog.i(TAG, "Microphone permission already granted")
        }
    }

}
