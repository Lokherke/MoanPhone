package com.moanphone.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.*
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.*
import androidx.core.app.NotificationCompat
import kotlin.math.*

class SensorService : Service(), SensorEventListener {

    inner class LocalBinder : Binder() {
        fun getService(): SensorService = this@SensorService
    }

    private val binder = LocalBinder()

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private var chargingReceiver: BroadcastReceiver? = null
    private var slapMediaPlayer: MediaPlayer? = null
    private var fallMediaPlayer: MediaPlayer? = null
    private var chargeMediaPlayer: MediaPlayer? = null

    var isRunning = false
    private var sensitivity = 50f  // 0–100

    // Feature toggles
    private var fallEnabled = true
    private var slapEnabled = true
    private var chargingEnabled = true

    // Fall detection state
    private var isFalling = false
    private var fallStartTime = 0L
    private val FALL_THRESHOLD_LOW = 3.0f   // m/s² — below this = free-fall
    private val FALL_IMPACT_HIGH = 20.0f    // m/s² — landing impact

    // Slap detection
    private var lastSlapTime = 0L
    private val SLAP_COOLDOWN_MS = 1500L

    // Debounce for any moan
    private var lastMoanTime = 0L
    private val MOAN_COOLDOWN_MS = 2000L

    // ── Sensitivity scaling ────────────────────────────────────────────────────
    // Higher sensitivity = lower thresholds needed to trigger
    private val slapThreshold: Float
        get() {
            // sensitivity 0→100 maps slapThreshold 40→12 m/s²
            return 40f - (sensitivity / 100f) * 28f
        }
    private val fallThresholdLow: Float
        get() {
            // sensitivity 0→100 maps freefall threshold 1→5 m/s²
            return 1f + (sensitivity / 100f) * 4f
        }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        startListening()
        isRunning = true
        return START_STICKY
    }

    private fun startListening() {
        accelerometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        registerChargingReceiver()
    }

    private fun stopListening() {
        sensorManager.unregisterListener(this)
        chargingReceiver?.let { unregisterReceiver(it) }
        chargingReceiver = null
    }

    // ── SensorEventListener ───────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)

        val now = SystemClock.elapsedRealtime()
        
        // ── Fall Detection ─────────────────────────────────────────────────
        if (fallEnabled) {
            if (!isFalling && magnitude < fallThresholdLow) {
                isFalling = true
                fallStartTime = now
            } else if (isFalling) {
                val fallDuration = now - fallStartTime
                if (magnitude > FALL_IMPACT_HIGH && fallDuration in 100..2000) {
                    // Device fell and just landed
                    isFalling = false
                    triggerMoan(MoanType.FALL)
                } else if (fallDuration > 2500) {
                    isFalling = false  // Timed out, not a real fall
                }
            }
        }

        // ── Slap Detection ─────────────────────────────────────────────────
        if (slapEnabled && magnitude > slapThreshold) {
            triggerMoan(MoanType.SLAP)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── Charging Detection ────────────────────────────────────────────────────

    private fun registerChargingReceiver() {
        chargingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_POWER_CONNECTED && chargingEnabled) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastMoanTime > MOAN_COOLDOWN_MS) {
                        triggerMoan(MoanType.CHARGE)
                        lastMoanTime = now
                    }
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_POWER_CONNECTED)
        registerReceiver(chargingReceiver, filter)
    }

    // ── Moan Generator ────────────────────────────────────────────────────────

    private fun triggerMoan(type: MoanType) {
        val resId = when (type) {
            MoanType.FALL -> R.raw.fallsound
            MoanType.SLAP -> R.raw.slapsound
            MoanType.CHARGE -> R.raw.chargesound
        }

        try {
            when (type) {
                MoanType.SLAP -> {
                    slapMediaPlayer?.apply {
                        try { if (isPlaying) stop() } catch (e: Exception) {}
                        release()
                    }
                    slapMediaPlayer = MediaPlayer.create(this, resId)?.apply {
                        setOnCompletionListener { 
                            it.release()
                            if (slapMediaPlayer == it) slapMediaPlayer = null
                        }
                        start()
                    }
                }
                MoanType.FALL -> {
                    fallMediaPlayer?.apply {
                        try { if (isPlaying) stop() } catch (e: Exception) {}
                        release()
                    }
                    fallMediaPlayer = MediaPlayer.create(this, resId)?.apply {
                        setOnCompletionListener { 
                            it.release()
                            if (fallMediaPlayer == it) fallMediaPlayer = null
                        }
                        start()
                    }
                }
                MoanType.CHARGE -> {
                    chargeMediaPlayer?.apply {
                        try { if (isPlaying) stop() } catch (e: Exception) {}
                        release()
                    }
                    chargeMediaPlayer = MediaPlayer.create(this, resId)?.apply {
                        setOnCompletionListener { 
                            it.release()
                            if (chargeMediaPlayer == it) chargeMediaPlayer = null
                        }
                        start()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun playMoan(type: MoanType) {
        val sampleRate = 44100
        val durationMs = when (type) {
            MoanType.FALL   -> 900
            MoanType.SLAP   -> 600
            MoanType.CHARGE -> 1100
        }
        val numSamples = (sampleRate * durationMs / 1000)
        val buffer = ShortArray(numSamples)

        when (type) {
            MoanType.FALL   -> generateFallMoan(buffer, sampleRate, numSamples)
            MoanType.SLAP   -> generateSlapMoan(buffer, sampleRate, numSamples)
            MoanType.CHARGE -> generateChargeMoan(buffer, sampleRate, numSamples)
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buffer.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(buffer, 0, buffer.size)
        track.play()
        Thread.sleep(durationMs.toLong() + 200)
        track.stop()
        track.release()
    }

    /**
     * Fall moan: starts high-pitched surprise "OHH!", slides down as falling,
     * then a grunt/oof on impact.
     * Frequency: 400 Hz → 200 Hz slide, then spike to 350 Hz at impact.
     */
    private fun generateFallMoan(buf: ShortArray, sr: Int, n: Int) {
        for (i in 0 until n) {
            val t = i.toFloat() / sr
            val progress = i.toFloat() / n

            // Frequency sweeps from 400 down to 200
            val freq = 400f - progress * 200f

            // Vibrato for voice-like texture
            val vibrato = sin(2 * PI * 6.0 * t).toFloat() * 3f
            val finalFreq = freq + vibrato

            // Amplitude: rises fast, sustains, fades at end
            val amp = when {
                progress < 0.1f -> progress / 0.1f
                progress > 0.85f -> (1f - progress) / 0.15f
                else -> 1.0f
            }

            val sample = sin(2 * PI * finalFreq * t).toFloat() * amp
            // Add slight harmonic for richness
            val harmonic = sin(2 * PI * finalFreq * 2 * t).toFloat() * amp * 0.3f

            buf[i] = ((sample + harmonic) * 28000).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    /**
     * Slap moan: sharp "Ow!" — starts with a quick high-pitch squeal,
     * drops rapidly. Short and punchy.
     */
    private fun generateSlapMoan(buf: ShortArray, sr: Int, n: Int) {
        for (i in 0 until n) {
            val t = i.toFloat() / sr
            val progress = i.toFloat() / n

            // Starts at 500 Hz, drops sharply to 180 Hz
            val freq = 500f * exp(-3.0f * progress) + 180f

            val vibrato = sin(2 * PI * 8.0 * t).toFloat() * 5f

            val amp = when {
                progress < 0.05f -> progress / 0.05f
                progress > 0.6f  -> (1f - progress) / 0.4f
                else -> 1.0f
            }

            val sample = sin(2 * PI * (freq + vibrato) * t).toFloat() * amp
            val harmonic = sin(2 * PI * (freq + vibrato) * 1.5 * t).toFloat() * amp * 0.25f

            buf[i] = ((sample + harmonic) * 30000).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    /**
     * Charge moan: satisfied "Mmm~" — warm, low hum that rises slightly.
     * Sounds like contentment/relief.
     */
    private fun generateChargeMoan(buf: ShortArray, sr: Int, n: Int) {
        for (i in 0 until n) {
            val t = i.toFloat() / sr
            val progress = i.toFloat() / n

            // Starts low at 220, rises to 280 as if satisfied
            val freq = 220f + progress * 60f

            val vibrato = sin(2 * PI * 5.5 * t).toFloat() * 4f
            val tremolo = 1f + 0.1f * sin(2 * PI * 3.0 * t).toFloat()

            val amp = when {
                progress < 0.08f -> progress / 0.08f
                progress > 0.88f -> (1f - progress) / 0.12f
                else -> 1.0f
            } * tremolo

            val sample = sin(2 * PI * (freq + vibrato) * t).toFloat() * amp
            val harmonic2 = sin(2 * PI * (freq + vibrato) * 2 * t).toFloat() * amp * 0.4f
            val harmonic3 = sin(2 * PI * (freq + vibrato) * 3 * t).toFloat() * amp * 0.15f

            buf[i] = ((sample + harmonic2 + harmonic3) * 26000)
                .toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    fun setSensitivity(value: Float) { sensitivity = value }
    fun setFallDetectionEnabled(enabled: Boolean) { fallEnabled = enabled }
    fun setSlapDetectionEnabled(enabled: Boolean) { slapEnabled = enabled }
    fun setChargingDetectionEnabled(enabled: Boolean) { chargingEnabled = enabled }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "MoanPhone Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps MoanPhone running in background" }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MoanPhone is active 😩")
            .setContentText("Listening for falls, slaps & charging…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        isRunning = false
    }

    companion object {
        const val CHANNEL_ID = "moanphone_channel"
        const val NOTIFICATION_ID = 1
    }

    enum class MoanType { FALL, SLAP, CHARGE }
}
