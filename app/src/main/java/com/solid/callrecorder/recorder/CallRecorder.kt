package com.solid.callrecorder.recorder

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.solid.callrecorder.ui.MainActivity
import java.io.File

/**
 * Classic call capture.
 * Does not change audio mode (that was muting the call).
 * Does not request audio focus (that was ducking it).
 * Optional speaker so the far side is in the microphone.
 */
class CallRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var audioManager: AudioManager? = null
    private var outFile: File? = null
    private var savedSpeaker: Boolean? = null

    var isRecording = false
        private set
    var activeSource: Int = MediaRecorder.AudioSource.MIC
        private set
    var activeLabel: String = "MIC"
        private set

    fun start(phoneNumber: String? = null): Boolean {
        if (isRecording) return false
        val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val useSpeaker = prefs.getBoolean(MainActivity.KEY_SPEAKER, true)
        val source = if (prefs.getBoolean(MainActivity.KEY_VOICE_COMM, false)) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.MIC
        }

        val dir = RecordingStorage.safeAppDir(context)
        val prefix = phoneNumber?.substringBefore('_')?.takeIf {
            it.equals("In", true) || it.equals("Out", true)
        } ?: "Call"
        val number = phoneNumber?.substringAfter('_', phoneNumber ?: "unknown")
        outFile = File(dir, RecordingStorage.newFileName(prefix, number, "m4a"))

        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (useSpeaker) speaker(true)

        val started = startRecorder(source) || (source != MediaRecorder.AudioSource.MIC && startRecorder(MediaRecorder.AudioSource.MIC))
        if (!started) {
            speaker(false)
            return false
        }
        isRecording = true
        Log.i(TAG, "started $activeLabel speaker=$useSpeaker ${outFile?.name}")
        return true
    }

    private fun startRecorder(source: Int): Boolean {
        releaseRecorder()
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
            mr.setAudioSamplingRate(44_100)
            mr.setAudioEncodingBitRate(128_000)
            mr.setOutputFile(outFile!!.absolutePath)
            mr.prepare()
            mr.start()
            activeSource = source
            activeLabel = if (source == MediaRecorder.AudioSource.MIC) "MIC" else "VOICE_COMM"
            true
        } catch (e: Exception) {
            Log.w(TAG, "source $source ${e.message}")
            releaseRecorder()
            false
        }
    }

    private fun speaker(on: Boolean) {
        val am = audioManager ?: return
        try {
            if (savedSpeaker == null) savedSpeaker = am.isSpeakerphoneOn
            if (on) {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            } else {
                savedSpeaker?.let {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = it
                }
                savedSpeaker = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "speaker ${e.message}")
        }
    }

    fun stop(): File? {
        if (!isRecording) return null
        isRecording = false
        releaseRecorder()
        speaker(false)
        val file = outFile
        outFile = null
        if (file == null || !file.exists() || file.length() < 500) return null
        RecordingStorage.publishToLibrary(context, file)
        return file
    }

    private fun releaseRecorder() {
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
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
