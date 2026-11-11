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
import com.solid.callrecorder.recorder.RecordingStorage
import com.solid.callrecorder.recorder.ShizukuEnhancedRecorder
import com.solid.callrecorder.service.CallMonitorService
import com.solid.callrecorder.service.CallRecorderService

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var checklistText: TextView
    private lateinit var infoText: TextView
    private lateinit var btnPerms: Button
    private lateinit var btnBattery: Button
    private lateinit var btnGetShizuku: Button
    private lateinit var btnOpenShizuku: Button
    private lateinit var btnShizuku: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var chkOfflineOnly: CheckBox
    private lateinit var chkAuto: CheckBox

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val enhanced by lazy { ShizukuEnhancedRecorder(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            Toast.makeText(this, "Permissions OK", Toast.LENGTH_SHORT).show()
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
        checklistText = findViewById(R.id.checklistText)
        infoText = findViewById(R.id.infoText)
        btnPerms = findViewById(R.id.btnPerms)
        btnBattery = findViewById(R.id.btnBattery)
        btnGetShizuku = findViewById(R.id.btnGetShizuku)
        btnOpenShizuku = findViewById(R.id.btnOpenShizuku)
        btnShizuku = findViewById(R.id.btnShizuku)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        chkOfflineOnly = findViewById(R.id.chkOfflineOnly)
        chkAuto = findViewById(R.id.chkAuto)

        chkOfflineOnly.isChecked = prefs.getBoolean(KEY_OFFLINE_ONLY, false)
        chkAuto.isChecked = prefs.getBoolean(KEY_AUTO_RECORD, true)

        chkOfflineOnly.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_OFFLINE_ONLY, checked).apply()
            updateUI()
        }
        chkAuto.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_AUTO_RECORD, checked).apply()
            if (checked && checkPermissions()) CallMonitorService.start(this)
            else CallMonitorService.stop(this)
            updateUI()
        }

        btnPerms.setOnClickListener { requestPermissions() }
        btnBattery.setOnClickListener { openBatterySettings() }
        btnGetShizuku.setOnClickListener { openShizukuDownload() }
        btnOpenShizuku.setOnClickListener { openShizukuApp() }

        btnShizuku.setOnClickListener {
            if (!ShizukuEnhancedRecorder.isShizukuInstalled(this)) {
                Toast.makeText(this, "Install Shizuku-Next first (step 3)", Toast.LENGTH_LONG).show()
                openShizukuDownload()
                return@setOnClickListener
            }
            if (!isShizukuRunning()) {
                Toast.makeText(this, "Start Shizuku-Next first (step 4)", Toast.LENGTH_LONG).show()
                openShizukuApp()
                return@setOnClickListener
            }
            enhanced.requestPermission()
            Toast.makeText(this, "Grant Solid Call Recorder in Shizuku-Next", Toast.LENGTH_LONG).show()
            statusText.postDelayed({ updateUI() }, 1500)
        }

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
            Toast.makeText(this, "Recording…", Toast.LENGTH_SHORT).show()
            updateUI()
        }

        btnStop.setOnClickListener {
            startService(
                Intent(this, CallRecorderService::class.java).apply {
                    action = CallRecorderService.ACTION_STOP
                }
            )
            Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show()
            updateUI()
        }

        if (!checkPermissions()) requestPermissions()
        else if (chkAuto.isChecked) CallMonitorService.start(this)

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

    private fun isBatteryUnrestricted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBatterySettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Settings → Battery → this app → Unrestricted", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isShizukuRunning(): Boolean {
        return try {
            rikka.shizuku.Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    private fun openShizukuDownload() {
        val urls = listOf(
            "https://github.com/thedjchi/Shizuku/releases",
            "https://shizuku.rikka.app/download/"
        )
        for (u in urls) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                Toast.makeText(this, "Download Shizuku / Shizuku-Next, install, return here", Toast.LENGTH_LONG).show()
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun openShizukuApp() {
        val packages = arrayOf("moe.shizuku.privileged.api", "moe.shizuku.manager")
        for (pkg in packages) {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                startActivity(launch)
                Toast.makeText(this, "Start Shizuku, then return", Toast.LENGTH_LONG).show()
                return
            }
        }
        openShizukuDownload()
    }

    private fun updateUI() {
        val offline = prefs.getBoolean(KEY_OFFLINE_ONLY, false)
        val auto = prefs.getBoolean(KEY_AUTO_RECORD, true)
        val permsOk = checkPermissions()
        val batteryOk = isBatteryUnrestricted()
        val installed = ShizukuEnhancedRecorder.isShizukuInstalled(this)
        val running = isShizukuRunning()
        val authorized = try {
            running && enhanced.isAvailable()
        } catch (_: Throwable) {
            false
        }

        fun mark(ok: Boolean, label: String) = (if (ok) "[OK] " else "[ ] ") + label

        checklistText.text = buildString {
            appendLine(mark(permsOk, "Permissions (mic, phone, notifications)"))
            appendLine(mark(batteryOk, "Battery unrestricted"))
            appendLine(mark(auto, "Auto-record enabled"))
            if (offline) {
                appendLine("[OK] Offline mode — Shizuku not required")
            } else {
                appendLine(mark(installed, "Shizuku / Shizuku-Next installed"))
                appendLine(mark(running, "Shizuku service running"))
                appendLine(mark(authorized, "This app authorized"))
            }
        }

        statusText.text = when {
            offline && permsOk && auto -> "Ready (Classic offline auto-record)"
            authorized && permsOk && auto -> "Ready (Enhanced both-sides available)"
            permsOk && auto -> "Ready (Classic). Optional: Shizuku for both-sides."
            else -> "Finish checklist below"
        }

        btnShizuku.isEnabled = !offline
        btnGetShizuku.isEnabled = !offline
        btnOpenShizuku.isEnabled = !offline

        val path = RecordingStorage.humanPath(this)
        infoText.text = """
            Storage and format

            Folder: $path
            (usually Internal storage / Music / CallRecordings)

            Classic: M4A AAC mono 16 kHz 64 kbps
            (~0.5 MB per minute, clear speech, small size)

            Enhanced: 16 kHz mono WAV when Shizuku is active

            Silent: no beep from this app.
        """.trimIndent()
    }

    companion object {
        const val PREFS = "solid_prefs"
        const val KEY_OFFLINE_ONLY = "offline_only"
        const val KEY_AUTO_RECORD = "auto_record"
    }
}
