package com.solid.callrecorder.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.solid.callrecorder.ui.MainActivity

/**
 * Backup trigger. Primary path is CallMonitorService TelephonyCallback.
 * On PHONE_STATE, ensure monitor is running so OFFHOOK is handled there.
 */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(MainActivity.KEY_AUTO_RECORD, true)) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        Log.d(TAG, "broadcast state=$state action=${intent.action}")

        // Keep monitor alive; it owns start/stop recording
        try {
            CallMonitorService.start(context)
        } catch (e: Exception) {
            Log.e(TAG, "ensure monitor failed", e)
        }
    }

    companion object {
        private const val TAG = "CallStateReceiver"
    }
}
