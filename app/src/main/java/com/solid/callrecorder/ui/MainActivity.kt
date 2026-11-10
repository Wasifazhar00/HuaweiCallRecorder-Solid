package com.solid.callrecorder.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.solid.callrecorder.R
import com.solid.callrecorder.recorder.ShizukuEnhancedRecorder
import com.solid.callrecorder.service.CallRecorderService

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnShizuku: Button
    private lateinit var infoText: TextView

    private val enhancedRecorder by lazy { ShizukuEnhancedRecorder(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "Permissions OK", Toast.LENGTH_SHORT).show()
            maybeRequestBatteryExemption()
        } else {
            Toast.makeText(this, "Some permissions denied – recording may fail", Toast.LENGTH_LONG).show()
        }
        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnShizuku = findViewById(R.id.btnShizuku)
        infoText = findViewById(R.id.infoText)

        btnStart.setOnClickListener {
            if (checkPermissions()) {
                val intent = Intent(this, CallRecorderService::class.java).apply {
                    action = CallRecorderService.ACTION_START
                    putExtra(CallRecorderService.EXTRA_NUMBER, "manual")
                }
                startForegroundService(intent)
                statusText.text = "Recording…"
            } else {
                requestPermissions()
            }
        }

        btnStop.setOnClickListener {
            startService(Intent(this, CallRecorderService::class.java).apply {
                action = CallRecorderService.ACTION_STOP
            })
            statusText.text = "Stopped"
        }

        btnShizuku.setOnClickListener {
            if (!ShizukuEnhancedRecorder.isShizukuInstalled(this)) {
                Toast.makeText(this, "Install Shizuku first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            enhancedRecorder.requestPermission()
            Toast.makeText(this, "Grant this app in Shizuku", Toast.LENGTH_LONG).show()
            statusText.postDelayed({ updateUI() }, 1500)
        }

        updateUI()
        if (!checkPermissions()) requestPermissions()
        else maybeRequestBatteryExemption()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun checkPermissions(): Boolean {
        val needed = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val needed = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(needed.toTypedArray())
    }

    /** Ask once to ignore battery optimizations so the service is not killed mid-call. */
    private fun maybeRequestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            // Some OEMs block this intent – user can set it manually in Settings
        }
    }

    private fun updateUI() {
        val basicOk = checkPermissions()
        val shizukuInstalled = ShizukuEnhancedRecorder.isShizukuInstalled(this)
        val shizukuReady = enhancedRecorder.isAvailable()

        statusText.text = buildString {
            append(if (basicOk) "Permissions: OK" else "Permissions: Missing")
            append("\n")
            append(when {
                !shizukuInstalled -> "Shizuku: Not installed (Classic only)"
                shizukuReady -> "Shizuku: Ready – both-sides path active"
                else -> "Shizuku: Install & grant permission"
            })
        }

        infoText.text = """
            Solid Call Recorder – silent mode

            • No beep / no announcement from this app
            • Notification is silent and minimal (required by Android)
            • Low battery: service only runs during a call

            For both sides (recommended):
            1. Install & start Shizuku
            2. Tap "Request Shizuku Permission"
            3. Grant access

            Without Shizuku: Classic path (often your voice only)

            Files: Android/data/…/files/CallRecordings/
            Check local laws before recording calls.
        """.trimIndent()
    }
}
