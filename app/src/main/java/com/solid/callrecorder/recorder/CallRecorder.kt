package com.solid.callrecorder.recorder

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Classic MediaRecorder path (no tones, no announcement).
 * For true both-sides use Enhanced (Shizuku) path.
 */
class CallRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    var isRecording = false
        private set

    fun start(phoneNumber: String? = null): Boolean {
        if (isRecording) return false

        val recordingsDir = File(context.getExternalFilesDir(null), "CallRecordings")
        if (!recordingsDir.exists()) recordingsDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeNumber = phoneNumber?.replace(Regex("[^0-9+]"), "") ?: "unknown"
        outputFile = File(recordingsDir, "Call_${safeNumber}_$timestamp.m4a")

        return try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                val source = AudioSourceSelector.getBestSource()
                Log.i(TAG, "Audio source: ${AudioSourceSelector.getSourceName(source)}")
                setAudioSource(source)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // 96 kbps is enough for voice and uses less CPU/battery than 128k
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(16_000)
                setOutputFile(outputFile!!.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Start failed", e)
            release()
            false
        }
    }

    fun stop(): File? {
        if (!isRecording) return null
        return try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Stop failed", e)
            release()
            null
        }
    }

    private fun release() {
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        isRecording = false
    }

    companion object {
        private const val TAG = "CallRecorder"
    }
}

interface EnhancedRecorder {
    fun isAvailable(): Boolean
    fun start(phoneNumber: String?): Boolean
    fun stop(): File?
}
