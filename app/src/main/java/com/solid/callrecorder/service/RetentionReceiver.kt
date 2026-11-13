package com.solid.callrecorder.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.solid.callrecorder.data.RecordingRepository

class RetentionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        RecordingRepository.applyRetention(context)
    }
}
