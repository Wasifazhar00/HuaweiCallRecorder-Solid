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
import com.solid.callrecorder.service.CallRecorderService

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var infoText: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
            updateUI()
        } else {
            Toast.makeText(this, "Some permissions denied – recording may fail", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
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

        infoText.text = """
            Hybrid Call Recorder
            
            Classic path: MediaRecorder (may record only your side on many phones)
            
            For reliable both-sides:
            1. Install Shizuku
            2. Enable Wireless Debugging
            3. Grant shell-level access
            
            See README for full details and legal notes.
            
            Auto-record on call state is enabled via CallStateReceiver.
        """.trimIndent()

        updateUI()
        if (!checkPermissions()) requestPermissions()
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
        statusText.text = if (checkPermissions()) "Ready" else "Permissions required"
    }
}
