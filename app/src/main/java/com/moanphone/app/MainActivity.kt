package com.moanphone.app

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.moanphone.app.databinding.ActivityMainBinding
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var sensorService: SensorService? = null
    private var isBound = false
    private var isBinding = false
    private lateinit var prefs: SharedPreferences

    private val serviceStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SensorService.ACTION_SERVICE_STOPPED) {
                updateUI()
            }
        }
    }

    private var pendingTriggerType: SensorService.SlapType? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var recordingFile: File? = null

    private val pickSoundLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    // Persist permission to access this URI across reboots
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    pendingTriggerType?.let { saveCustomSound(uri, it) }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this, "Failed to persist file permission", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as SensorService.LocalBinder
            val service = localBinder.getService()
            sensorService = service
            isBound = true
            
            // Push current UI state to service immediately upon connection
            val voiceType = when {
                binding.rbMale.isChecked -> SensorService.VoiceType.MALE
                binding.rbCustom.isChecked -> SensorService.VoiceType.CUSTOM
                else -> SensorService.VoiceType.FEMALE
            }
            service.setVoiceType(voiceType)
            service.setCustomSlapUri(prefs.getString("custom_slap_uri", null))
            service.setCustomFallUri(prefs.getString("custom_fall_uri", null))
            service.setCustomChargeUri(prefs.getString("custom_charge_uri", null))
            service.setSensitivity(binding.sliderSensitivity.value)
            service.setFallDetectionEnabled(binding.switchFall.isChecked)
            service.setSlapDetectionEnabled(binding.switchSlap.isChecked)
            service.setChargingDetectionEnabled(binding.switchCharging.isChecked)
            
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sensorService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences("SlapPhonePrefs", Context.MODE_PRIVATE)

        loadSettings()
        setupUI()
        requestPermissionsIfNeeded()
        
        val filter = IntentFilter(SensorService.ACTION_SERVICE_STOPPED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceStoppedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceStoppedReceiver, filter)
        }
        
        // Try to bind if service is already running
        val intent = Intent(this, SensorService::class.java)
        if (bindService(intent, serviceConnection, 0)) {
            isBinding = true
        }
    }

    private fun setupUI() {
        binding.btnToggle.setOnClickListener {
            if (sensorService?.isRunning == true) {
                stopSlapService()
            } else {
                startSlapService()
            }
        }

        binding.sliderSensitivity.addOnChangeListener { _, value, _ ->
            binding.tvSensitivityValue.text = "Sensitivity: ${value.toInt()}%"
            sensorService?.setSensitivity(value)
            prefs.edit().putFloat("sensitivity", value).apply()
        }

        binding.switchFall.setOnCheckedChangeListener { _, checked ->
            sensorService?.setFallDetectionEnabled(checked)
            prefs.edit().putBoolean("fall_enabled", checked).apply()
        }

        binding.switchSlap.setOnCheckedChangeListener { _, checked ->
            sensorService?.setSlapDetectionEnabled(checked)
            prefs.edit().putBoolean("slap_enabled", checked).apply()
        }

        binding.switchCharging.setOnCheckedChangeListener { _, checked ->
            sensorService?.setChargingDetectionEnabled(checked)
            prefs.edit().putBoolean("charging_enabled", checked).apply()
        }

        binding.rgVoiceType.setOnCheckedChangeListener { _, checkedId ->
            val voiceType = when (checkedId) {
                R.id.rbMale -> SensorService.VoiceType.MALE
                R.id.rbCustom -> SensorService.VoiceType.CUSTOM
                else -> SensorService.VoiceType.FEMALE
            }
            
            binding.layoutCustomSounds.visibility = if (voiceType == SensorService.VoiceType.CUSTOM) View.VISIBLE else View.GONE
            
            sensorService?.setVoiceType(voiceType)
            prefs.edit().putString("voice_type", voiceType.name).apply()
        }

        binding.btnPickSlapSound.setOnClickListener {
            pickCustomSound(SensorService.SlapType.SLAP)
        }
        binding.btnPickFallSound.setOnClickListener {
            pickCustomSound(SensorService.SlapType.FALL)
        }
        binding.btnPickChargeSound.setOnClickListener {
            pickCustomSound(SensorService.SlapType.CHARGE)
        }

        binding.btnRecordSlapSound.setOnClickListener { handleRecordClick(SensorService.SlapType.SLAP) }
        binding.btnRecordFallSound.setOnClickListener { handleRecordClick(SensorService.SlapType.FALL) }
        binding.btnRecordChargeSound.setOnClickListener { handleRecordClick(SensorService.SlapType.CHARGE) }
    }

    private fun handleRecordClick(type: SensorService.SlapType) {
        if (isRecording) {
            stopRecording(type)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startRecording(type)
            } else {
                pendingTriggerType = type
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            }
        }
    }

    private fun startRecording(type: SensorService.SlapType) {
        val fileName = "recorded_${type.name.lowercase()}.m4a"
        recordingFile = File(filesDir, fileName)
        
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            try {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(recordingFile?.absolutePath)
                prepare()
                start()
                isRecording = true
                updateRecordButtons(type, true)
                Toast.makeText(this@MainActivity, "Recording...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "Recording failed: ${e.message}", Toast.LENGTH_SHORT).show()
                mediaRecorder?.release()
                mediaRecorder = null
                isRecording = false
                updateRecordButtons(type, false)
            }
        }
    }

    private fun stopRecording(type: SensorService.SlapType) {
        mediaRecorder?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        mediaRecorder = null
        isRecording = false
        updateRecordButtons(type, false)
        
        recordingFile?.let {
            val uri = Uri.fromFile(it)
            saveCustomSound(uri, type)
            Toast.makeText(this, "Recording saved!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateRecordButtons(type: SensorService.SlapType, recording: Boolean) {
        val btn = when(type) {
            SensorService.SlapType.SLAP -> binding.btnRecordSlapSound
            SensorService.SlapType.FALL -> binding.btnRecordFallSound
            SensorService.SlapType.CHARGE -> binding.btnRecordChargeSound
        }
        btn.text = if (recording) "⏹️" else "🎤"
        // Disable other buttons while recording
        val otherButtons = listOf(
            binding.btnRecordSlapSound, binding.btnRecordFallSound, binding.btnRecordChargeSound,
            binding.btnPickSlapSound, binding.btnPickFallSound, binding.btnPickChargeSound,
            binding.btnToggle
        )
        otherButtons.forEach { if (it != btn) it.isEnabled = !recording }
    }

    private fun pickCustomSound(type: SensorService.SlapType) {
        pendingTriggerType = type
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            this.type = "audio/*"
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            pickSoundLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker found on device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadSettings() {
        binding.sliderSensitivity.value = prefs.getFloat("sensitivity", 50f)
        binding.tvSensitivityValue.text = "Sensitivity: ${binding.sliderSensitivity.value.toInt()}%"
        binding.switchFall.isChecked = prefs.getBoolean("fall_enabled", true)
        binding.switchSlap.isChecked = prefs.getBoolean("slap_enabled", true)
        binding.switchCharging.isChecked = prefs.getBoolean("charging_enabled", true)
        
        val voiceTypeName = prefs.getString("voice_type", "FEMALE")
        when (voiceTypeName) {
            "MALE" -> binding.rbMale.isChecked = true
            "CUSTOM" -> {
                binding.rbCustom.isChecked = true
                binding.layoutCustomSounds.visibility = View.VISIBLE
            }
            else -> binding.rbFemale.isChecked = true
        }
        
        prefs.getString("custom_slap_uri", null)?.let { uriStr ->
            binding.tvSlapSoundName.text = getFileName(uriStr.toUri()) ?: "File selected"
        }
        
        prefs.getString("custom_fall_uri", null)?.let { uriStr ->
            binding.tvFallSoundName.text = getFileName(uriStr.toUri()) ?: "File selected"
        }
        
        prefs.getString("custom_charge_uri", null)?.let { uriStr ->
            binding.tvChargeSoundName.text = getFileName(uriStr.toUri()) ?: "File selected"
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = it.getString(index)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return name ?: uri.path?.substringAfterLast('/')
    }

    private fun saveCustomSound(uri: Uri, type: SensorService.SlapType) {
        val key = when (type) {
            SensorService.SlapType.SLAP -> "custom_slap_uri"
            SensorService.SlapType.FALL -> "custom_fall_uri"
            SensorService.SlapType.CHARGE -> "custom_charge_uri"
        }
        prefs.edit().putString(key, uri.toString()).apply()
        
        val fileName = getFileName(uri) ?: "File selected"
        
        when (type) {
            SensorService.SlapType.SLAP -> {
                binding.tvSlapSoundName.text = fileName
                sensorService?.setCustomSlapUri(uri.toString())
            }
            SensorService.SlapType.FALL -> {
                binding.tvFallSoundName.text = fileName
                sensorService?.setCustomFallUri(uri.toString())
            }
            SensorService.SlapType.CHARGE -> {
                binding.tvChargeSoundName.text = fileName
                sensorService?.setCustomChargeUri(uri.toString())
            }
        }
    }

    private fun startSlapService() {
        val voiceType = when {
            binding.rbMale.isChecked -> "MALE"
            binding.rbCustom.isChecked -> "CUSTOM"
            else -> "FEMALE"
        }
        val slapUri = prefs.getString("custom_slap_uri", null)
        val fallUri = prefs.getString("custom_fall_uri", null)
        val chargeUri = prefs.getString("custom_charge_uri", null)

        val intent = Intent(this, SensorService::class.java).apply {
            putExtra("EXTRA_VOICE_TYPE", voiceType)
            putExtra("EXTRA_CUSTOM_SLAP_URI", slapUri)
            putExtra("EXTRA_CUSTOM_FALL_URI", fallUri)
            putExtra("EXTRA_CUSTOM_CHARGE_URI", chargeUri)
            putExtra("EXTRA_SENSITIVITY", binding.sliderSensitivity.value)
            putExtra("EXTRA_FALL_ENABLED", binding.switchFall.isChecked)
            putExtra("EXTRA_SLAP_ENABLED", binding.switchSlap.isChecked)
            putExtra("EXTRA_CHARGING_ENABLED", binding.switchCharging.isChecked)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        if (!isBinding && !isBound) {
            if (bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                isBinding = true
            }
        }
    }

    private fun stopSlapService() {
        if (isBound || isBinding) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isBound = false
            isBinding = false
        }
        val intent = Intent(this, SensorService::class.java)
        stopService(intent)
        binding.btnToggle.text = "🟢  START SLAPPING"
        binding.statusDot.setImageResource(R.drawable.dot_inactive)
        binding.tvStatus.text = "Inactive"
        sensorService = null
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        val running = sensorService?.isRunning ?: prefs.getBoolean("service_running", false)
        binding.btnToggle.text = if (running) "🔴  STOP SLAPPING" else "🟢  START SLAPPING"
        binding.tvStatus.text = if (running) "Active – listening for events" else "Inactive"
        binding.statusDot.setImageResource(if (running) R.drawable.dot_active else R.drawable.dot_inactive)
    }

    private fun requestPermissionsIfNeeded() {
        val permsNeeded = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }

        // HIGH_SAMPLING_RATE_SENSORS is a normal permission, but check anyway for completeness if it were dangerous.
        // It's declared in manifest, so it's granted at install time on Android 12+.

        if (permsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permsNeeded.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (requestCode == 100) {
            if (granted) {
                Toast.makeText(this, "Notification permission granted!", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == 101) {
            if (granted) {
                pendingTriggerType?.let { startRecording(it) }
            } else {
                Toast.makeText(this, "Microphone permission denied. Cannot record.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(serviceStoppedReceiver)
        
        mediaRecorder?.apply {
            try { stop() } catch (e: Exception) {}
            release()
        }
        mediaRecorder = null

        if (isBound || isBinding) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isBound = false
            isBinding = false
        }
    }
}
