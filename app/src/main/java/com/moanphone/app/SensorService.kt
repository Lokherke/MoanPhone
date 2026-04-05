package com.moanphone.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.*
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
    private var sensitivity = 50f
    private var fallEnabled = true
    private var slapEnabled = true
    private var chargingEnabled = true
    private var voiceType = VoiceType.FEMALE

    fun setSensitivity(value: Float) {
        this.sensitivity = value
    }

    fun setFallDetectionEnabled(enabled: Boolean) {
        this.fallEnabled = enabled
    }

    fun setSlapDetectionEnabled(enabled: Boolean) {
        this.slapEnabled = enabled
    }

    fun setChargingDetectionEnabled(enabled: Boolean) {
        this.chargingEnabled = enabled
    }

    fun setVoiceType(type: VoiceType) {
        this.voiceType = type
    }

    private var isFalling = false
    private var fallStartTime = 0L
    private val FALL_THRESHOLD_LOW = 3.0f
    private val FALL_IMPACT_HIGH = 20.0f

    private val slapThreshold: Float
        get() = 40f - (sensitivity / 100f) * 28f
    private val fallThresholdLow: Float
        get() = 1f + (sensitivity / 100f) * 4f

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val voiceStr = it.getStringExtra("EXTRA_VOICE_TYPE")
            if (voiceStr != null) {
                voiceType = try { VoiceType.valueOf(voiceStr) } catch (e: Exception) { VoiceType.FEMALE }
            }
            sensitivity = it.getFloatExtra("EXTRA_SENSITIVITY", sensitivity)
            fallEnabled = it.getBooleanExtra("EXTRA_FALL_ENABLED", fallEnabled)
            slapEnabled = it.getBooleanExtra("EXTRA_SLAP_ENABLED", slapEnabled)
            chargingEnabled = it.getBooleanExtra("EXTRA_CHARGING_ENABLED", chargingEnabled)
        }

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

    private fun registerChargingReceiver() {
        chargingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_POWER_CONNECTED && chargingEnabled) {
                    triggerMoan(MoanType.CHARGE)
                }
            }
        }
        registerReceiver(chargingReceiver, IntentFilter(Intent.ACTION_POWER_CONNECTED))
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val magnitude = sqrt(event.values[0].pow(2) + event.values[1].pow(2) + event.values[2].pow(2))
        val now = SystemClock.elapsedRealtime()

        if (fallEnabled) {
            if (!isFalling && magnitude < fallThresholdLow) {
                isFalling = true
                fallStartTime = now
            } else if (isFalling) {
                val fallDuration = now - fallStartTime
                if (magnitude > FALL_IMPACT_HIGH && fallDuration in 100..2000) {
                    isFalling = false
                    triggerMoan(MoanType.FALL)
                } else if (fallDuration > 2500) {
                    isFalling = false
                }
            }
        }

        if (slapEnabled && magnitude > slapThreshold) {
            triggerMoan(MoanType.SLAP)
        }
    }

    private fun triggerMoan(type: MoanType) {
        val resId = when (type) {
            MoanType.FALL -> if (voiceType == VoiceType.FEMALE) R.raw.fallsound else R.raw.m_fallsound
            MoanType.SLAP -> if (voiceType == VoiceType.FEMALE) R.raw.slapsound else R.raw.m_slapsound
            MoanType.CHARGE -> if (voiceType == VoiceType.FEMALE) R.raw.chargesound else R.raw.m_chargesound
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
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onBind(intent: Intent?): IBinder = binder

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "MoanPhone Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MoanPhone is active 😩")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        chargingReceiver?.let { unregisterReceiver(it) }
        slapMediaPlayer?.release()
        fallMediaPlayer?.release()
        chargeMediaPlayer?.release()
    }

    companion object {
        const val CHANNEL_ID = "moanphone_channel"
        const val NOTIFICATION_ID = 1
    }
    enum class MoanType { FALL, SLAP, CHARGE }
    enum class VoiceType { FEMALE, MALE }
}
