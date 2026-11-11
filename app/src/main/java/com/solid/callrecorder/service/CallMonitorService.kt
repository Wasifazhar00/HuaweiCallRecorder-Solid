package com.solid.callrecorder.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.solid.callrecorder.SolidApp
import com.solid.callrecorder.ui.MainActivity
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Long-lived lightweight monitor. Registers TelephonyCallback so incoming/outgoing
 * calls start/stop CallRecorderService automatically (manifest PHONE_STATE is unreliable).
 */
class CallMonitorService : Service() {

    private var telephonyManager: TelephonyManager? = null
    private var callback: TelephonyCallback? = null
    private val inCall = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        registerCallCallback()
        Log.i(TAG, "Monitor started – auto record armed")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!autoEnabled()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun autoEnabled(): Boolean =
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_AUTO_RECORD, true)

    private fun startAsForeground() {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, SolidApp.CHANNEL_MONITOR)
            .setContentTitle("Call Recorder")
            .setContentText("Auto-record on")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Pre-14: no specialUse – use dataSync or none; microphone only while recording
            startForeground(NOTIF_ID, n)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun registerCallCallback() {
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleState(state)
                }
            }
            callback = cb
            val executor = Executor { it.run() }
            try {
                telephonyManager?.registerTelephonyCallback(executor, cb)
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing READ_PHONE_STATE", e)
            }
        } else {
            @Suppress("DEPRECATION")
            telephonyManager?.listen(
                object : android.telephony.PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleState(state, phoneNumber)
                    }
                },
                android.telephony.PhoneStateListener.LISTEN_CALL_STATE
            )
        }
    }

    private fun handleState(state: Int, number: String? = null) {
        if (!autoEnabled()) return
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (inCall.compareAndSet(false, true)) {
                    Log.i(TAG, "OFFHOOK → start record number=$number")
                    val i = Intent(this, CallRecorderService::class.java).apply {
                        action = CallRecorderService.ACTION_START
                        putExtra(CallRecorderService.EXTRA_NUMBER, number)
                    }
                    startForegroundService(i)
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (inCall.compareAndSet(true, false)) {
                    Log.i(TAG, "IDLE → stop record")
                    startService(
                        Intent(this, CallRecorderService::class.java).apply {
                            action = CallRecorderService.ACTION_STOP
                        }
                    )
                }
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                // wait for answer (OFFHOOK)
            }
        }
    }

    override fun onDestroy() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                callback?.let { telephonyManager?.unregisterTelephonyCallback(it) }
            }
        } catch (_: Exception) {}
        callback = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CallMonitor"
        private const val NOTIF_ID = 1000

        fun start(context: android.content.Context) {
            val prefs = context.getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            if (!prefs.getBoolean(MainActivity.KEY_AUTO_RECORD, true)) return
            context.startForegroundService(Intent(context, CallMonitorService::class.java))
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, CallMonitorService::class.java))
        }
    }
}
