package com.solid.callrecorder.recorder

import android.media.MediaRecorder
import android.os.Build

/**
 * Tries the best available AudioSource for call recording.
 * On most modern devices VOICE_CALL is blocked for normal apps.
 */
object AudioSourceSelector {

    fun getBestSource(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return MediaRecorder.AudioSource.VOICE_CALL
        }
        return try {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } catch (e: Exception) {
            MediaRecorder.AudioSource.MIC
        }
    }

    fun getSourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL (both sides if allowed)"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.MIC -> "MIC (local side only)"
        else -> "Unknown ($source)"
    }
}
