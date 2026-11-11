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
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.solid.callrecorder.R
import com.solid.callrecorder.recorder.ShizukuEnhancedRecorder
import com.solid.callrecorder.service.CallMonitorService
import com.solid.callrecorder.service.CallRecorderService

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnShizuku: Button
    private lateinit var chkOfflineOnly: CheckBox
    private lateinit var chkAuto: CheckBox
    private lateinit var infoText: TextView

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val enhanced by lazy { ShizukuEnhancedRecorder(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            Toast.makeText(this, "Permissions OK", Toast.LENGTH_SHORT).show()
            maybeRequestBatteryExemption()
            if (prefs.getBoolean(KEY_AUTO_RECORD, true)) {
                CallMonitorService.start(this)
            }
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
        chkOfflineOnly = findViewById(R.id.chkOfflineOnly)
        chkAuto = findViewById(R.id.chkAuto)
        infoText = findViewById(R.id.infoText)

        chkOfflineOnly.isChecked = prefs.getBoolean(KEY_OFFLINE_ONLY, false)
        chkAuto.isChecked = prefs.getBoolean(KEY_AUTO_RECORD, true)

        chkOfflineOnly.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_OFFLINE_ONLY, checked).apply()
            btnShizuku.isEnabled = !checked
            updateUI()
        }
        chkAuto.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_AUTO_RECORD, checked).apply()
            if (checked && checkPermissions()) CallMonitorService.start(this)
            else CallMonitorService.stop(this)
            updateUI()
        }
        btnShizuku.isEnabled = !chkOfflineOnly.isChecked

        btnStart.setOnClickListener {
            if (!checkPermissions()) {
                requestPermissions()
                return@setOnClickListener
            }
            startForegroundService(
                Intent(this, CallRecorderService::class.java).apply {
                    action = CallRecorderService.ACTION_START
                    putExtra(CallRecorderService.EXTRA_NUMBER, "manual")
                    putExtra(CallRecorderService.EXTRA_OFFLINE_ONLY, chkOfflineOnly.isChecked)
                }
            )
            statusText.text = "Manual recording…"
        }

        btnStop.setOnClickListener {
            startService(
                Intent(this, CallRecorderService::class.java).apply {
                    action = CallRecorderService.ACTION_STOP
                }
            )
            statusText.text = "Stopped"
        }

        btnShizuku.setOnClickListener {
            if (!ShizukuEnhancedRecorder.isShizukuInstalled(this)) {
                Toast.makeText(
                    this,
                    "Install Shizuku-Next for both-sides (optional)",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            enhanced.requestPermission()
            Toast.makeText(this, "Grant in Shizuku-Next", Toast.LENGTH_LONG).show()
            statusText.postDelayed({ updateUI() }, 1200)
        }

        if (!checkPermissions()) requestPermissions()
        else {
            maybeRequestBatteryExemption()
            if (chkAuto.isChecked) CallMonitorService.start(this)
        }
        updateUI()
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

    private fun maybeRequestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: Exception) {
        }
    }

    private fun updateUI() {
        val offline = prefs.getBoolean(KEY_OFFLINE_ONLY, false)
        val auto = prefs.getBoolean(KEY_AUTO_RECORD, true)
        val basicOk = checkPermissions()
        val installed = ShizukuEnhancedRecorder.isShizukuInstalled(this)
        val ready = !offline && enhanced.isAvailable()

        statusText.text = buildString {
            append(if (basicOk) "Permissions: OK" else "Permissions: required")
            append('\n')
            append(if (auto) "Auto-record: ON (in + out)" else "Auto-record: OFF")
            append('\n')
            when {
                offline -> append("Audio: Classic only (offline)")
                ready -> append("Audio: Enhanced (Shizuku) ready")
                installed -> append("Audio: authorize Shizuku-Next for both-sides")
                else -> append("Audio: Classic (install Shizuku-Next optional)")
            }
        }

        infoText.text = """
            Solid Call Recorder 2.0 – production

            Auto-record: starts when you answer or place a call,
            stops when the call ends. Silent (no beep).

            Both-sides on modern Android still needs shell privilege
            (Shizuku-Next). That manager cannot be shipped inside this
            APK – Android blocks it. Without it, Classic path still
            auto-records (often your side only).

            After install: grant permissions, keep Auto-record ON,
            allow battery unrestricted. Optional: Shizuku-Next.

            Files: Android/data/…/files/CallRecordings/
        """.trimIndent()
    }

    companion object {
        const val PREFS = "solid_prefs"
        const val KEY_OFFLINE_ONLY = "offline_only"
        const val KEY_AUTO_RECORD = "auto_record"
    }
}
