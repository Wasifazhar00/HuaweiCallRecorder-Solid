package com.solid.callrecorder.recorder

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Classic MediaRecorder – AAC M4A, voice-optimized (small + clear).
 * No tones / announcements.
 */
class CallRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    var isRecording = false
        private set

    fun start(phoneNumber: String? = null): Boolean {
        if (isRecording) return false

        // Voice: 16 kHz mono AAC ~64 kbps ≈ 0.5 MB per minute, clearer than AMR
        outputFile = RecordingStorage.newFile(context, "Call", phoneNumber, "m4a")

        return try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                val source = AudioSourceSelector.getBestSource()
                Log.i(TAG, "source=${AudioSourceSelector.getSourceName(source)} file=${outputFile!!.name}")
                setAudioSource(source)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(64_000)
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
            outputFile?.also {
                RecordingStorage.indexInMediaStore(context, it, "audio/mp4")
                Log.i(TAG, "Saved ${it.absolutePath} size=${it.length()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stop failed", e)
            release()
            null
        }
    }

    private fun release() {
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
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
