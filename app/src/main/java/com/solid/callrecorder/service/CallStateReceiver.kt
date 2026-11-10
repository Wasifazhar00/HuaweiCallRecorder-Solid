package com.solid.callrecorder.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            ?: intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)

        Log.d(TAG, "state=$state number=$number")

        when (state) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // Start only when call is connected – no ring-time recording
                val start = Intent(context, CallRecorderService::class.java).apply {
                    action = CallRecorderService.ACTION_START
                    putExtra(CallRecorderService.EXTRA_NUMBER, number)
                }
                context.startForegroundService(start)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val stop = Intent(context, CallRecorderService::class.java).apply {
                    action = CallRecorderService.ACTION_STOP
                }
                context.startService(stop)
            }
            // RINGING: do nothing (saves battery, no false starts)
        }
    }

    companion object {
        private const val TAG = "CallStateReceiver"
    }
}
