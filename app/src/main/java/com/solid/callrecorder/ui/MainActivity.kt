package com.solid.callrecorder.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
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
                statusText.text = "Manual recording started"
            } else {
                requestPermissions()
            }
        }

        btnStop.setOnClickListener {
            val intent = Intent(this, CallRecorderService::class.java).apply {
                action = CallRecorderService.ACTION_STOP
            }
            startService(intent)
            statusText.text = "Recording stopped"
        }

        btnShizuku.setOnClickListener {
            if (!ShizukuEnhancedRecorder.isShizukuInstalled(this)) {
                Toast.makeText(this, "Please install Shizuku first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            enhancedRecorder.requestPermission()
            Toast.makeText(this, "Check Shizuku app to grant permission", Toast.LENGTH_LONG).show()
            statusText.postDelayed({ updateUI() }, 1500)
        }

        updateUI()
        if (!checkPermissions()) requestPermissions()
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

    private fun updateUI() {
        val basicOk = checkPermissions()
        val shizukuInstalled = ShizukuEnhancedRecorder.isShizukuInstalled(this)
        val shizukuReady = enhancedRecorder.isAvailable()

        val status = buildString {
            append(if (basicOk) "Basic permissions: OK" else "Basic permissions: Missing")
            append("\n")
            append(when {
                !shizukuInstalled -> "Shizuku: Not installed"
                shizukuReady -> "Shizuku: Ready (Enhanced path available)"
                else -> "Shizuku: Installed but not authorized"
            })
        }
        statusText.text = status

        infoText.text = """
            Solid Call Recorder v1.1 (Enhanced)

            Classic path: MediaRecorder (often one side)
            Enhanced path: Shizuku UserService + shell identity
            → tries VOICE_UPLINK + VOICE_DOWNLINK first

            Setup for both-sides:
            1. Install & start Shizuku (Wireless Debugging)
            2. Tap "Request Shizuku Permission"
            3. Grant this app inside Shizuku
            4. Make a call – Enhanced path is preferred automatically

            Recordings: Android/data/.../files/CallRecordings/
            See README for technical details and legal notes.
        """.trimIndent()
    }
}
