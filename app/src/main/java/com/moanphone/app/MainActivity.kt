package com.moanphone.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity() {

    private var moanService: SensorService? = null
    private var isBound = false

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var statusDot: ImageView
    private lateinit var sliderSensitivity: Slider
    private lateinit var tvSensitivityValue: TextView
    private lateinit var switchFall: SwitchCompat
    private lateinit var switchSlap: SwitchCompat
    private lateinit var switchCharging: SwitchCompat

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as SensorService.LocalBinder
            moanService = binder.getService()
            isBound = true
            updateUI()
            // Apply current UI settings to the service upon connection
            updateServiceSettings()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            moanService = null
            updateUI()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startMoanService()
        } else {
            Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Views
        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)
        statusDot = findViewById(R.id.statusDot)
        sliderSensitivity = findViewById(R.id.sliderSensitivity)
        tvSensitivityValue = findViewById(R.id.tvSensitivityValue)
        switchFall = findViewById(R.id.switchFall)
        switchSlap = findViewById(R.id.switchSlap)
        switchCharging = findViewById(R.id.switchCharging)

        btnToggle.setOnClickListener {
            if (moanService?.isRunning == true) {
                stopMoanService()
            } else {
                checkAndStartService()
            }
        }

        sliderSensitivity.addOnChangeListener { _, value, _ ->
            tvSensitivityValue.text = "Sensitivity: ${value.toInt()}%"
            updateServiceSettings()
        }

        switchFall.setOnCheckedChangeListener { _, _ -> updateServiceSettings() }
        switchSlap.setOnCheckedChangeListener { _, _ -> updateServiceSettings() }
        switchCharging.setOnCheckedChangeListener { _, _ -> updateServiceSettings() }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, SensorService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    private fun updateServiceSettings() {
        moanService?.updateSettings(
            sliderSensitivity.value,
            switchFall.isChecked,
            switchSlap.isChecked,
            switchCharging.isChecked
        )
    }

    private fun updateUI() {
        val running = moanService?.isRunning ?: false
        if (running) {
            btnToggle.text = "🔴  STOP MOANING"
            btnToggle.setBackgroundColor(Color.parseColor("#FF4444")) // Red for stop
            tvStatus.text = "Active"
            statusDot.setImageResource(R.drawable.dot_active)
        } else {
            btnToggle.text = "🟢  START MOANING"
            btnToggle.setBackgroundColor(Color.parseColor("#FF6B9D")) // Pink for start
            tvStatus.text = "Inactive"
            statusDot.setImageResource(R.drawable.dot_inactive)
        }
    }

    private fun checkAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startMoanService()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startMoanService()
        }
    }

    private fun startMoanService() {
        val serviceIntent = Intent(this, SensorService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        // Bind immediately so we can update settings
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun stopMoanService() {
        val serviceIntent = Intent(this, SensorService::class.java)
        stopService(serviceIntent)
        // Service stopped, UI will update via connection if still bound, or manually
        moanService?.isRunning = false
        updateUI()
    }
}
