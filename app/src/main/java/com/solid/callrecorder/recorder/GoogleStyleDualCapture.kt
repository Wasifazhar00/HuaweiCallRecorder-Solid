package com.solid.callrecorder.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
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
import kotlin.math.min

/**
 * Inspired by Google Dialer uplink/downlink tee model.
 * Uses AudioRecord VOICE_UPLINK + VOICE_DOWNLINK (or VOICE_CALL).
 * Works best under Shizuku/shell; tried here for any process that is allowed.
 * No disclosure beep is played.
 */
class GoogleStyleDualCapture(private val context: Context) {

    private var up: AudioRecord? = null
    private var down: AudioRecord? = null
    private var single: AudioRecord? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private var outFile: File? = null

    fun start(phoneNumber: String?): Boolean {
        if (running.get()) return false
        val dir = RecordingStorage.publicRecordingsDir() ?: RecordingStorage.safeAppDir(context)
        outFile = File(dir, RecordingStorage.newFileName("GDual", phoneNumber, "wav"))

        val rate = 16_000
        val ch = AudioFormat.CHANNEL_IN_MONO
        val enc = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(rate, ch, enc).coerceAtLeast(2048) * 2

        // Try dual streams (Google-style)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            up = build(MediaRecorder.AudioSource.VOICE_UPLINK, rate, ch, enc, minBuf)
            down = build(MediaRecorder.AudioSource.VOICE_DOWNLINK, rate, ch, enc, minBuf)
            if (up != null && down != null) {
                return beginWriter(rate)
            }
            releaseRecs()
        }

        // Combined VOICE_CALL (Oplus/Google both use source 4)
        single = build(MediaRecorder.AudioSource.VOICE_CALL, rate, ch, enc, minBuf)
            ?: build(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, ch, enc, minBuf)
            ?: build(MediaRecorder.AudioSource.MIC, rate, ch, enc, minBuf)

        return if (single != null) beginWriter(rate) else false
    }

    @SuppressLint("MissingPermission")
    private fun build(source: Int, rate: Int, ch: Int, enc: Int, buf: Int): AudioRecord? {
        return try {
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setEncoding(enc)
                            .setChannelMask(ch)
                            .build()
                    )
                    .setBufferSizeInBytes(buf)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioRecord(source, rate, ch, enc, buf)
            }
            if (r.state == AudioRecord.STATE_INITIALIZED) r else {
                r.release(); null
            }
        } catch (e: Exception) {
            Log.w(TAG, "build source=$source: ${e.message}")
            null
        }
    }

    private fun beginWriter(rate: Int): Boolean {
        return try {
            up?.startRecording()
            down?.startRecording()
            single?.startRecording()
            running.set(true)
            val file = outFile!!
            thread = Thread({
                try {
                    FileOutputStream(file).use { fos ->
                        writeHeader(fos, 0, rate, 1, 16)
                        val buf = ByteArray(4096)
                        var total = 0L
                        while (running.get()) {
                            val n = readMixed(buf)
                            if (n > 0) {
                                // Soft gain x2 to fight quiet offline captures
                                boost(buf, n, 2)
                                fos.write(buf, 0, n)
                                total += n
                            } else if (n < 0) break
                        }
                        patchHeader(file, total)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "writer", e)
                }
            }, "GDualWriter").also { it.start() }
            Log.i(TAG, "started dual=${up != null && down != null}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "begin", e)
            stop()
            false
        }
    }

    private fun readMixed(buffer: ByteArray): Int {
        val u = up
        val d = down
        if (u != null && d != null) {
            val ub = ByteArray(2048)
            val db = ByteArray(2048)
            val ur = u.read(ub, 0, ub.size)
            val dr = d.read(db, 0, db.size)
            val n = min(ur, dr)
            if (n <= 0) return n
            for (i in 0 until n step 2) {
                if (i + 1 >= n) break
                val us = (ub[i].toInt() and 0xFF) or (ub[i + 1].toInt() shl 8)
                val ds = (db[i].toInt() and 0xFF) or (db[i + 1].toInt() shl 8)
                val m = ((us.toShort() + ds.toShort()) / 2).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                buffer[i] = (m and 0xFF).toByte()
                buffer[i + 1] = ((m shr 8) and 0xFF).toByte()
            }
            return n
        }
        return single?.read(buffer, 0, buffer.size) ?: -1
    }

    private fun boost(buf: ByteArray, n: Int, factor: Int) {
        for (i in 0 until n step 2) {
            if (i + 1 >= n) break
            var s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            s = (s.toShort() * factor).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buf[i] = (s and 0xFF).toByte()
            buf[i + 1] = ((s shr 8) and 0xFF).toByte()
        }
    }

    fun stop(): File? {
        if (!running.getAndSet(false)) return null
        try {
            thread?.join(2000)
        } catch (_: Exception) {
        }
        thread = null
        releaseRecs()
        val f = outFile
        outFile = null
        return f?.takeIf { it.exists() && it.length() > 44 }
    }

    private fun releaseRecs() {
        try {
            up?.apply { stop(); release() }
            down?.apply { stop(); release() }
            single?.apply { stop(); release() }
        } catch (_: Exception) {
        }
        up = null
        down = null
        single = null
    }

    private fun writeHeader(out: FileOutputStream, dataSize: Long, rate: Int, ch: Int, bits: Int) {
        val br = rate * ch * bits / 8
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray())
        h.putInt((36 + dataSize).toInt())
        h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray())
        h.putInt(16)
        h.putShort(1)
        h.putShort(ch.toShort())
        h.putInt(rate)
        h.putInt(br)
        h.putShort((ch * bits / 8).toShort())
        h.putShort(bits.toShort())
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
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "GoogleStyleDual"
    }
}
