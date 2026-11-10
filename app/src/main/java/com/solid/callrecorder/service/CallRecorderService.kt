package com.solid.callrecorder.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.solid.callrecorder.SolidApp
import com.solid.callrecorder.recorder.CallRecorder
import com.solid.callrecorder.recorder.ShizukuEnhancedRecorder
import com.solid.callrecorder.ui.MainActivity

/**
 * Foreground service – prefers Enhanced (Shizuku), falls back to Classic.
 * Optimized for low battery and silent operation (no tones, minimal notification).
 */
class CallRecorderService : Service() {

    private lateinit var classicRecorder: CallRecorder
    private lateinit var enhancedRecorder: ShizukuEnhancedRecorder
    private var usingEnhanced = false
    private var currentNumber: String? = null
    private var activeStrategyLabel: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isActive = false

    override fun onCreate() {
        super.onCreate()
        classicRecorder = CallRecorder(this)
        enhancedRecorder = ShizukuEnhancedRecorder(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (isActive) {
                    Log.d(TAG, "Already recording – ignore duplicate START")
                    return START_NOT_STICKY
                }
                currentNumber = intent.getStringExtra(EXTRA_NUMBER)
                startRecording()
            }
            ACTION_STOP -> {
                if (isActive) stopRecording()
                else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        // NOT_STICKY: do not restart after kill when idle – saves battery
        return START_NOT_STICKY
    }

    private fun startRecording() {
        promoteForeground("Recording")

        val started = if (enhancedRecorder.isAvailable()) {
            usingEnhanced = true
            Log.i(TAG, "Enhanced (Shizuku) path")
            enhancedRecorder.start(currentNumber).also {
                if (it) {
                    activeStrategyLabel = enhancedRecorder.lastStrategyLabel()
                    Log.i(TAG, "Enhanced OK strategy=$activeStrategyLabel")
                }
            }
        } else {
            usingEnhanced = false
            Log.i(TAG, "Classic path")
            classicRecorder.start(currentNumber)
        }

        if (!started && usingEnhanced) {
            Log.w(TAG, "Enhanced failed – Classic fallback")
            usingEnhanced = false
            activeStrategyLabel = null
            if (!classicRecorder.start(currentNumber)) {
                Log.e(TAG, "All paths failed")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
        } else if (!started) {
            Log.e(TAG, "Could not start recorder")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        isActive = true
        acquireWakeLock()
        // Keep notification text short & generic – no strategy noise in UI
        updateNotification("Recording")
    }

    private fun stopRecording() {
        isActive = false
        val file = if (usingEnhanced) {
            enhancedRecorder.stop()
        } else {
            classicRecorder.stop()
        }
        releaseWakeLock()
        Log.i(TAG, "Stopped file=${file?.absolutePath} strategy=$activeStrategyLabel")
        activeStrategyLabel = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, SolidApp.CHANNEL_ID)
            .setContentTitle("Call Recorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            releaseWakeLock()
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SolidCallRecorder:Rec"
            ).apply {
                setReferenceCounted(false)
                // Cap at 3 hours – released on call end anyway
                acquire(3 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock failed", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onDestroy() {
        if (isActive) {
            try {
                if (usingEnhanced) enhancedRecorder.stop() else classicRecorder.stop()
            } catch (_: Exception) {}
            isActive = false
        }
        releaseWakeLock()
        enhancedRecorder.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.solid.callrecorder.START"
        const val ACTION_STOP = "com.solid.callrecorder.STOP"
        const val EXTRA_NUMBER = "number"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "CallRecorderService"
    }
}
