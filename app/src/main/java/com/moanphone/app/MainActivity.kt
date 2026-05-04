package com.moanphone.app

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
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
import com.moanphone.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var sensorService: SensorService? = null
    private var isBound = false
    private lateinit var prefs: SharedPreferences

    private val pickSoundLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                // Persist permission to access this URI across reboots
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                saveCustomSound(uri)
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
            service.setCustomSoundUri(prefs.getString("custom_sound_uri", null))
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
        
        // Try to bind if service is already running
        val intent = Intent(this, SensorService::class.java)
        bindService(intent, serviceConnection, 0)
    }

    private fun setupUI() {
        binding.btnToggle.setOnClickListener {
            if (sensorService?.isRunning == true) {
                stopMoanService()
            } else {
                startMoanService()
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
            
            binding.btnPickCustomSound.visibility = if (voiceType == SensorService.VoiceType.CUSTOM) View.VISIBLE else View.GONE
            binding.tvCustomSoundName.visibility = if (voiceType == SensorService.VoiceType.CUSTOM) View.VISIBLE else View.GONE
            
            sensorService?.setVoiceType(voiceType)
            prefs.edit().putString("voice_type", voiceType.name).apply()
        }

        binding.btnPickCustomSound.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            pickSoundLauncher.launch(intent)
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
                binding.btnPickCustomSound.visibility = View.VISIBLE
                binding.tvCustomSoundName.visibility = View.VISIBLE
            }
            else -> binding.rbFemale.isChecked = true
        }
        
        val customUri = prefs.getString("custom_sound_uri", null)
        if (customUri != null) {
            binding.tvCustomSoundName.text = "File selected"
        }
    }

    private fun saveCustomSound(uri: Uri) {
        prefs.edit().putString("custom_sound_uri", uri.toString()).apply()
        binding.tvCustomSoundName.text = "File selected"
        sensorService?.setCustomSoundUri(uri.toString())
    }

    private fun startMoanService() {
        val voiceType = when {
            binding.rbMale.isChecked -> "MALE"
            binding.rbCustom.isChecked -> "CUSTOM"
            else -> "FEMALE"
        }
        val customUri = prefs.getString("custom_sound_uri", null)

        val intent = Intent(this, SensorService::class.java).apply {
            putExtra("EXTRA_VOICE_TYPE", voiceType)
            putExtra("EXTRA_CUSTOM_SOUND_URI", customUri)
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
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopMoanService() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        val intent = Intent(this, SensorService::class.java)
        stopService(intent)
        binding.btnToggle.text = "🟢  START MOANING"
        binding.statusDot.setImageResource(R.drawable.dot_inactive)
        binding.tvStatus.text = "Inactive"
        sensorService = null
    }

    private fun updateUI() {
        val running = sensorService?.isRunning == true
        binding.btnToggle.text = if (running) "🔴  STOP MOANING" else "🟢  START MOANING"
        binding.tvStatus.text = if (running) "Active – listening for events" else "Inactive"
        binding.statusDot.setImageResource(if (running) R.drawable.dot_active else R.drawable.dot_inactive)
        
        // Update slider and switches to match current service state if needed
        sensorService?.let {
            // These would ideally come from the service or shared prefs
        }
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

        if (permsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permsNeeded.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            Toast.makeText(this, "Permissions updated!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
