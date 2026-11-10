package com.solid.callrecorder.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.solid.callrecorder.SolidApp
import com.solid.callrecorder.recorder.CallRecorder
import com.solid.callrecorder.recorder.ShizukuEnhancedRecorder
import com.solid.callrecorder.ui.MainActivity

class CallRecorderService : Service() {

    private lateinit var classicRecorder: CallRecorder
    private lateinit var enhancedRecorder: ShizukuEnhancedRecorder
    private var usingEnhanced = false
    private var currentNumber: String? = null

    override fun onCreate() {
        super.onCreate()
        classicRecorder = CallRecorder(this)
        enhancedRecorder = ShizukuEnhancedRecorder(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentNumber = intent.getStringExtra(EXTRA_NUMBER)

                // Prefer enhanced (Shizuku) path when available
                val started = if (enhancedRecorder.isAvailable()) {
                    usingEnhanced = true
                    Log.i(TAG, "Using Enhanced (Shizuku) path")
                    enhancedRecorder.start(currentNumber)
                } else {
                    usingEnhanced = false
                    Log.i(TAG, "Using Classic MediaRecorder path")
                    classicRecorder.start(currentNumber)
                }

                if (started) {
                    val mode = if (usingEnhanced) "Enhanced" else "Classic"
                    startForeground(NOTIFICATION_ID, buildNotification("Recording ($mode)…"))
                    Log.i(TAG, "Recording started for $currentNumber ($mode)")
                } else {
                    // If enhanced failed, try classic as fallback
                    if (usingEnhanced) {
                        Log.w(TAG, "Enhanced start failed, falling back to classic")
                        usingEnhanced = false
                        if (classicRecorder.start(currentNumber)) {
                            startForeground(NOTIFICATION_ID, buildNotification("Recording (Classic)…"))
                        } else {
                            stopSelf()
                        }
                    } else {
                        stopSelf()
                    }
                }
            }
            ACTION_STOP -> {
                val file = if (usingEnhanced) {
                    enhancedRecorder.stop()
                } else {
                    classicRecorder.stop()
                }
                Log.i(TAG, "Recording stopped. File: ${file?.absolutePath}")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, SolidApp.CHANNEL_ID)
            .setContentTitle("Solid Call Recorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
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
