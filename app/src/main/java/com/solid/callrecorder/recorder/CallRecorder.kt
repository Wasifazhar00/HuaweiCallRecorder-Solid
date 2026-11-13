package com.solid.callrecorder.recorder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Classic MediaRecorder – VOICE_CALL (4) first, then fallbacks.
 * Matches Oplus audit source priority for two-way when permitted.
 */
class CallRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var output: RecordingStorage.OutputTarget? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    var isRecording = false
        private set
    var activeSource: Int = -1
        private set

    fun start(phoneNumber: String? = null): Boolean {
        if (isRecording) return false

        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        requestFocus()

        // Integer 4 = VOICE_CALL (audit). Prefer this, then communication, then mic.
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_CALL,           // 4 – both sides if allowed
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, // VoIP / in-call path
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.CAMCORDER
        )

        for (source in sources) {
            // Try speech profile first (16 kHz like Oplus), then higher quality
            if (tryStart(source, phoneNumber, speechProfile = true) ||
                tryStart(source, phoneNumber, speechProfile = false)
            ) {
                isRecording = true
                activeSource = source
                Log.i(TAG, "OK source=$source (${sourceName(source)}) → ${output?.displayName}")
                return true
            }
            discardFailedOutput()
        }

        abandonFocus()
        Log.e(TAG, "All sources failed")
        return false
    }

    private fun tryStart(source: Int, phoneNumber: String?, speechProfile: Boolean): Boolean {
        releaseRecorderOnly()
        return try {
            output = RecordingStorage.createOutput(context, "Call", phoneNumber, "m4a")
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
            if (speechProfile) {
                // Closer to Oplus 16 kHz path – smaller, speech-focused
                mr.setAudioSamplingRate(16_000)
                mr.setAudioEncodingBitRate(64_000)
            } else {
                mr.setAudioSamplingRate(44_100)
                mr.setAudioEncodingBitRate(96_000)
            }

            when {
                output?.pfd != null -> mr.setOutputFile(output!!.pfd!!.fileDescriptor)
                output?.file != null -> mr.setOutputFile(output!!.file!!.absolutePath)
                else -> return false
            }

            mr.prepare()
            mr.start()
            true
        } catch (e: Exception) {
            Log.w(TAG, "source=$source speech=$speechProfile: ${e.message}")
            releaseRecorderOnly()
            discardFailedOutput()
            false
        }
    }

    fun stop(): File? {
        if (!isRecording) return null
        isRecording = false
        return try {
            mediaRecorder?.apply {
                try {
                    stop()
                } catch (e: Exception) {
                    Log.w(TAG, "stop: ${e.message}")
                }
                release()
            }
            mediaRecorder = null
            RecordingStorage.finalizeOutput(context, output)
            val result = output?.file
            val name = output?.displayName
            output = null
            abandonFocus()
            Log.i(TAG, "Stopped source=$activeSource name=$name")
            result ?: File(RecordingStorage.humanPath(context), name ?: "call.m4a")
        } catch (e: Exception) {
            Log.e(TAG, "Stop failed", e)
            release()
            null
        }
    }

    private fun sourceName(s: Int) = when (s) {
        MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
        else -> "$s"
    }

    private fun requestFocus() {
        try {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .build()
                focusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "focus: ${e.message}")
        }
    }

    private fun abandonFocus() {
        try {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
        }
        focusRequest = null
    }

    private fun discardFailedOutput() {
        try {
            output?.pfd?.close()
            output?.file?.delete()
            output?.uri?.let {
                try {
                    context.contentResolver.delete(it, null, null)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        output = null
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
        discardFailedOutput()
        abandonFocus()
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
