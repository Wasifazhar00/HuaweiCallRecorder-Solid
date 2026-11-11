package com.solid.callrecorder.recorder

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Classic MediaRecorder with source fallback ladder.
 * Always writes to app-accessible Music/CallRecordings.
 */
class CallRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    var isRecording = false
        private set

    fun start(phoneNumber: String? = null): Boolean {
        if (isRecording) return false

        outputFile = RecordingStorage.newFile(context, "Call", phoneNumber, "m4a")
        Log.i(TAG, "Will write to ${outputFile!!.absolutePath}")

        val sources = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.VOICE_CALL
        )

        for (source in sources) {
            if (tryStart(source)) {
                isRecording = true
                Log.i(TAG, "Recording with source=$source file=${outputFile!!.name}")
                return true
            }
            // delete empty failed file and recreate path
            try {
                outputFile?.delete()
            } catch (_: Exception) {
            }
            outputFile = RecordingStorage.newFile(context, "Call", phoneNumber, "m4a")
        }

        Log.e(TAG, "All audio sources failed")
        outputFile = null
        return false
    }

    private fun tryStart(source: Int): Boolean {
        releaseRecorderOnly()
        return try {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder = mr
            mr.setAudioSource(source)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioChannels(1)
            mr.setAudioSamplingRate(16_000)
            mr.setAudioEncodingBitRate(64_000)
            mr.setOutputFile(outputFile!!.absolutePath)
            mr.prepare()
            mr.start()
            true
        } catch (e: Exception) {
            Log.w(TAG, "source=$source failed: ${e.message}")
            releaseRecorderOnly()
            false
        }
    }

    fun stop(): File? {
        if (!isRecording) return null
        return try {
            mediaRecorder?.apply {
                try {
                    stop()
                } catch (e: Exception) {
                    Log.w(TAG, "stop(): ${e.message}")
                }
                release()
            }
            mediaRecorder = null
            isRecording = false
            val file = outputFile
            outputFile = null
            if (file != null && file.exists() && file.length() > 0) {
                RecordingStorage.indexInMediaStore(context, file, "audio/mp4")
                Log.i(TAG, "Saved ${file.absolutePath} (${file.length()} bytes)")
                file
            } else {
                Log.w(TAG, "Empty or missing file after stop")
                file?.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stop failed", e)
            release()
            null
        }
    }

    private fun releaseRecorderOnly() {
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
    }

    private fun release() {
        releaseRecorderOnly()
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
