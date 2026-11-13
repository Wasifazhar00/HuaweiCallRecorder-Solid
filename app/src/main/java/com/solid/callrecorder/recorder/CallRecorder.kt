package com.solid.callrecorder.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Classic path for incoming and outgoing calls.
 *
 * Old bug: MediaRecorder VOICE_CALL often "started" but wrote silence, so MIC
 * was never used. Audio-focus requests also ducked the call, so speakerphone
 * still sounded empty.
 *
 * Now we open the mic and, when the device allows, a second call source, mix
 * them, and apply gain. We do not take audio focus and we do not force speaker.
 */
class CallRecorder(private val context: Context) {

    private var mic: AudioRecord? = null
    private var call: AudioRecord? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private var outFile: File? = null
    private var audioManager: AudioManager? = null
    private var savedMode: Int? = null

    var isRecording = false
        private set
    var activeSource: Int = MediaRecorder.AudioSource.MIC
        private set
    var activeLabel: String = "MIC"
        private set

    fun start(phoneNumber: String? = null): Boolean {
        if (isRecording || running.get()) return false

        val dir = RecordingStorage.publicRecordingsDir() ?: RecordingStorage.safeAppDir(context)
        val prefix = phoneNumber?.substringBefore('_')?.takeIf {
            it.equals("In", true) || it.equals("Out", true)
        } ?: "Call"
        val number = phoneNumber?.substringAfter('_', phoneNumber)
        outFile = File(dir, RecordingStorage.newFileName(prefix, number, "wav"))

        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        prepareRoute()

        val rate = 16_000
        val ch = AudioFormat.CHANNEL_IN_MONO
        val enc = AudioFormat.ENCODING_PCM_16BIT
        val buf = (AudioRecord.getMinBufferSize(rate, ch, enc).coerceAtLeast(2048) * 2)

        mic = open(MediaRecorder.AudioSource.MIC, rate, ch, enc, buf)
            ?: open(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, ch, enc, buf)
            ?: open(MediaRecorder.AudioSource.CAMCORDER, rate, ch, enc, buf)

        if (mic == null) {
            Log.e(TAG, "Mic failed to open")
            restoreRoute()
            return false
        }

        // Second stream: real both-sides when the OS allows it. Silent if blocked.
        call = open(MediaRecorder.AudioSource.VOICE_CALL, rate, ch, enc, buf)
            ?: open(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, ch, enc, buf)

        activeSource = if (call != null) MediaRecorder.AudioSource.VOICE_CALL else MediaRecorder.AudioSource.MIC
        activeLabel = if (call != null) "MIC+CALL" else "MIC"

        return try {
            mic?.startRecording()
            call?.startRecording()
            running.set(true)
            isRecording = true
            startWriter(rate)
            Log.i(TAG, "Classic started $activeLabel → ${outFile?.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "start", e)
            stop()
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(source: Int, rate: Int, ch: Int, enc: Int, buf: Int): AudioRecord? {
        return try {
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(enc)
                            .setSampleRate(rate)
                            .setChannelMask(ch)
                            .build()
                    )
                    .setBufferSizeInBytes(buf)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioRecord(source, rate, ch, enc, buf)
            }
            if (rec.state == AudioRecord.STATE_INITIALIZED) rec else {
                rec.release()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "open source=$source ${e.message}")
            null
        }
    }

    private fun prepareRoute() {
        val am = audioManager ?: return
        try {
            savedMode = am.mode
            // Keep the call audible. Do not request audio focus (that ducks the far side).
            // Do not force speakerphone.
            if (am.mode == AudioManager.MODE_NORMAL) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
            }
        } catch (e: Exception) {
            Log.w(TAG, "route ${e.message}")
        }
    }

    private fun restoreRoute() {
        try {
            savedMode?.let { audioManager?.mode = it }
        } catch (_: Exception) {
        }
        savedMode = null
    }

    private fun startWriter(rate: Int) {
        val file = outFile ?: return
        thread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                FileOutputStream(file).use { fos ->
                    writeHeader(fos, 0, rate)
                    val micBuf = ShortArray(1024)
                    val callBuf = ShortArray(1024)
                    val out = ByteArray(2048)
                    var total = 0L
                    var peak = 0
                    while (running.get()) {
                        val nMic = mic?.read(micBuf, 0, micBuf.size) ?: 0
                        val nCall = call?.read(callBuf, 0, callBuf.size) ?: 0
                        val n = when {
                            nMic > 0 && nCall > 0 -> min(nMic, nCall)
                            nMic > 0 -> nMic
                            else -> nCall
                        }
                        if (n <= 0) {
                            if (n < 0) break
                            continue
                        }
                        for (i in 0 until n) {
                            val m = if (nMic > i) micBuf[i].toInt() else 0
                            val c = if (nCall > i) callBuf[i].toInt() else 0
                            // Mix, then lift quiet speaker bleed without clipping hard.
                            var s = m + c
                            if (abs(s) in 1..1800) s *= 4
                            else if (abs(s) in 1..6000) s = (s * 2)
                            s = s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            if (abs(s) > peak) peak = abs(s)
                            out[i * 2] = (s and 0xFF).toByte()
                            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                        }
                        fos.write(out, 0, n * 2)
                        total += n * 2
                    }
                    patchHeader(file, total)
                    Log.i(TAG, "wrote bytes=$total peak=$peak")
                }
            } catch (e: Exception) {
                Log.e(TAG, "writer", e)
            }
        }, "ClassicMixWriter").also { it.start() }
    }

    fun stop(): File? {
        if (!isRecording && !running.get()) return null
        isRecording = false
        running.set(false)
        try {
            thread?.join(2500)
        } catch (_: Exception) {
        }
        thread = null
        releaseRec(mic)
        releaseRec(call)
        mic = null
        call = null
        restoreRoute()
        val f = outFile
        outFile = null
        val ok = f != null && f.exists() && f.length() > 44
        Log.i(TAG, "stop ok=$ok bytes=${f?.length()} label=$activeLabel")
        return if (ok) f else null
    }

    private fun releaseRec(r: AudioRecord?) {
        try {
            r?.stop()
        } catch (_: Exception) {
        }
        try {
            r?.release()
        } catch (_: Exception) {
        }
    }

    private fun writeHeader(out: FileOutputStream, dataSize: Long, rate: Int) {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray())
        h.putInt((36 + dataSize).toInt())
        h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray())
        h.putInt(16)
        h.putShort(1)
        h.putShort(1)
        h.putInt(rate)
        h.putInt(rate * 2)
        h.putShort(2)
        h.putShort(16)
        h.put("data".toByteArray())
        h.putInt(dataSize.toInt())
        out.write(h.array())
    }

    private fun patchHeader(file: File, dataSize: Long) {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(4)
                raf.writeInt(Integer.reverseBytes((36 + dataSize).toInt()))
                raf.seek(40)
                raf.writeInt(Integer.reverseBytes(dataSize.toInt()))
            }
        } catch (e: Exception) {
            Log.w(TAG, "header ${e.message}")
        }
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
