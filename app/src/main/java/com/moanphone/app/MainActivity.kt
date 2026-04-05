package com.moanphone.app

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.moanphone.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var sensorService: SensorService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as SensorService.LocalBinder
            sensorService = localBinder.getService()
            isBound = true
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
            val prefs = getSharedPreferences("MoanPhonePrefs", MODE_PRIVATE)
            prefs.edit().putFloat("sensitivity", value).apply()
            sensorService?.setSensitivity(value)
        }

        binding.switchFall.setOnCheckedChangeListener { _, checked ->
            val prefs = getSharedPreferences("MoanPhonePrefs", MODE_PRIVATE)
            prefs.edit().putBoolean("fallEnabled", checked).apply()
            sensorService?.setFallDetectionEnabled(checked)
        }

        binding.switchSlap.setOnCheckedChangeListener { _, checked ->
            val prefs = getSharedPreferences("MoanPhonePrefs", MODE_PRIVATE)
            prefs.edit().putBoolean("slapEnabled", checked).apply()
            sensorService?.setSlapDetectionEnabled(checked)
        }

        binding.switchCharging.setOnCheckedChangeListener { _, checked ->
            val prefs = getSharedPreferences("MoanPhonePrefs", MODE_PRIVATE)
            prefs.edit().putBoolean("chargingEnabled", checked).apply()
            sensorService?.setChargingDetectionEnabled(checked)
        }

        binding.rgVoiceType.setOnCheckedChangeListener { _, checkedId ->
            val voiceType = if (checkedId == R.id.rbMale) {
                SensorService.VoiceType.MALE
            } else {
                SensorService.VoiceType.FEMALE
            }
            val prefs = getSharedPreferences("MoanPhonePrefs", MODE_PRIVATE)
            prefs.edit().putString("voiceType", voiceType.name).apply()
            sensorService?.setVoiceType(voiceType)
        }
    }

    private fun startMoanService() {
        val prefs = getSharedPreferences("MoanPhonePrefs", MODE_PRIVATE)
        val intent = Intent(this, SensorService::class.java).apply {
            putExtra("EXTRA_VOICE_TYPE", prefs.getString("voiceType", SensorService.VoiceType.FEMALE.name))
            putExtra("EXTRA_SENSITIVITY", prefs.getFloat("sensitivity", 50f))
            putExtra("EXTRA_FALL_ENABLED", prefs.getBoolean("fallEnabled", true))
            putExtra("EXTRA_SLAP_ENABLED", prefs.getBoolean("slapEnabled", true))
            putExtra("EXTRA_CHARGING_ENABLED", prefs.getBoolean("chargingEnabled", true))
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
        
        // Update slider and switches to match current saved state
        val prefs = getSharedPreferences("MoanPhonePrefs", MODE_PRIVATE)
        val sensitivity = prefs.getFloat("sensitivity", 50f)
        binding.sliderSensitivity.value = sensitivity
        binding.tvSensitivityValue.text = "Sensitivity: ${sensitivity.toInt()}%"
        
        binding.switchFall.isChecked = prefs.getBoolean("fallEnabled", true)
        binding.switchSlap.isChecked = prefs.getBoolean("slapEnabled", true)
        binding.switchCharging.isChecked = prefs.getBoolean("chargingEnabled", true)
        
        val voiceStr = prefs.getString("voiceType", SensorService.VoiceType.FEMALE.name)
        if (voiceStr == SensorService.VoiceType.MALE.name) {
            binding.rbMale.isChecked = true
        } else {
            binding.rbFemale.isChecked = true
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
