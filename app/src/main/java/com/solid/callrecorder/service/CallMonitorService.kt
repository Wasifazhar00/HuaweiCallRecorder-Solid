package com.solid.callrecorder.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.solid.callrecorder.SolidApp
import com.solid.callrecorder.data.RecordingRepository
import com.solid.callrecorder.recorder.AutoRecordPolicy
import com.solid.callrecorder.recorder.SmartCaptureEngine
import com.solid.callrecorder.ui.MainActivity
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class CallMonitorService : Service() {

    private var telephonyManager: TelephonyManager? = null
    private var callback: TelephonyCallback? = null
    private var legacyListener: PhoneStateListener? = null
    private val inCall = AtomicBoolean(false)
    private var engine: SmartCaptureEngine? = null
    private var lastNumber: String? = null
    private var lastBackend = SmartCaptureEngine.Backend.NONE
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        engine = SmartCaptureEngine(this)
        if (!startAsForegroundSafe()) {
            stopSelf()
            return
        }
        registerCallCallback()
        RecordingRepository.applyRetention(this)
        Log.i(TAG, "Monitor armed")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!autoMasterOn()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun autoMasterOn(): Boolean =
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_AUTO_RECORD, true)

    private fun startAsForegroundSafe(): Boolean {
        val n = buildMonitorNotification("Listening for calls")
        val attempts = mutableListOf<Int>()
        if (Build.VERSION.SDK_INT >= 34) {
            attempts += ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            attempts += ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        attempts += 0
        for (type in attempts.distinct()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type != 0) {
                    ServiceCompat.startForeground(this, NOTIF_ID, n, type)
                } else startForeground(NOTIF_ID, n)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "FGS $type: ${e.message}")
            }
        }
        return try {
            startForeground(NOTIF_ID, n)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun buildMonitorNotification(text: String): Notification {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, SolidApp.CHANNEL_MONITOR)
            .setContentTitle("Solid Call Recorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()
    }

    private fun updateNotif(text: String) {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .notify(NOTIF_ID, buildMonitorNotification(text))
        } catch (_: Exception) {
        }
    }

    private fun registerCallCallback() {
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    mainHandler.post { handleState(state, null) }
                }
            }
            callback = cb
            try {
                telephonyManager?.registerTelephonyCallback(mainExecutorCompat(), cb)
            } catch (e: Exception) {
                Log.e(TAG, "TelephonyCallback", e)
            }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    mainHandler.post { handleState(state, phoneNumber) }
                }
            }
            legacyListener = listener
            @Suppress("DEPRECATION")
            telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun mainExecutorCompat(): Executor {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) mainExecutor
        else Executor { mainHandler.post(it) }
    }

    private fun handleState(state: Int, number: String?) {
        if (!autoMasterOn()) return
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (inCall.compareAndSet(false, true)) startRecordingNow(number)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (inCall.compareAndSet(true, false)) stopRecordingNow()
            }
        }
    }

    private fun startRecordingNow(number: String?) {
        lastNumber = number
        mainHandler.postDelayed({
            if (!inCall.get()) return@postDelayed
            if (!AutoRecordPolicy.shouldRecord(this, number)) {
                updateNotif("Skipped (policy)")
                inCall.set(false)
                mainHandler.postDelayed({ updateNotif("Listening for calls") }, 2500)
                return@postDelayed
            }
            val result = engine?.start(number) ?: SmartCaptureEngine.Result(false, SmartCaptureEngine.Backend.NONE, "no engine")
            if (result.ok) {
                lastBackend = result.backend
                updateNotif("Recording · ${result.detail}")
                Log.i(TAG, "recording ${result.detail}")
            } else {
                lastBackend = SmartCaptureEngine.Backend.NONE
                updateNotif("Start failed")
                inCall.set(false)
                Log.e(TAG, "start failed: ${result.detail}")
            }
        }, 1200)
    }

    private fun stopRecordingNow() {
        mainHandler.removeCallbacksAndMessages(null)
        val (file, backend) = engine?.stop() ?: (null to SmartCaptureEngine.Backend.NONE)
        if (file != null) {
            RecordingRepository.onRecordingFinished(
                this, file.absolutePath, lastNumber, backend.name.lowercase()
            )
            updateNotif("Saved ${file.name}")
        } else {
            updateNotif("Listening for calls")
        }
        lastNumber = null
        lastBackend = SmartCaptureEngine.Backend.NONE
        mainHandler.postDelayed({ updateNotif("Listening for calls") }, 3500)
    }

    override fun onDestroy() {
        try {
            if (inCall.get()) engine?.stop()
        } catch (_: Exception) {
        }
        inCall.set(false)
        engine?.release()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                callback?.let { telephonyManager?.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                legacyListener?.let {
                    telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CallMonitor"
        private const val NOTIF_ID = 1000
        const val ACTION_ENSURE = "com.solid.callrecorder.ENSURE_MONITOR"

        fun start(context: android.content.Context) {
            val prefs = context.getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            if (!prefs.getBoolean(MainActivity.KEY_AUTO_RECORD, true)) return
            try {
                context.startForegroundService(
                    Intent(context, CallMonitorService::class.java).apply { action = ACTION_ENSURE }
                )
            } catch (e: Exception) {
                Log.e(TAG, "start", e)
            }
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, CallMonitorService::class.java))
        }
    }
}
