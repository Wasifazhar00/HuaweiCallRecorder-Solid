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
import com.solid.callrecorder.service.CallRecorderService

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnShizuku: Button
    private lateinit var chkOfflineOnly: CheckBox
    private lateinit var infoText: TextView

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val enhanced by lazy { ShizukuEnhancedRecorder(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            Toast.makeText(this, "Permissions OK", Toast.LENGTH_SHORT).show()
            maybeRequestBatteryExemption()
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
        infoText = findViewById(R.id.infoText)

        chkOfflineOnly.isChecked = prefs.getBoolean(KEY_OFFLINE_ONLY, false)
        chkOfflineOnly.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_OFFLINE_ONLY, checked).apply()
            btnShizuku.isEnabled = !checked
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
            statusText.text = "Recording…"
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
                    "Install Shizuku-Next (or Shizuku) from GitHub", Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            enhanced.requestPermission()
            Toast.makeText(this, "Grant access in Shizuku-Next", Toast.LENGTH_LONG).show()
            statusText.postDelayed({ updateUI() }, 1200)
        }

        if (!checkPermissions()) requestPermissions()
        else maybeRequestBatteryExemption()
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
        val basicOk = checkPermissions()
        val installed = ShizukuEnhancedRecorder.isShizukuInstalled(this)
        val ready = !offline && enhanced.isAvailable()

        statusText.text = buildString {
            append(if (basicOk) "Permissions: OK" else "Permissions: need grant")
            append('\n')
            when {
                offline -> append("Mode: Offline (Classic only – no Wi‑Fi debug needed)")
                !installed -> append("Shizuku-Next: not installed")
                ready -> append("Shizuku-Next: ready – both-sides path on")
                else -> append("Shizuku-Next: start service & authorize this app")
            }
        }

        infoText.text = """
            Solid Call Recorder 1.3 – silent, lean

            Recommended: Shizuku-Next (same API as Shizuku)
            • Start once (Wireless Debugging, USB, or root)
            • Many Next builds can keep service without constant Wi‑Fi
            • Authorize this app, then uncheck Offline only

            Offline only: no Shizuku, no developer mode required
            → Classic mic path (often one side). Works anywhere.

            App stays silent (no beep). Notification is minimal.
            Files: Android/data/…/files/CallRecordings/
        """.trimIndent()
    }

    companion object {
        const val PREFS = "solid_prefs"
        const val KEY_OFFLINE_ONLY = "offline_only"
    }
}
