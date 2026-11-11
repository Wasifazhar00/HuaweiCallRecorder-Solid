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
            if (prefs.getBoolean(KEY_AUTO_RECORD, true)) CallMonitorService.start(this)
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

        chkOfflineOnly.setOnCheckedChangeListener { _, c ->
            prefs.edit().putBoolean(KEY_OFFLINE_ONLY, c).apply()
            updateUI()
        }
        chkAuto.setOnCheckedChangeListener { _, c ->
            prefs.edit().putBoolean(KEY_AUTO_RECORD, c).apply()
            if (c && checkPermissions()) CallMonitorService.start(this)
            else CallMonitorService.stop(this)
            updateUI()
        }

        btnPerms.setOnClickListener { requestPermissions() }
        btnBattery.setOnClickListener { openBatterySettings() }
        btnGetShizuku.setOnClickListener { openShizukuDownload() }
        btnOpenShizuku.setOnClickListener { openShizukuApp() }

        btnShizuku.setOnClickListener {
            if (!ShizukuEnhancedRecorder.isShizukuInstalled(this)) {
                openShizukuDownload()
                return@setOnClickListener
            }
            if (!isShizukuRunning()) {
                openShizukuApp()
                return@setOnClickListener
            }
            enhanced.requestPermission()
            Toast.makeText(this, "Grant access in Shizuku", Toast.LENGTH_LONG).show()
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
        }

        btnStop.setOnClickListener {
            startService(
                Intent(this, CallRecorderService::class.java).apply {
                    action = CallRecorderService.ACTION_STOP
                }
            )
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
        return (getSystemService(POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
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
            }
        }
    }

    private fun isShizukuRunning(): Boolean = try {
        rikka.shizuku.Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    private fun openShizukuDownload() {
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/thedjchi/Shizuku/releases")
                )
            )
        } catch (_: Exception) {
        }
    }

    private fun openShizukuApp() {
        for (pkg in arrayOf("moe.shizuku.privileged.api", "moe.shizuku.manager")) {
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                startActivity(it)
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
            appendLine(mark(permsOk, "Permissions"))
            appendLine(mark(batteryOk, "Battery unrestricted"))
            appendLine(mark(auto, "Auto-record"))
            if (!offline) {
                appendLine(mark(installed, "Shizuku installed"))
                appendLine(mark(running, "Shizuku running"))
                appendLine(mark(authorized, "Authorized"))
            }
        }

        statusText.text = when {
            permsOk && auto -> "Ready – auto-record on"
            else -> "Finish checklist"
        }

        btnShizuku.isEnabled = !offline
        btnGetShizuku.isEnabled = !offline
        btnOpenShizuku.isEnabled = !offline

        infoText.text = """
            Files folder:
            ${RecordingStorage.humanPath(this)}

            Look in: Internal storage → Recordings → CallRecorder

            Format: M4A AAC 44.1 kHz 96 kbps (clearer speech)

            Tip: speakerphone often improves offline capture.
            Both-sides still best with Shizuku when available.
        """.trimIndent()
    }

    companion object {
        const val PREFS = "solid_prefs"
        const val KEY_OFFLINE_ONLY = "offline_only"
        const val KEY_AUTO_RECORD = "auto_record"
    }
}
