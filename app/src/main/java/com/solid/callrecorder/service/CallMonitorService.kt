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
import com.solid.callrecorder.recorder.CallRecorder
import com.solid.callrecorder.ui.MainActivity
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps a silent FGS alive, listens for call state, and records on OFFHOOK
 * directly (no second service) so auto-record is reliable.
 */
class CallMonitorService : Service() {

    private var telephonyManager: TelephonyManager? = null
    private var callback: TelephonyCallback? = null
    private var legacyListener: PhoneStateListener? = null
    private val inCall = AtomicBoolean(false)
    private var recorder: CallRecorder? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        recorder = CallRecorder(this)
        if (!startAsForegroundSafe()) {
            Log.e(TAG, "Could not enter foreground – auto-record disabled")
            stopSelf()
            return
        }
        registerCallCallback()
        Log.i(TAG, "Monitor armed")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENSURE -> {
                if (!autoEnabled()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        if (!autoEnabled()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun autoEnabled(): Boolean =
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_AUTO_RECORD, true)

    private fun startAsForegroundSafe(): Boolean {
        val n = buildMonitorNotification("Auto-record on – waiting for calls")
        val attempts = mutableListOf<Int>()
        if (Build.VERSION.SDK_INT >= 34) {
            attempts += ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            attempts += ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            attempts += 0 // plain
        }

        for (type in attempts.distinct()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type != 0) {
                    ServiceCompat.startForeground(this, NOTIF_ID, n, type)
                } else {
                    startForeground(NOTIF_ID, n)
                }
                Log.i(TAG, "Foreground OK type=$type")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "startForeground type=$type failed: ${e.message}")
            }
        }
        return try {
            startForeground(NOTIF_ID, n)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground final fail", e)
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
            .setContentTitle("Call Recorder")
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
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIF_ID, buildMonitorNotification(text))
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
                Log.i(TAG, "TelephonyCallback registered")
            } catch (e: Exception) {
                Log.e(TAG, "registerTelephonyCallback failed", e)
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
            Log.i(TAG, "PhoneStateListener registered")
        }

        // Sync current state (already in call)
        try {
            @Suppress("DEPRECATION")
            val state = telephonyManager?.callState ?: TelephonyManager.CALL_STATE_IDLE
            handleState(state, null)
        } catch (_: Exception) {
        }
    }

    private fun mainExecutorCompat(): Executor {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            mainExecutor
        } else {
            Executor { mainHandler.post(it) }
        }
    }

    private fun handleState(state: Int, number: String?) {
        if (!autoEnabled()) return
        Log.i(TAG, "callState=$state number=$number")
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (inCall.compareAndSet(false, true)) {
                    startRecordingNow(number)
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (inCall.compareAndSet(true, false)) {
                    stopRecordingNow()
                }
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                Log.i(TAG, "RINGING – wait for answer")
            }
        }
    }

    private fun startRecordingNow(number: String?) {
        Log.i(TAG, "OFFHOOK → start recording")
        // Small delay so audio routing is ready after answer/dial connect
        mainHandler.postDelayed({
            if (!inCall.get()) return@postDelayed
            val ok = try {
                recorder?.start(number) == true
            } catch (e: Exception) {
                Log.e(TAG, "recorder.start error", e)
                false
            }
            if (ok) {
                updateNotif("Recording call…")
                Log.i(TAG, "Auto recording started")
            } else {
                Log.e(TAG, "Auto recording FAILED to start")
                updateNotif("Auto-record on – start failed (check mic permission)")
                inCall.set(false)
            }
        }, 800)
    }

    private fun stopRecordingNow() {
        Log.i(TAG, "IDLE → stop recording")
        mainHandler.removeCallbacksAndMessages(null)
        val file = try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "recorder.stop error", e)
            null
        }
        if (file != null) {
            Log.i(TAG, "Saved ${file.absolutePath}")
            updateNotif("Saved ${file.name}")
            mainHandler.postDelayed({
                updateNotif("Auto-record on – waiting for calls")
            }, 4000)
        } else {
            updateNotif("Auto-record on – waiting for calls")
        }
    }

    override fun onDestroy() {
        try {
            if (inCall.get()) recorder?.stop()
        } catch (_: Exception) {
        }
        inCall.set(false)
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
        callback = null
        legacyListener = null
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
                val i = Intent(context, CallMonitorService::class.java).apply {
                    action = ACTION_ENSURE
                }
                context.startForegroundService(i)
                Log.i(TAG, "startForegroundService requested")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start monitor", e)
            }
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, CallMonitorService::class.java))
        }
    }
}
