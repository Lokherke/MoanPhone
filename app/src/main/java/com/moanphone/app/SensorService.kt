package com.moanphone.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.*
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicLong

class SensorService : Service(), SensorEventListener {

    inner class LocalBinder : Binder() {
        fun getService(): SensorService = this@SensorService
    }

    private val binder = LocalBinder()
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var chargingReceiver: BroadcastReceiver? = null
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var vibrator: Vibrator? = null
    
    @Volatile
    var isRunning = false
    @Volatile
    private var sensitivity = 50f
    @Volatile
    private var fallEnabled = true
    @Volatile
    private var slapEnabled = true
    @Volatile
    private var chargingEnabled = true
    @Volatile
    private var voiceType = VoiceType.FEMALE
    @Volatile
    private var customSlapUri: String? = null
    @Volatile
    private var customFallUri: String? = null
    @Volatile
    private var customChargeUri: String? = null

    private val lastSlapTime = AtomicLong(0L)
    private val lastFallTime = AtomicLong(0L)
    private val lastChargeTime = AtomicLong(0L)
    private val SOUND_COOLDOWN = 500L // ms

    // MediaPlayer management for custom (potentially long) sounds
    private val playerLock = Any()
    private var slapMediaPlayer: MediaPlayer? = null
    private var fallMediaPlayer: MediaPlayer? = null
    private var chargeMediaPlayer: MediaPlayer? = null
    private val activePlayers = mutableSetOf<MediaPlayer>()

    // SoundPool for default (short, rapid) sounds
    private var soundPool: SoundPool? = null
    private val soundMap = mutableMapOf<Int, Int>()

    // Stability and Watchdog components
    private val lastSensorEventTime = AtomicLong(0L)
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val lastEvent = lastSensorEventTime.get()
            if (isRunning && lastEvent > 0 && now - lastEvent > 300000) {
                Log.w("SensorService", "Watchdog: No sensor events for 5 minutes. Re-registering sensors...")
                reRegisterSensors()
            }
            watchdogHandler.postDelayed(this, 300000)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    private fun reRegisterSensors() {
        try {
            sensorManager.unregisterListener(this)
            val handler = sensorHandler ?: return
            accelerometer?.also {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler)
            }
            lastSensorEventTime.set(SystemClock.elapsedRealtime())
        } catch (e: Exception) {
            Log.e("SensorService", "Failed to re-register sensors", e)
        }
    }

    fun setSensitivity(value: Float) { this.sensitivity = value }
    fun setFallDetectionEnabled(enabled: Boolean) { this.fallEnabled = enabled }
    fun setSlapDetectionEnabled(enabled: Boolean) { this.slapEnabled = enabled }
    fun setChargingDetectionEnabled(enabled: Boolean) { this.chargingEnabled = enabled }
    fun setVoiceType(type: VoiceType) { this.voiceType = type }
    fun setCustomSlapUri(uri: String?) { this.customSlapUri = uri }
    fun setCustomFallUri(uri: String?) { this.customFallUri = uri }
    fun setCustomChargeUri(uri: String?) { this.customChargeUri = uri }

    private var isFalling = false
    private var fallStartTime = 0L

    private val slapThreshold: Float get() = 40f - (sensitivity / 100f) * 28f
    private val fallThresholdLow: Float get() = 1f + (sensitivity / 100f) * 2f
    private val fallImpactHigh: Float get() = 25f - (sensitivity / 100f) * 10f

    override fun onCreate() {
        super.onCreate()
        Log.i("SensorService", "Service onCreate")
        loadSettingsFromPrefs()
        
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SlapPhone::SensorWakeLock")
        
        initSoundPool()
        
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        createNotificationChannel()

        watchdogHandler.postDelayed(watchdogRunnable, 300000)
    }

    private fun initSoundPool() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(5)
            .setAudioAttributes(attrs)
            .build()
        
        val sounds = listOf(
            R.raw.fallsound, R.raw.slapsound, R.raw.chargesound,
            R.raw.m_fallsound, R.raw.m_slapsound, R.raw.m_chargesound
        )
        sounds.forEach { resId ->
            try {
                soundPool?.load(this, resId, 1)?.let { soundId ->
                    soundMap[resId] = soundId
                }
            } catch (e: Exception) {
                Log.e("SensorService", "Failed to load sound resource: $resId", e)
            }
        }
    }

    private fun loadSettingsFromPrefs() {
        val prefs = getSharedPreferences("SlapPhonePrefs", Context.MODE_PRIVATE)
        sensitivity = prefs.getFloat("sensitivity", 50f)
        fallEnabled = prefs.getBoolean("fall_enabled", true)
        slapEnabled = prefs.getBoolean("slap_enabled", true)
        chargingEnabled = prefs.getBoolean("charging_enabled", true)
        val voiceStr = prefs.getString("voice_type", "FEMALE")
        voiceType = try { VoiceType.valueOf(voiceStr!!) } catch (e: Exception) { VoiceType.FEMALE }
        customSlapUri = prefs.getString("custom_slap_uri", null)
        customFallUri = prefs.getString("custom_fall_uri", null)
        customChargeUri = prefs.getString("custom_charge_uri", null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopServiceInternal()
            return START_NOT_STICKY
        }
        
        intent?.let {
            val voiceStr = it.getStringExtra("EXTRA_VOICE_TYPE")
            if (voiceStr != null) voiceType = try { VoiceType.valueOf(voiceStr) } catch (e: Exception) { VoiceType.FEMALE }
            customSlapUri = it.getStringExtra("EXTRA_CUSTOM_SLAP_URI")
            customFallUri = it.getStringExtra("EXTRA_CUSTOM_FALL_URI")
            customChargeUri = it.getStringExtra("EXTRA_CUSTOM_CHARGE_URI")
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
        
        if (!isRunning) {
            startListening()
            setRunningState(true)
        }
        return START_STICKY
    }

    private fun setRunningState(running: Boolean) {
        isRunning = running
        val prefs = getSharedPreferences("SlapPhonePrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("service_running", running).apply()
    }

    private fun startListening() {
        if (sensorThread == null) {
            sensorThread = HandlerThread("SensorThread").apply { start() }
            sensorHandler = Handler(sensorThread!!.looper)
        }
        accelerometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, sensorHandler)
        }
        registerChargingReceiver()
        lastSensorEventTime.set(SystemClock.elapsedRealtime())
    }

    private fun registerChargingReceiver() {
        if (chargingReceiver != null) return
        chargingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_POWER_CONNECTED && chargingEnabled) {
                    triggerSlap(SlapType.CHARGE)
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_POWER_CONNECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(chargingReceiver, filter, null, sensorHandler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(chargingReceiver, filter, null, sensorHandler)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        lastSensorEventTime.set(SystemClock.elapsedRealtime())
        
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitudeSq = x * x + y * y + z * z
        val now = SystemClock.elapsedRealtime()

        if (fallEnabled) {
            val lowThreshold = fallThresholdLow
            if (!isFalling && magnitudeSq < lowThreshold * lowThreshold) {
                isFalling = true
                fallStartTime = now
                wakeLock?.acquire(3000) // Pulse wake lock when fall detected
            } else if (isFalling) {
                val fallDuration = now - fallStartTime
                val impactThreshold = fallImpactHigh
                if (magnitudeSq > impactThreshold * impactThreshold && fallDuration in 100..2000) {
                    isFalling = false
                    triggerSlap(SlapType.FALL)
                    return 
                } else if (fallDuration > 2500) {
                    isFalling = false
                }
            }
        }

        if (slapEnabled && !isFalling) {
            val threshold = slapThreshold
            if (magnitudeSq > threshold * threshold) {
                wakeLock?.acquire(2000) // Pulse wake lock for slap
                triggerSlap(SlapType.SLAP)
            }
        }
    }

    private fun triggerSlap(type: SlapType) {
        val now = SystemClock.elapsedRealtime()
        val lastTimeAtom = when (type) {
            SlapType.SLAP -> lastSlapTime
            SlapType.FALL -> lastFallTime
            SlapType.CHARGE -> lastChargeTime
        }
        
        val lastTime = lastTimeAtom.get()
        if (now - lastTime < SOUND_COOLDOWN) return
        if (!lastTimeAtom.compareAndSet(lastTime, now)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(50)
        }

        if (voiceType == VoiceType.CUSTOM) {
            val uri = when (type) {
                SlapType.SLAP -> customSlapUri
                SlapType.FALL -> customFallUri
                SlapType.CHARGE -> customChargeUri
            }
            if (uri != null) {
                playUri(uri, type)
                return
            }
        }
        triggerSlapDefault(type)
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest == null) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || 
                            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            stopAllPlayers()
                        }
                    }
                    .build()
            }
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonFocus() {
        synchronized(playerLock) {
            if (activePlayers.isNotEmpty()) return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun stopAllPlayers() {
        synchronized(playerLock) {
            activePlayers.forEach { 
                try {
                    if (it.isPlaying) it.stop()
                    it.release()
                } catch (e: Exception) {
                    Log.e("SensorService", "Error stopping player", e)
                }
            }
            activePlayers.clear()
            slapMediaPlayer = null
            fallMediaPlayer = null
            chargeMediaPlayer = null
            abandonFocus()
        }
    }

    private fun triggerSlapDefault(type: SlapType) {
        val resId = when (type) {
            SlapType.FALL -> if (voiceType == VoiceType.FEMALE) R.raw.fallsound else R.raw.m_fallsound
            SlapType.SLAP -> if (voiceType == VoiceType.FEMALE) R.raw.slapsound else R.raw.m_slapsound
            SlapType.CHARGE -> if (voiceType == VoiceType.FEMALE) R.raw.chargesound else R.raw.m_chargesound
        }
        val soundId = soundMap[resId] ?: return
        
        try {
            requestFocus()
            soundPool?.play(soundId, 1f, 1f, 1, 0, 1f)
            wakeLock?.acquire(2000)
            
            // Release focus after a short delay since SoundPool doesn't have a completion callback easily
            watchdogHandler.postDelayed({
                synchronized(playerLock) {
                    if (activePlayers.isEmpty()) abandonFocus()
                }
            }, 2000)
        } catch (e: Exception) { 
            Log.e("SensorService", "Error playing default sound with SoundPool", e)
            abandonFocus()
        }
    }

    private fun playUri(uriStr: String, type: SlapType) {
        try {
            val uri = android.net.Uri.parse(uriStr)
            try {
                contentResolver.openInputStream(uri)?.close()
            } catch (e: Exception) {
                Log.e("SensorService", "Access denied for URI: $uriStr", e)
                triggerSlapDefault(type)
                return
            }

            requestFocus()
            val player = MediaPlayer().apply {
                setDataSource(this@SensorService, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setOnPreparedListener { mp ->
                    synchronized(playerLock) {
                        activePlayers.add(mp)
                        mp.start()
                        wakeLock?.acquire(5000)
                    }
                }
                setOnCompletionListener { mp ->
                    synchronized(playerLock) {
                        activePlayers.remove(mp)
                        mp.release()
                        cleanupPlayerReference(mp)
                        if (activePlayers.isEmpty()) abandonFocus()
                    }
                }
                setOnErrorListener { mp, what, extra -> 
                    Log.e("SensorService", "MediaPlayer error: $what, $extra")
                    synchronized(playerLock) {
                        activePlayers.remove(mp)
                        mp.release()
                        cleanupPlayerReference(mp)
                        if (activePlayers.isEmpty()) abandonFocus()
                    }
                    true 
                }
                prepareAsync()
            }
            updatePlayerReference(player, type)
        } catch (e: Exception) {
            Log.e("SensorService", "Error playing URI", e)
            abandonFocus()
            triggerSlapDefault(type)
        }
    }

    private fun updatePlayerReference(player: MediaPlayer, type: SlapType) {
        synchronized(playerLock) {
            val oldPlayer = when (type) {
                SlapType.SLAP -> slapMediaPlayer
                SlapType.FALL -> fallMediaPlayer
                SlapType.CHARGE -> chargeMediaPlayer
            }
            oldPlayer?.let {
                activePlayers.remove(it)
                try { it.release() } catch (e: Exception) {}
            }
            when (type) {
                SlapType.SLAP -> slapMediaPlayer = player
                SlapType.FALL -> fallMediaPlayer = player
                SlapType.CHARGE -> chargeMediaPlayer = player
            }
        }
    }

    private fun cleanupPlayerReference(player: MediaPlayer) {
        synchronized(playerLock) {
            if (slapMediaPlayer == player) slapMediaPlayer = null
            if (fallMediaPlayer == player) fallMediaPlayer = null
            if (chargeMediaPlayer == player) chargeMediaPlayer = null
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onBind(intent: Intent?): IBinder = binder

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "SlapPhone Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, SensorService::class.java).apply { action = ACTION_STOP_SERVICE }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SlapPhone is active 👋")
            .setContentText("Listening for slaps, falls, and charging events.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun stopServiceInternal() {
        if (!isRunning) return
        setRunningState(false)
        stopListening()
        notifyServiceStopped()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun notifyServiceStopped() {
        val intent = Intent(ACTION_SERVICE_STOPPED).apply { setPackage(packageName) }
        sendBroadcast(intent)
    }

    private fun stopListening() {
        sensorManager.unregisterListener(this)
        chargingReceiver?.let { 
            try { unregisterReceiver(it) } catch (e: Exception) { Log.e("SensorService", "Receiver not registered", e) }
        }
        chargingReceiver = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    override fun onDestroy() {
        Log.i("SensorService", "Service onDestroy")
        setRunningState(false)
        super.onDestroy()
        watchdogHandler.removeCallbacks(watchdogRunnable)
        stopListening()
        sensorThread?.quitSafely()
        abandonFocus()
        synchronized(playerLock) {
            activePlayers.forEach { try { it.release() } catch (e: Exception) {} }
            activePlayers.clear()
            slapMediaPlayer = null
            fallMediaPlayer = null
            chargeMediaPlayer = null
        }
        soundPool?.release()
        soundPool = null
        notifyServiceStopped()
    }

    companion object {
        const val CHANNEL_ID = "slapphone_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP_SERVICE = "com.moanphone.app.ACTION_STOP_SERVICE"
        const val ACTION_SERVICE_STOPPED = "com.moanphone.app.ACTION_SERVICE_STOPPED"
    }
    enum class SlapType { FALL, SLAP, CHARGE }
    enum class VoiceType { FEMALE, MALE, CUSTOM }
}
