package com.kooo.hey.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.kooo.hey.audio.dsp.FrequencyShifter
import com.kooo.hey.audio.dsp.HighPassFilter
import com.kooo.hey.audio.dsp.LowPassFilter
import com.kooo.hey.log.AppLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

/**
 * Real-time microphone → speaker audio pipeline.
 * Also provides test tone playback for debugging output path.
 */
class AudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "AudioEngine"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_RELEASE_DELAY_MS = 10_000L
    }

    private val isRunning = AtomicBoolean(false)
    private val isTestPlaying = AtomicBoolean(false)
    private val isDraining = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var streamThread: Thread? = null
    private var drainThread: Thread? = null
    private var currentAudioSource: Int = -1
    private var currentEffectiveUsage: Int = -1

    /** app_process child for system-only AudioSource (HOTWORD etc.), launched via ADB root + TCP */
    private var hotwordProcess: Process? = null
    private var hotwordInputStream: java.io.InputStream? = null
    private var hotwordSocket: java.net.Socket? = null
    private var hotwordAdbHelper: com.kooo.hey.AdbPermissionHelper? = null

    /** Live gain multiplier – can be changed while running, takes effect immediately. */
    @Volatile
    var liveGain: Float = 3.0f

    /** Live bandpass enable – takes effect next audio frame. */
    @Volatile
    var liveBandpassEnabled: Boolean = true

    /** Live high-pass cutoff Hz – takes effect next audio frame. */
    @Volatile
    var liveHighPassCutoff: Int = 300

    /** Live low-pass cutoff Hz – takes effect next audio frame. */
    @Volatile
    var liveLowPassCutoff: Int = 4000

    /** Live frequency shift enable – takes effect next audio frame. */
    @Volatile
    var liveFreqShiftEnabled: Boolean = false

    /** Live frequency shift Hz – takes effect next audio frame. */
    @Volatile
    var liveFreqShiftHz: Float = 5f

    /** Live noise gate enable – takes effect next audio frame. */
    @Volatile
    var liveNoiseGateEnabled: Boolean = false

    /** Live noise gate strength 0~1 (0=off, 1=full) – takes effect next audio frame. */
    @Volatile
    var liveNoiseGateStrength: Float = 0.5f

    val running: Boolean get() = isRunning.get()

    /** Optional log callback for debug panel */
    var onLog: ((String) -> Unit)? = null

    /** Optional level callback: (inputLevel 0~1, outputLevel 0~1) */
    var onLevel: ((Float, Float) -> Unit)? = null

    /** Called when stream thread exits unexpectedly while engine is in running state. */
    var onUnexpectedStop: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val delayedReleaseRunnable = Runnable { releaseIdleResources() }

    @Volatile
    var isForeground: Boolean = true

    fun onAppForeground() {
        isForeground = true
        mainHandler.removeCallbacks(delayedReleaseRunnable)
        AppLog.d(TAG, "App foreground — cancel pending release")
    }

    fun onAppBackground() {
        isForeground = false
        if (isDraining.get()) {
            mainHandler.removeCallbacks(delayedReleaseRunnable)
            mainHandler.postDelayed(delayedReleaseRunnable, AUDIO_RELEASE_DELAY_MS)
            AppLog.d(TAG, "App background & draining — schedule release in ${AUDIO_RELEASE_DELAY_MS / 1000}s")
        } else if (!isRunning.get() && (audioRecord != null || audioTrack != null)) {
            mainHandler.removeCallbacks(delayedReleaseRunnable)
            mainHandler.postDelayed(delayedReleaseRunnable, AUDIO_RELEASE_DELAY_MS)
            AppLog.d(TAG, "App background & idle — schedule release in ${AUDIO_RELEASE_DELAY_MS / 1000}s")
        }
    }

    private fun releaseIdleResources() {
        if (isRunning.get()) return
        stopDrainThread()
        audioRecord?.let {
            try { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        audioRecord = null
        currentAudioSource = -1
        audioTrack?.let { try { it.release() } catch (_: Exception) {} }
        audioTrack = null
        currentEffectiveUsage = -1
        AppLog.d(TAG, "Idle audio resources released (${AUDIO_RELEASE_DELAY_MS / 1000}s timeout)")
    }

    private fun log(msg: String) {
        AppLog.i(TAG, msg)
        onLog?.invoke(msg)
    }

    private fun logError(msg: String, e: Exception? = null) {
        if (e != null) AppLog.e(TAG, msg, e) else AppLog.e(TAG, msg)
        onLog?.invoke("✗ $msg" + if (e != null) ": ${e.message}" else "")
    }

    // ==================== Mic → Speaker Pipeline ====================

    fun start(
        audioUsage: Int = 73,
        outputDevice: AudioDeviceInfo? = null,
        inputDevice: AudioDeviceInfo? = null,
        audioSource: Int = 6,      // VOICE_RECOGNITION
        volumeGain: Float = 3.0f,  // max software amplification
        enableNoiseGate: Boolean = false, // software noise gate
        noiseGateStrength: Float = 0.5f,  // 0~1, only used when enableNoiseGate
        enableAgc: Boolean = true,  // AGC anti-feedback
        enableReverb: Boolean = true, // reverb for voice enrichment
        reverbMix: Float = 0.3f,   // wet/dry mix (0=dry, 1=all reverb)
        enableHighPass: Boolean = true, // high-pass filter to cut low-freq rumble
        highPassCutoff: Int = 300, // HP cutoff frequency in Hz
        enableLowPass: Boolean = true, // low-pass filter to cut high-freq howling
        lowPassCutoff: Int = 4000, // LP cutoff frequency in Hz
        enableFreqShift: Boolean = false, // frequency shift anti-howling
        freqShiftHz: Float = 5f, // shift amount in Hz
        dspMode: Int = 0,  // Ecarx DSP: 0=关, 1=噪音抑制, 2=回声消除, 3=唤醒优化
    ) {
        if (isRunning.get()) {
            log("已在运行中")
            return
        }

        // For system audio sources (>= 1997), the entire start flow must run on a
        // background thread because it involves TCP sockets (NetworkOnMainThreadException)
        // and waiting for adb root + app_process child.
        val needsHotwordPipe = (inputDevice == null && audioSource >= 1997)
        if (needsHotwordPipe && Looper.myLooper() == Looper.getMainLooper()) {
            Thread({
                start(
                    audioUsage = audioUsage, outputDevice = outputDevice,
                    inputDevice = inputDevice, audioSource = audioSource,
                    volumeGain = volumeGain, enableNoiseGate = enableNoiseGate,
                    noiseGateStrength = noiseGateStrength, enableAgc = enableAgc, enableReverb = enableReverb,
                    reverbMix = reverbMix, enableHighPass = enableHighPass,
                    highPassCutoff = highPassCutoff, enableLowPass = enableLowPass,
                    lowPassCutoff = lowPassCutoff, enableFreqShift = enableFreqShift,
                    freqShiftHz = freqShiftHz, dspMode = dspMode,
                )
            }, "Hey-HotwordStart").start()
            return
        }

        if (isDraining.get()) {
            stopDrainThread()
            log("  停止 drain，复用已有 AudioRecord")
        }
        if (mainHandler.hasCallbacks(delayedReleaseRunnable)) {
            mainHandler.removeCallbacks(delayedReleaseRunnable)
            log("  取消延迟释放，复用音频资源")
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            logError("没有录音权限 (RECORD_AUDIO)")
            return
        }

        val minRecordBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        val minTrackBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        val baseBufferSize = maxOf(minRecordBuf, minTrackBuf, 4096)
        val bufferSize = baseBufferSize

        log("缓冲区: record=$minRecordBuf, track=$minTrackBuf, 使用=$bufferSize")

        try {
            // ---- AudioRecord ----
            val effectiveSource = if (inputDevice != null) {
                log("指定输入设备 id=${inputDevice.id} addr=${inputDevice.address}, 跳过 audioSource 路由, 使用 DEFAULT(0)")
                0
            } else {
                audioSource
            }
            val srcName = when(effectiveSource) {
                0 -> "DEFAULT"; 1 -> "MIC"; 5 -> "CAMCORDER"
                6 -> "VOICE_RECOGNITION"; 7 -> "VOICE_COMMUNICATION"
                9 -> "UNPROCESSED"; 10 -> "VOICE_PERFORMANCE"
                1997 -> "ECHO_REFERENCE"; 1998 -> "RADIO_TUNER"; 1999 -> "HOTWORD"
                else -> "SOURCE_$effectiveSource"
            }
            log("AudioRecord 源: $srcName ($effectiveSource)")

            val useHotwordPipe = effectiveSource >= 1997
            val record: AudioRecord?

            if (useHotwordPipe) {
                // System-only source: launch via ADB root + TCP socket
                // Requires: user has run `adb root` so adbd runs as root (UID 0)
                stopHotwordProcess()
                audioRecord?.let { try { it.release() } catch (_: Exception) {} }
                audioRecord = null

                val apkPath = context.applicationInfo.sourceDir

                // Already on background thread (redirected at top of start()).
                // Step 1: ensure adbd runs as root
                val rootLatch = java.util.concurrent.CountDownLatch(1)
                var rootOk = false
                val rootAdb = com.kooo.hey.AdbPermissionHelper(context)
                rootAdb.ensureRootAsync(object : com.kooo.hey.AdbPermissionHelper.Callback {
                    override fun onLog(message: String) {
                        if (message.isNotBlank()) log("  [root] $message")
                    }
                    override fun onComplete(allSuccess: Boolean) {
                        rootOk = allSuccess
                        rootLatch.countDown()
                    }
                })
                if (!rootLatch.await(20, java.util.concurrent.TimeUnit.SECONDS) || !rootOk) {
                    logError("adb root 失败，无法获取 HOTWORD 权限")
                    return
                }

                // Step 2: open TCP server socket
                val serverSocket = java.net.ServerSocket(0)
                serverSocket.soTimeout = 12000
                val port = serverSocket.localPort
                log("启动 app_process 管道 (root): source=$srcName, TCP:$port")

                val shellCmd = "export CLASSPATH='$apkPath'; " +
                    "exec app_process /system/bin " +
                    "com.kooo.hey.audio.HotwordMicWorker stream $effectiveSource $SAMPLE_RATE $port"

                // Step 3: launch app_process via ADB (now as root)
                val adb = com.kooo.hey.AdbPermissionHelper(context)
                hotwordAdbHelper = adb
                adb.executeShellCommandAsync(shellCmd, 600_000, object : com.kooo.hey.AdbPermissionHelper.Callback {
                    override fun onLog(message: String) {
                        if (message.isNotBlank()) log("  [hotword] $message")
                    }
                    override fun onComplete(allSuccess: Boolean) {
                        log("  [hotword] 进程结束")
                    }
                })

                // Step 4: accept TCP connection from child
                val clientSocket = serverSocket.accept()
                clientSocket.tcpNoDelay = true
                hotwordSocket = clientSocket
                hotwordInputStream = clientSocket.getInputStream()
                serverSocket.close()
                log("✓ app_process 管道已建立 ($srcName → TCP:$port → PCM16LE mono ${SAMPLE_RATE}Hz)")

                record = null
                currentAudioSource = effectiveSource
            } else {
                // Normal source: use local AudioRecord
                stopHotwordProcess()

                val existingRecord = audioRecord
                if (existingRecord != null
                    && currentAudioSource == effectiveSource
                    && existingRecord.state == AudioRecord.STATE_INITIALIZED) {
                    record = existingRecord
                    log("✓ 复用 AudioRecord ($srcName, ${SAMPLE_RATE}Hz, mono, 16bit)" +
                        ", recordingState=${record.recordingState}" +
                        ", routedDevice=${record.routedDevice?.let { "id=${it.id},type=${it.type},addr=${it.address}" } ?: "null"}")
                } else {
                    if (existingRecord != null) {
                        log("  旧 AudioRecord 不兼容: state=${existingRecord.state}, src=$currentAudioSource→$effectiveSource, 重新创建")
                    }
                    existingRecord?.let { try { it.release() } catch (_: Exception) {} }
                    val newRecord = AudioRecord(
                        effectiveSource,
                        SAMPLE_RATE, CHANNEL_IN, ENCODING, bufferSize * 2
                    )
                    if (newRecord.state != AudioRecord.STATE_INITIALIZED) {
                        logError("AudioRecord 初始化失败, state=${newRecord.state}, source=$srcName")
                        newRecord.release()
                        return
                    }
                    record = newRecord
                    audioRecord = record
                    currentAudioSource = effectiveSource
                    log("✓ AudioRecord 初始化成功 ($srcName, ${SAMPLE_RATE}Hz, mono, 16bit)")
                }

                // Set preferred input device (microphone)
                inputDevice?.let { device ->
                    val ok = record.setPreferredDevice(device)
                    log("  输入设备: setPreferredDevice(id=${device.id}, addr=${device.address}): $ok")
                }
            }

            // ---- AudioTrack ----
            val effectiveUsage = if (outputDevice != null) {
                log("指定输出设备 id=${outputDevice.id} addr=${outputDevice.address}, 跳过 usage 路由, 使用 USAGE_UNKNOWN(0)")
                0 // USAGE_UNKNOWN — 不让车载音频策略按 usage 强制路由
            } else {
                audioUsage
            }
            log("AudioTrack usage: $effectiveUsage, gain: ${volumeGain}x")

            val track: AudioTrack
            val existingTrack = audioTrack
            if (existingTrack != null
                && currentEffectiveUsage == effectiveUsage
                && existingTrack.state == AudioTrack.STATE_INITIALIZED) {
                track = existingTrack
                try { track.flush() } catch (_: Exception) {}
                log("✓ 复用 AudioTrack (usage=$effectiveUsage)" +
                    ", playState=${track.playState}" +
                    ", routedDevice=${track.routedDevice?.let { "id=${it.id},type=${getDeviceTypeName(it.type)},addr=${it.address}" } ?: "null"}")
            } else {
                if (existingTrack != null) {
                    log("  旧 AudioTrack 不兼容: state=${existingTrack.state}, usage=$currentEffectiveUsage→$effectiveUsage, 重新创建")
                }
                existingTrack?.let { try { it.release() } catch (_: Exception) {} }
                val newTrack = createTrack(effectiveUsage, bufferSize * 2)
                if (newTrack.state != AudioTrack.STATE_INITIALIZED) {
                    logError("AudioTrack 初始化失败, state=${newTrack.state}")
                    newTrack.release()
                    return
                }
                track = newTrack
                audioTrack = track
                currentEffectiveUsage = effectiveUsage
            }

            // Set preferred device BEFORE play
            outputDevice?.let { device ->
                val ok = track.setPreferredDevice(device)
                log("  setPreferredDevice(id=${device.id}, addr=${device.address}): $ok")
            }

            // Report routing info
            val routedDevice = track.routedDevice
            log("✓ AudioTrack 初始化成功 (usage=$effectiveUsage)")
            if (routedDevice != null) {
                log("  路由设备: type=${getDeviceTypeName(routedDevice.type)}, addr=${routedDevice.address}")
            } else {
                log("  路由设备: 未知 (可能在播放后才确定)")
            }

            // Start recording (only for local AudioRecord, not hotword pipe)
            if (!useHotwordPipe && record != null) {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    logError("AudioRecord.startRecording() 失败! recordingState=${record.recordingState}, state=${record.state}")
                }
                log("✓ AudioRecord 开始录音, recordingState=${record.recordingState}" +
                    ", routedDevice=${record.routedDevice?.let { "id=${it.id},type=${it.type},addr=${it.address}" } ?: "null"}")
            }

            track.play()
            log("✓ AudioTrack 开始播放, playState=${track.playState}")

            // Report actual routed device after play
            track.routedDevice?.let { rd ->
                log("  实际路由: type=${getDeviceTypeName(rd.type)}, addr=${rd.address}")
            }

            isRunning.set(true)
            liveGain = volumeGain
            liveBandpassEnabled = enableHighPass || enableLowPass
            liveHighPassCutoff = highPassCutoff
            liveLowPassCutoff = lowPassCutoff
            liveFreqShiftEnabled = enableFreqShift
            liveFreqShiftHz = freqShiftHz
            liveNoiseGateEnabled = enableNoiseGate
            liveNoiseGateStrength = noiseGateStrength
            AmpManager.acquire(context, "engine")

            // Streaming thread
            val useAgc = enableAgc
            val useReverb = enableReverb
            val wetMix = reverbMix.coerceIn(0f, 0.8f)  // cap at 0.8 to prevent mush
            val dryMix = 1f - wetMix
            val dspLabel = when (dspMode) {
                1 -> "噪音抑制"
                2 -> "回声消除"
                else -> "关"
            }
            val pipeInputStream = hotwordInputStream  // capture for thread
            streamThread = Thread({
                // Bandpass filters (always created; bypass controlled by volatile)
                val highPassFilter = HighPassFilter(SAMPLE_RATE, liveHighPassCutoff.toFloat())
                val lowPassFilter = LowPassFilter(SAMPLE_RATE, liveLowPassCutoff.toFloat())
                var lastHpCutoff = liveHighPassCutoff
                var lastLpCutoff = liveLowPassCutoff

                // Frequency shifter (anti-howling)
                val freqShifter = FrequencyShifter(SAMPLE_RATE, liveFreqShiftHz)
                var lastShiftHz = liveFreqShiftHz

                val inputMode = if (useHotwordPipe) "PIPE($srcName)" else "AudioRecord($srcName)"
                val bpEnabled = liveBandpassEnabled
                log("音频流线程启动 [$inputMode] (gain=${liveGain}x, AGC=${if (useAgc) "开" else "关"}, " +
                    "带通=${if (bpEnabled) "开(${liveHighPassCutoff}~${liveLowPassCutoff}Hz)" else "关"}, " +
                    "噪声门=${if (liveNoiseGateEnabled) "开(${liveNoiseGateStrength})" else "关"}, " +
                    "频移=${if (liveFreqShiftEnabled) "开(${liveFreqShiftHz}Hz)" else "关"}, " +
                    "混响=${if (useReverb) "开(${wetMix})" else "关"}, " +
                    "DSP=$dspLabel)")
                val buffer = ShortArray(bufferSize / 2)
                val pipeByteBuffer = if (useHotwordPipe) ByteArray(buffer.size * 2) else null
                val micFloat = FloatArray(buffer.size)

                var totalFrames = 0L
                var levelCounter = 0
                var exitError: String? = null

                // AGC limiter state (only attenuates, never amplifies)
                val targetLevel = 0.45f
                val minAtten = 0.05f  // floor: allow compressing down to 5%
                val attackCoeff = 0.15f
                val releaseCoeff = 0.02f
                val hardClipThreshold = (Short.MAX_VALUE * 0.95f).toInt()
                var agcAtten = 1.0f  // 1.0 = no attenuation

                if (useAgc) {
                    log("  AGC限幅: target=${targetLevel}, minAtten=${minAtten}, " +
                        "attack=${attackCoeff}, release=${releaseCoeff}")
                }

                // Reverb state: 3 comb filter delay lines at different lengths
                // Creates a rich, natural-sounding reverb tail
                val delay1 = IntArray(SAMPLE_RATE * 37 / 1000)  // 37ms
                val delay2 = IntArray(SAMPLE_RATE * 53 / 1000)  // 53ms
                val delay3 = IntArray(SAMPLE_RATE * 79 / 1000)  // 79ms (prime-ish gaps avoid flutter echo)
                var dPos1 = 0; var dPos2 = 0; var dPos3 = 0
                val fb1 = 0.35f; val fb2 = 0.30f; val fb3 = 0.25f  // feedback (decay speed)

                while (isRunning.get()) {
                    val read = if (useHotwordPipe && pipeInputStream != null && pipeByteBuffer != null) {
                        // Read PCM16LE bytes from pipe and convert to short[]
                        val bytesNeeded = buffer.size * 2
                        var totalRead = 0
                        while (totalRead < bytesNeeded) {
                            val n = pipeInputStream.read(pipeByteBuffer, totalRead, bytesNeeded - totalRead)
                            if (n <= 0) break
                            totalRead += n
                        }
                        val samples = totalRead / 2
                        for (i in 0 until samples) {
                            val lo = pipeByteBuffer[i * 2].toInt() and 0xFF
                            val hi = pipeByteBuffer[i * 2 + 1].toInt()
                            buffer[i] = ((hi shl 8) or lo).toShort()
                        }
                        samples
                    } else {
                        audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    }
                    if (read > 0) {
                        // --- 1. Bandpass first (on buffer, real-time) – 带通最有用，放在最前 ---
                        run {
                            val curBpEnabled = liveBandpassEnabled
                            val newHp = liveHighPassCutoff
                            val newLp = liveLowPassCutoff
                            if (newHp != lastHpCutoff) {
                                highPassFilter.setCutoff(newHp.toFloat())
                                lastHpCutoff = newHp
                            }
                            if (newLp != lastLpCutoff) {
                                lowPassFilter.setCutoff(newLp.toFloat())
                                lastLpCutoff = newLp
                            }
                            if (curBpEnabled) {
                                highPassFilter.processInPlace(buffer, 0, read)
                                lowPassFilter.processInPlace(buffer, 0, read)
                            }
                        }

                        // --- 2. Software noise gate (real-time enable + strength) ---
                        val ngEnabled = liveNoiseGateEnabled
                        val ngStrength = liveNoiseGateStrength.coerceIn(0f, 1f)
                        if (ngEnabled && ngStrength > 0.0001f) {
                            var i = 0
                            while (i < read) {
                                micFloat[i] = buffer[i] / 32768.0f
                                i++
                            }
                            var sumAbs = 0f
                            var ng = 0
                            while (ng < read) {
                                sumAbs += kotlin.math.abs(micFloat[ng])
                                ng++
                            }
                            val avgLevel = sumAbs / read
                            // 固定最小阈值，避免“跟着信号走”；强度高时阈值略提高，关门更狠
                            val baseThreshold = maxOf(0.003f, avgLevel * 0.5f)
                            val noiseThreshold = baseThreshold * (0.7f + 0.3f * ngStrength)
                            val upperThreshold = noiseThreshold * 1.5f
                            ng = 0
                            while (ng < read) {
                                val absVal = kotlin.math.abs(micFloat[ng])
                                val rawFactor: Float
                                if (absVal < noiseThreshold) {
                                    val ratio = if (noiseThreshold > 0f) absVal / noiseThreshold else 1f
                                    // 门限以下：ratio 0~1 → rawFactor 0~1，最强时能压到接近静音
                                    rawFactor = ratio
                                } else if (absVal < upperThreshold) {
                                    val ratio = (absVal - noiseThreshold) / (upperThreshold - noiseThreshold)
                                    rawFactor = 0.7f + 0.3f * ratio
                                } else {
                                    rawFactor = 1f
                                }
                                // strength=0 直通，strength=1 完全按 rawFactor（门内可压到 0.4，配合上面曲线更强）
                                val effectiveFactor = 1f - (1f - rawFactor) * ngStrength
                                micFloat[ng] *= effectiveFactor
                                ng++
                            }

                            // Convert float back to short
                            i = 0
                            while (i < read) {
                                val v = (micFloat[i] * 32768.0f).toInt()
                                buffer[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                                i++
                            }
                        }

                        // Calculate input level BEFORE gain processing (for level callback)
                        val inputLevel = rmsLevel(buffer, read)

                        // --- Step 1: Always apply slider gain ---
                        val gain = liveGain
                        if (gain != 1.0f) {
                            for (i in 0 until read) {
                                val amplified = (buffer[i] * gain).toInt()
                                buffer[i] = amplified.coerceIn(
                                    Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()
                                ).toShort()
                            }
                        }

                        // --- Step 2: AGC limiter (only pull down, never boost) ---
                        if (useAgc) {
                            val postLevel = rmsLevel(buffer, read)
                            if (postLevel > 0.0001f) {
                                val desired = (targetLevel / postLevel).coerceIn(minAtten, 1.0f)

                                agcAtten += if (desired < agcAtten) {
                                    (desired - agcAtten) * attackCoeff
                                } else {
                                    (desired - agcAtten) * releaseCoeff
                                }
                                agcAtten = agcAtten.coerceIn(minAtten, 1.0f)

                                if (agcAtten < 0.99f) {
                                    for (i in 0 until read) {
                                        buffer[i] = (buffer[i] * agcAtten).toInt()
                                            .coerceIn(-hardClipThreshold, hardClipThreshold)
                                            .toShort()
                                    }
                                }
                            }
                        }

                        // --- Frequency shift (anti-howling, real-time adjustable) ---
                        run {
                            val curEnabled = liveFreqShiftEnabled
                            val newHz = liveFreqShiftHz
                            if (newHz != lastShiftHz) {
                                freqShifter.setShift(newHz)
                                lastShiftHz = newHz
                            }
                            if (curEnabled) {
                                freqShifter.processInPlace(buffer, 0, read)
                            }
                        }

                        // --- Reverb: multi-tap comb filter ---
                        if (useReverb) {
                            for (i in 0 until read) {
                                val dry = buffer[i].toInt()

                                // Read from delay lines
                                val d1 = delay1[dPos1]
                                val d2 = delay2[dPos2]
                                val d3 = delay3[dPos3]

                                // Mix delay taps
                                val wetSignal = (d1 * 0.4f + d2 * 0.35f + d3 * 0.25f).toInt()

                                // Write to delay lines (input + feedback)
                                delay1[dPos1] = dry + (d1 * fb1).toInt()
                                delay2[dPos2] = dry + (d2 * fb2).toInt()
                                delay3[dPos3] = dry + (d3 * fb3).toInt()

                                // Advance positions (circular)
                                dPos1 = (dPos1 + 1) % delay1.size
                                dPos2 = (dPos2 + 1) % delay2.size
                                dPos3 = (dPos3 + 1) % delay3.size

                                // Mix dry + wet
                                val mixed = (dry * dryMix + wetSignal * wetMix).toInt()
                                buffer[i] = mixed.coerceIn(
                                    Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()
                                ).toShort()
                            }
                        }

                        val outputLevel = rmsLevel(buffer, read)

                        val written = audioTrack?.write(buffer, 0, read) ?: -1
                        if (written < 0) {
                            exitError = "AudioTrack.write error: $written"
                            logError(exitError)
                            break
                        }
                        if (written != read) {
                            exitError = "AudioTrack.write short write: written=$written, expected=$read"
                            logError(exitError)
                            break
                        }

                        totalFrames += read

                        if (totalFrames == read.toLong()) {
                            log("  首帧: read=$read, written=$written, " +
                                "in=${"%.4f".format(inputLevel)}, out=${"%.4f".format(outputLevel)}, " +
                                "gain=${liveGain}x, agcAtten=${"%.2f".format(agcAtten)}")
                        }

                        // Push levels ~30 times per second
                        levelCounter += read
                        if (levelCounter >= SAMPLE_RATE / 30) {
                            levelCounter = 0
                            onLevel?.invoke(inputLevel, outputLevel)
                        }

                    } else if (read < 0) {
                        exitError = "AudioRecord.read 错误: $read"
                        logError(exitError)
                        break
                    }
                }
                log("音频流线程结束, 总帧数=$totalFrames, gain=${liveGain}x, 最终agcAtten=${"%.2f".format(agcAtten)}")

                val wasRunning = isRunning.getAndSet(false)
                if (exitError != null && wasRunning) {
                    val msg = exitError ?: "未知错误"
                    mainHandler.post {
                        onUnexpectedStop?.invoke(msg)
                    }
                }
            }, "Hey-AudioStream").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

        } catch (e: Exception) {
            logError("启动音频引擎失败", e)
            stop()
        }
    }

    fun stop() {
        val wasRunning = isRunning.getAndSet(false)
        if (wasRunning) {
            AmpManager.release(context, "engine")
        }

        val keepMicAlive = currentEffectiveUsage == 72 && isForeground && hotwordProcess == null

        streamThread?.let { t ->
            try { t.join(1000) } catch (_: Exception) {}
        }
        streamThread = null

        // Always stop hotword child process
        stopHotwordProcess()

        if (keepMicAlive && audioRecord != null) {
            startDrainThread()
        } else {
            stopDrainThread()
            audioRecord?.let { rec ->
                try { if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop() } catch (_: Exception) {}
                try { rec.release() } catch (_: Exception) {}
            }
            audioRecord = null
            currentAudioSource = -1
        }

        audioTrack?.let { trk ->
            try { if (trk.playState == AudioTrack.PLAYSTATE_PLAYING) trk.stop() } catch (_: Exception) {}
            try { trk.release() } catch (_: Exception) {}
        }
        audioTrack = null
        currentEffectiveUsage = -1

        mainHandler.removeCallbacks(delayedReleaseRunnable)

        log("音频引擎已停止" + if (keepMicAlive) " (麦克风保持占用)" else "")
    }

    /**
     * Fully release all audio resources. Call from Activity.onDestroy().
     * Unlike stop(), this releases the cached AudioRecord/AudioTrack so the
     * HAL can reclaim hardware. Between stop/start cycles within the same
     * Activity lifetime, instances are intentionally kept alive to avoid
     * HAL reconnection issues on some automotive platforms.
     */
    fun destroy() {
        stop()
        stopDrainThread()
        stopHotwordProcess()
        mainHandler.removeCallbacks(delayedReleaseRunnable)
        audioRecord?.let {
            try { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        audioRecord = null
        currentAudioSource = -1
        audioTrack?.let { try { it.release() } catch (_: Exception) {} }
        audioTrack = null
        currentEffectiveUsage = -1
    }

    private fun stopHotwordProcess() {
        // Close TCP socket (signals the child to stop via broken pipe)
        try { hotwordSocket?.close() } catch (_: Throwable) {}
        hotwordSocket = null
        hotwordInputStream = null

        // Stop the app_process child (legacy ProcessBuilder path)
        hotwordProcess?.let { proc ->
            try {
                proc.outputStream.write("STOP\n".toByteArray())
                proc.outputStream.flush()
            } catch (_: Throwable) {}
            try { proc.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) {}
            if (proc.isAlive) proc.destroyForcibly()
        }
        hotwordProcess = null

        // Release ADB helper
        hotwordAdbHelper = null
    }

    // ==================== Test Tone Playback ====================

    /**
     * Play a 440Hz sine wave test tone for 2 seconds.
     * Tests the output path WITHOUT involving the microphone.
     */
    fun playTestTone(audioUsage: Int = 73, outputDevice: AudioDeviceInfo? = null) {
        if (isTestPlaying.get()) {
            log("测试音正在播放中")
            return
        }

        Thread({
            isTestPlaying.set(true)
            AmpManager.acquire(context, "test_tone")
            try {
                val durationSec = 2
                val frequency = 440.0 // Hz
                val numSamples = SAMPLE_RATE * durationSec
                val bufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)

                val effectiveUsage = if (outputDevice != null) {
                    log("指定输出设备 id=${outputDevice.id} addr=${outputDevice.address}, 跳过 usage 路由")
                    0
                } else {
                    audioUsage
                }
                log("测试播放: ${frequency}Hz, ${durationSec}s, usage=$effectiveUsage")

                val track = createTrack(effectiveUsage, maxOf(bufSize, 4096))
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    logError("测试 AudioTrack 初始化失败, state=${track.state}")
                    track.release()
                    return@Thread
                }
                log("✓ 测试 AudioTrack 初始化成功")

                outputDevice?.let { device ->
                    val ok = track.setPreferredDevice(device)
                    log("  setPreferredDevice(id=${device.id}, addr=${device.address}): $ok")
                }

                track.play()
                log("✓ 测试播放开始, playState=${track.playState}")

                // Report routing
                track.routedDevice?.let { rd ->
                    log("  路由: type=${getDeviceTypeName(rd.type)}, addr=${rd.address}")
                }

                // Generate and write sine wave
                val buffer = ShortArray(1024)
                var samplesWritten = 0
                var levelCounter = 0
                while (samplesWritten < numSamples && isTestPlaying.get()) {
                    val toWrite = minOf(buffer.size, numSamples - samplesWritten)
                    for (i in 0 until toWrite) {
                        val angle = 2.0 * Math.PI * frequency * (samplesWritten + i) / SAMPLE_RATE
                        buffer[i] = (sin(angle) * Short.MAX_VALUE * 0.5).toInt().toShort()
                    }
                    val written = track.write(buffer, 0, toWrite)
                    if (written < 0) {
                        logError("测试 write 错误: $written")
                        break
                    }
                    samplesWritten += written

                    // Push output level for waveform
                    levelCounter += written
                    if (levelCounter >= SAMPLE_RATE / 30) {
                        levelCounter = 0
                        val level = rmsLevel(buffer, toWrite)
                        onLevel?.invoke(0f, level)  // no mic input, only output
                    }
                }

                track.stop()
                track.release()
                log("✓ 测试播放完成, 写入 $samplesWritten 采样 (${samplesWritten / SAMPLE_RATE}s)")

            } catch (e: Exception) {
                logError("测试播放失败", e)
            } finally {
                isTestPlaying.set(false)
                AmpManager.release(context, "test_tone")
            }
        }, "Hey-TestTone").start()
    }

    /** Stop test tone if playing */
    fun stopTestTone() {
        isTestPlaying.set(false)
    }

    // ==================== Usage Sweep Test ====================

    private val isSweeping = AtomicBoolean(false)

    val sweeping: Boolean get() = isSweeping.get()

    /**
     * Sweep usage values 0..100 via a separate `app_process` child process.
     * Native crashes from invalid usage values only kill the child; this process survives.
     */
    fun startUsageSweep(from: Int = 0, to: Int = 100, toneDurationMs: Int = 800) {
        if (isSweeping.get()) {
            log("Usage 遍历已在运行中")
            return
        }

        Thread({
            isSweeping.set(true)
            sweepProcess = null
            AmpManager.acquire(context, "sweep")

            log("══ Usage 遍历测试 (进程隔离) ══")
            log("范围: $from ~ $to, 每个播放 ${toneDurationMs}ms")
            log("────────────────────────────")

            val results = mutableListOf<String>()
            var nextUsage = from
            val maxRestarts = 50

            var restarts = 0
            while (nextUsage <= to && isSweeping.get() && restarts < maxRestarts) {
                val lastTestedUsage = runSweepProcess(nextUsage, to, toneDurationMs, results)

                if (!isSweeping.get()) break

                if (lastTestedUsage < to) {
                    val crashedUsage = lastTestedUsage + 1
                    if (crashedUsage <= to) {
                        val name = AudioUsageNames.nameOf(crashedUsage)
                        val line = "  [$crashedUsage] $name → ✗ NATIVE CRASH (进程被杀)"
                        log(line)
                        results.add(line)
                    }
                    nextUsage = crashedUsage + 1
                    restarts++
                } else {
                    nextUsage = to + 1
                }
            }

            log("────────────────────────────")
            log("══ 遍历完成, 共 ${results.size} 个 usage ══")
            AmpManager.release(context, "sweep")
            sweepProcess = null
            isSweeping.set(false)
        }, "Hey-UsageSweep").start()
    }

    /**
     * Spawn one `app_process` child covering [from..to].
     * Returns the last usage value that produced a RESULT line (-1 if none).
     */
    private fun runSweepProcess(
        from: Int, to: Int, toneDurationMs: Int,
        results: MutableList<String>
    ): Int {
        val apkPath = context.applicationInfo.sourceDir
        val shellCmd = "export CLASSPATH='$apkPath'; " +
                "exec app_process /system/bin " +
                "com.kooo.hey.audio.UsageSweepWorker $from $to $toneDurationMs"

        log("  启动子进程: usage $from..$to")
        val pb = ProcessBuilder("sh", "-c", shellCmd)
        pb.redirectErrorStream(false)

        val process = pb.start()
        sweepProcess = process

        // Read stderr in background for diagnostics
        val stderrThread = Thread({
            try {
                process.errorStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        if (line.isNotBlank()) log("  [stderr] $line")
                    }
                }
            } catch (_: Throwable) {}
        }, "Hey-SweepStderr")
        stderrThread.isDaemon = true
        stderrThread.start()

        var lastUsage = from - 1
        var gotDone = false
        val perUsageTimeoutMs = (toneDurationMs + 3000).toLong()

        try {
            val reader = process.inputStream.bufferedReader()
            val readThread = Thread({
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line!!
                        if (l == "DONE") {
                            gotDone = true
                            break
                        }
                        if (!l.startsWith("RESULT|")) continue

                        val parts = l.split("|", limit = 5)
                        if (parts.size < 3) continue

                        val usage = parts[1].toIntOrNull() ?: continue
                        lastUsage = usage
                        val status = parts[2]
                        val name = AudioUsageNames.nameOf(usage)

                        val displayLine = if (status == "OK") {
                            val devType = parts.getOrElse(3) { "?" }
                            val addr = parts.getOrElse(4) { "" }
                            "  [$usage] $name → $devType:$addr"
                        } else {
                            val reason = parts.getOrElse(3) { "unknown" }
                            "  [$usage] $name → ✗ $reason"
                        }
                        log(displayLine)
                        results.add(displayLine)

                        if (!isSweeping.get()) {
                            process.destroyForcibly()
                            break
                        }
                    }
                } catch (_: Throwable) {}
            }, "Hey-SweepStdout")
            readThread.isDaemon = true
            readThread.start()

            // Watchdog: if no progress for perUsageTimeoutMs, kill the child
            var prevUsage = lastUsage
            while (readThread.isAlive && !gotDone && isSweeping.get()) {
                readThread.join(perUsageTimeoutMs)
                if (readThread.isAlive && lastUsage == prevUsage && !gotDone) {
                    log("  ⚠ 子进程超时 (卡在 usage ${lastUsage + 1}), 强制终止")
                    process.destroyForcibly()
                    break
                }
                prevUsage = lastUsage
            }
        } catch (_: Throwable) {}

        val exitCode = try { process.waitFor() } catch (_: Throwable) { -1 }
        try { stderrThread.join(2000) } catch (_: Throwable) {}

        if (!gotDone && lastUsage < to) {
            log("  ⚠ 子进程异常退出 (exit=$exitCode, 最后成功 usage=$lastUsage)")
        }

        return if (gotDone) to else lastUsage
    }

    @Volatile
    private var sweepProcess: Process? = null

    fun stopUsageSweep() {
        isSweeping.set(false)
        sweepProcess?.destroyForcibly()
    }

    // ==================== Mic Source Test (app_process) ====================

    private val isMicTesting = AtomicBoolean(false)
    val micTesting: Boolean get() = isMicTesting.get()

    @Volatile
    private var micTestProcess: Process? = null

    /**
     * Test all AudioSource values (0~10, 1997~1999) via a separate `app_process`
     * child process with shell UID. This can access system-only sources like
     * HOTWORD (1999) that regular apps cannot use.
     */
    fun startMicSourceTest(durationMs: Int = 1000) {
        if (isMicTesting.get()) {
            log("麦克风源测试已在运行中")
            return
        }

        Thread({
            isMicTesting.set(true)
            micTestProcess = null

            log("══ 麦克风源扫描 (app_process 提权) ══")
            log("每个源录音 ${durationMs}ms")
            log("────────────────────────────")

            val apkPath = context.applicationInfo.sourceDir
            val shellCmd = "export CLASSPATH='$apkPath'; " +
                "exec app_process /system/bin " +
                "com.kooo.hey.audio.MicSourceTestWorker $durationMs"

            try {
                val pb = ProcessBuilder("sh", "-c", shellCmd)
                pb.redirectErrorStream(false)
                val process = pb.start()
                micTestProcess = process

                val stderrThread = Thread({
                    try {
                        process.errorStream.bufferedReader().use { reader ->
                            reader.forEachLine { line ->
                                if (line.isNotBlank()) log("  [stderr] $line")
                            }
                        }
                    } catch (_: Throwable) {}
                }, "Hey-MicTestStderr").apply { isDaemon = true; start() }

                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        if (line == "DONE") return@forEachLine
                        if (!line.startsWith("RESULT|")) return@forEachLine

                        val parts = line.split("|", limit = 4)
                        if (parts.size < 3) return@forEachLine

                        val srcId = parts[1]
                        val status = parts[2]
                        val rest = parts.getOrElse(3) { "" }

                        if (status == "OK") {
                            log("  ✓ [$srcId] $rest")
                        } else {
                            log("  ✗ [$srcId] $rest")
                        }
                    }
                }

                val exitCode = process.waitFor()
                stderrThread.join(2000)
                log("────────────────────────────")
                log("══ 扫描完成 (exit=$exitCode) ══")
            } catch (e: Exception) {
                log("✗ 启动麦克风源测试失败: ${e.message}")
            } finally {
                micTestProcess = null
                isMicTesting.set(false)
            }
        }, "Hey-MicSourceTest").start()
    }

    fun stopMicSourceTest() {
        isMicTesting.set(false)
        micTestProcess?.destroyForcibly()
    }

    // ==================== Mic Drain (keep-alive) ====================

    private fun startDrainThread() {
        stopDrainThread()
        isDraining.set(true)
        val buffer = ShortArray(2048)
        drainThread = Thread({
            AppLog.d(TAG, "Mic drain started (keeping hardware occupied)")
            while (isDraining.get()) {
                val n = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (n < 0) break
            }
            AppLog.d(TAG, "Mic drain stopped")
        }, "Hey-MicDrain").apply { start() }
    }

    private fun stopDrainThread() {
        if (!isDraining.getAndSet(false)) return
        drainThread?.let { try { it.join(500) } catch (_: Exception) {} }
        drainThread = null
    }

    // ==================== Diagnostics ====================

    private fun dumpActiveRecordings() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val configs = am.activeRecordingConfigurations
            if (configs.isEmpty()) {
                log("  活跃录音: 无 (可能无权查看其他应用)")
                return
            }
            log("  活跃录音: ${configs.size} 个")
            for ((i, cfg) in configs.withIndex()) {
                val clientSrc = cfg.clientAudioSource
                val srcName = when (clientSrc) {
                    0 -> "DEFAULT"; 1 -> "MIC"; 6 -> "VOICE_RECOGNITION"
                    7 -> "VOICE_COMMUNICATION"; 9 -> "UNPROCESSED"
                    else -> "SOURCE_$clientSrc"
                }
                val device = cfg.audioDevice
                val devInfo = device?.let { "id=${it.id},type=${it.type},addr=${it.address}" } ?: "null"
                val effects = cfg.effects?.joinToString(",") { it.type.toString() } ?: "none"
                log("  [$i] source=$srcName($clientSrc), device=$devInfo, effects=[$effects]")
            }
        } catch (e: Exception) {
            log("  活跃录音查询失败: ${e.message}")
        }
    }

    // ==================== Helpers ====================

    /** Calculate RMS amplitude as 0.0 ~ 1.0 */
    private fun rmsLevel(buffer: ShortArray, length: Int): Float {
        if (length == 0) return 0f
        var sum = 0.0
        for (i in 0 until length) {
            val s = buffer[i].toDouble()
            sum += s * s
        }
        val rms = kotlin.math.sqrt(sum / length)
        return (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }

    private fun createTrack(audioUsage: Int, bufferSize: Int): AudioTrack {
        val attrs = AudioAttributes.Builder()
            .setUsage(audioUsage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(ENCODING)
            .setChannelMask(CHANNEL_OUT)
            .build()

        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun getDeviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUS -> "BUS"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        else -> "TYPE_$type"
    }

    fun getOutputDevices(): List<AudioDeviceInfo> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    }

    fun findOutputDevice(address: String): AudioDeviceInfo? {
        if (address.isBlank()) return null
        return getOutputDevices().find { it.address == address }
    }

    fun findOutputDeviceById(id: Int): AudioDeviceInfo? {
        if (id <= 0) return null
        val devices = getOutputDevices()
        val found = devices.find { it.id == id }
        if (found == null) {
            val available = devices.joinToString { "id=${it.id},type=${it.type},addr=${it.address}" }
            AppLog.w(TAG, "输出设备 id=$id 未找到! 可用: [$available]")
        }
        return found
    }

    fun getInputDevices(): List<AudioDeviceInfo> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
    }

    fun findInputDevice(id: Int): AudioDeviceInfo? {
        if (id <= 0) return null
        val devices = getInputDevices()
        val found = devices.find { it.id == id }
        if (found == null) {
            val available = devices.joinToString { "id=${it.id},type=${it.type},addr=${it.address}" }
            AppLog.w(TAG, "输入设备 id=$id 未找到! 可用: [$available]")
        }
        return found
    }
}
