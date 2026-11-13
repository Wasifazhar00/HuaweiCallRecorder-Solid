package com.solid.callrecorder.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.solid.callrecorder.data.RecordingRepository
import com.solid.callrecorder.recorder.AutoRecordPolicy
import com.solid.callrecorder.recorder.OplusRecorderBridge
import com.solid.callrecorder.recorder.RecordingStorage
import com.solid.callrecorder.recorder.ShizukuEnhancedRecorder
import com.solid.callrecorder.service.CallMonitorService
import com.solid.callrecorder.service.CallRecorderService
import java.io.File

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
    private lateinit var btnHistory: Button
    private lateinit var btnTest: Button
    private lateinit var chkOfflineOnly: CheckBox
    private lateinit var chkAuto: CheckBox
    private lateinit var chkStrangersOnly: CheckBox
    private lateinit var chkSpeaker: CheckBox
    private lateinit var chkVoice: CheckBox
    private lateinit var chkPrivileged: CheckBox

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val enhanced by lazy { ShizukuEnhancedRecorder(this) }
    private var testRecorder: MediaRecorder? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it }) {
            Toast.makeText(this, "Permissions updated", Toast.LENGTH_SHORT).show()
            if (prefs.getBoolean(KEY_AUTO_RECORD, true)) CallMonitorService.start(this)
        }
        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!prefs.contains(KEY_RETENTION_DAYS)) {
            prefs.edit()
                .putInt(KEY_RETENTION_DAYS, 30)
                .putInt(KEY_MAX_RECORDINGS, 200)
                .apply()
        }

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
        btnHistory = findViewById(R.id.btnHistory)
        btnTest = findViewById(R.id.btnTest)
        chkOfflineOnly = findViewById(R.id.chkOfflineOnly)
        chkAuto = findViewById(R.id.chkAuto)
        chkStrangersOnly = findViewById(R.id.chkStrangersOnly)
        chkSpeaker = findViewById(R.id.chkSpeaker)
        chkVoice = findViewById(R.id.chkVoice)
        chkPrivileged = findViewById(R.id.chkPrivileged)

        chkOfflineOnly.isChecked = prefs.getBoolean(KEY_OFFLINE_ONLY, true)
        chkAuto.isChecked = prefs.getBoolean(KEY_AUTO_RECORD, true)
        chkStrangersOnly.isChecked =
            prefs.getString(KEY_AUTO_MODE, AutoRecordPolicy.MODE_ALL) == AutoRecordPolicy.MODE_STRANGERS
        chkSpeaker.isChecked = prefs.getBoolean(KEY_SPEAKER, true)
        chkVoice.isChecked = prefs.getBoolean(KEY_VOICE_COMM, false)
        chkPrivileged.isChecked = prefs.getBoolean(KEY_PRIVILEGED, false)

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
        chkStrangersOnly.setOnCheckedChangeListener { _, c ->
            prefs.edit().putString(
                KEY_AUTO_MODE,
                if (c) AutoRecordPolicy.MODE_STRANGERS else AutoRecordPolicy.MODE_ALL
            ).apply()
            if (c) requestContactsIfNeeded()
            updateUI()
        }
        chkSpeaker.setOnCheckedChangeListener { _, c ->
            prefs.edit().putBoolean(KEY_SPEAKER, c).apply()
        }
        chkVoice.setOnCheckedChangeListener { _, c ->
            prefs.edit().putBoolean(KEY_VOICE_COMM, c).apply()
        }
        chkPrivileged.setOnCheckedChangeListener { _, c ->
            prefs.edit().putBoolean(KEY_PRIVILEGED, c).apply()
            updateUI()
        }

        btnPerms.setOnClickListener { requestPermissions() }
        btnBattery.setOnClickListener { openBatterySettings() }
        btnGetShizuku.setOnClickListener { openShizukuDownload() }
        btnOpenShizuku.setOnClickListener { openShizukuApp() }
        btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        btnTest.setOnClickListener { testMicrophone() }

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
                    putExtra(CallRecorderService.EXTRA_OFFLINE_ONLY, chkOfflineOnly.isChecked || !chkPrivileged.isChecked)
                }
            )
        }

        btnStop.setOnClickListener {
            startService(
                Intent(this, CallRecorderService::class.java).apply {
                    action = CallRecorderService.ACTION_STOP
                }
            )
            Toast.makeText(this, "Stopped. Open the player.", Toast.LENGTH_SHORT).show()
        }

        RecordingRepository.applyRetention(this)
        if (!checkPermissions()) requestPermissions()
        else if (chkAuto.isChecked) CallMonitorService.start(this)
        updateUI()
    }

    private fun testMicrophone() {
        if (!checkPermissions()) {
            requestPermissions()
            return
        }
        try {
            testRecorder?.release()
        } catch (_: Exception) {
        }
        val file = File(RecordingStorage.safeAppDir(this), RecordingStorage.newFileName("MicTest", "room", "m4a"))
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        testRecorder = mr
        try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(44100)
            mr.setAudioEncodingBitRate(128000)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            btnTest.isEnabled = false
            btnTest.text = "Listening 4 seconds — speak now"
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    mr.stop()
                } catch (_: Exception) {
                }
                try {
                    mr.release()
                } catch (_: Exception) {
                }
                testRecorder = null
                btnTest.isEnabled = true
                btnTest.text = "Test microphone (4 seconds)"
                if (file.exists() && file.length() > 500) {
                    RecordingStorage.publishToLibrary(this, file)
                    startActivity(Intent(this, HistoryActivity::class.java).putExtra("play", file.absolutePath))
                } else {
                    Toast.makeText(this, "Mic test file was empty. Grant microphone permission.", Toast.LENGTH_LONG).show()
                }
            }, 4000)
        } catch (e: Exception) {
            Toast.makeText(this, "Mic test failed: ${e.message}", Toast.LENGTH_LONG).show()
            btnTest.isEnabled = true
        }
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
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
            needed.add(Manifest.permission.READ_MEDIA_AUDIO)
        }
        if (chkStrangersOnly.isChecked) needed.add(Manifest.permission.READ_CONTACTS)
        permissionLauncher.launch(needed.toTypedArray())
    }

    private fun requestContactsIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
        }
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
                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/thedjchi/Shizuku/releases"))
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
        val permsOk = checkPermissions()
        val batteryOk = isBatteryUnrestricted()
        val auto = prefs.getBoolean(KEY_AUTO_RECORD, true)
        val count = RecordingStorage.listPlayable(this).size
        fun mark(ok: Boolean, label: String) = (if (ok) "[OK] " else "[ ] ") + label
        checklistText.text = buildString {
            appendLine(mark(permsOk, "Microphone and phone"))
            appendLine(mark(batteryOk, "Battery unrestricted"))
            appendLine(mark(auto, "Auto-record in and out"))
            appendLine(mark(chkSpeaker.isChecked, "Speaker while recording"))
            appendLine("Recordings saved: $count")
        }
        statusText.text = if (permsOk) "Ready" else "Allow the microphone"
        val oplus = OplusRecorderBridge(this).isAvailable()
        infoText.text = "Player is the next button. Test microphone first, then a real call.\nOplus present: $oplus\nTurn media volume up before playing."
        btnShizuku.isEnabled = chkPrivileged.isChecked
        btnGetShizuku.isEnabled = chkPrivileged.isChecked
        btnOpenShizuku.isEnabled = chkPrivileged.isChecked
    }

    companion object {
        const val PREFS = "solid_prefs"
        const val KEY_OFFLINE_ONLY = "offline_only"
        const val KEY_AUTO_RECORD = "auto_record"
        const val KEY_AUTO_MODE = "auto_mode"
        const val KEY_RETENTION_DAYS = "retention_days"
        const val KEY_MAX_RECORDINGS = "max_recordings"
        const val KEY_SPEAKER = "speaker_while_recording"
        const val KEY_VOICE_COMM = "voice_comm_source"
        const val KEY_PRIVILEGED = "prefer_privileged"
    }
}
