package com.solid.callrecorder.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.RemoteException
import android.util.Log
import com.solid.callrecorder.IShellRecorder
import com.solid.callrecorder.recorder.RecordingStrategy
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shizuku shell recorder.
 * Keeps the first source that actually enters RECORDSTATE_RECORDING.
 * Does not reject a quiet first half-second (that was dropping VOICE_CALL).
 * Audio is written through the app's file descriptor and the WAV header is
 * patched on that same descriptor so the file is playable.
 */
class ShellRecorderUserService : IShellRecorder.Stub {

    private val recording = AtomicBoolean(false)
    private var uplinkRecord: AudioRecord? = null
    private var downlinkRecord: AudioRecord? = null
    private var singleRecord: AudioRecord? = null
    private var writeThread: Thread? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var statusMessage: String = "idle"
    private var activeStrategy: RecordingStrategy? = null
    private var shellContext: Context? = null
    private var wakeLock: PowerManager.WakeLock? = null

    constructor(context: Context) {
        val base = obtainSystemContext(context)
        shellContext = WrappedShellContext(base)
        Log.i(TAG, "ShellRecorderUserService uid=${android.os.Process.myUid()}")
    }

    constructor() : this(
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val systemMain = atClass.getMethod("systemMain").invoke(null)
            atClass.getMethod("getSystemContext").invoke(systemMain) as Context
        } catch (e: Throwable) {
            throw RuntimeException("Cannot create ShellRecorderUserService without context", e)
        }
    )

    private fun obtainSystemContext(fallback: Context): Context {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val systemMain = atClass.getMethod("systemMain").invoke(null)
            atClass.getMethod("getSystemContext").invoke(systemMain) as Context
        } catch (e: Throwable) {
            Log.w(TAG, "system context failed", e)
            fallback.applicationContext
        }
    }

    @Throws(RemoteException::class)
    override fun start(output: ParcelFileDescriptor?, phoneNumber: String?): Boolean {
        if (recording.get()) return false
        val ctx = shellContext ?: return false
        if (output == null) return false
        outputPfd = output
        activeStrategy = null

        val audioMode = try {
            (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode
        } catch (_: Exception) {
            null
        }
        val voip = audioMode == AudioManager.MODE_IN_COMMUNICATION
        Log.i(TAG, "start voip=$voip number=$phoneNumber")

        val latch = CountDownLatch(1)
        var success = false
        Handler(Looper.getMainLooper()).post {
            try {
                success = runStrategyLadder(ctx, voip)
            } catch (e: Throwable) {
                Log.e(TAG, "ladder", e)
                statusMessage = "error: ${e.message}"
                success = false
            } finally {
                latch.countDown()
            }
        }
        latch.await(6, TimeUnit.SECONDS)

        if (success) {
            recording.set(true)
            acquireWakeLock(ctx)
            startWriterThread()
            statusMessage = activeStrategy?.label ?: "recording"
            Log.i(TAG, "SUCCESS ${activeStrategy?.label}")
        } else {
            statusMessage = "all strategies failed"
            try {
                output.close()
            } catch (_: Exception) {
            }
            outputPfd = null
        }
        return success
    }

    private fun runStrategyLadder(ctx: Context, voip: Boolean): Boolean {
        val order = if (voip) {
            listOf(
                RecordingStrategy.VOICE_COMMUNICATION,
                RecordingStrategy.VOICE_CALL,
                RecordingStrategy.DUAL_UPLINK_DOWNLINK,
                RecordingStrategy.MIC_ONLY
            )
        } else {
            listOf(
                RecordingStrategy.VOICE_CALL,
                RecordingStrategy.DUAL_UPLINK_DOWNLINK,
                RecordingStrategy.VOICE_COMMUNICATION,
                RecordingStrategy.MIC_ONLY
            )
        }
        for (strategy in order) {
            val ok = when (strategy) {
                RecordingStrategy.DUAL_UPLINK_DOWNLINK -> tryDual(ctx)
                RecordingStrategy.MIC_PLUS_DOWNLINK -> false
                RecordingStrategy.VOICE_CALL -> trySingle(ctx, MediaRecorder.AudioSource.VOICE_CALL)
                RecordingStrategy.VOICE_COMMUNICATION -> trySingle(ctx, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                RecordingStrategy.MIC_ONLY -> trySingle(ctx, MediaRecorder.AudioSource.MIC)
            }
            if (ok) {
                activeStrategy = strategy
                return true
            }
            releaseAllRecords()
        }
        return false
    }

    private fun tryDual(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            val (sampleRate, channelConfig, encoding, bufSize) = audioParams()
            val up = buildRecord(ctx, MediaRecorder.AudioSource.VOICE_UPLINK, sampleRate, channelConfig, encoding, bufSize)
            val down = buildRecord(ctx, MediaRecorder.AudioSource.VOICE_DOWNLINK, sampleRate, channelConfig, encoding, bufSize)
            if (up == null || down == null) {
                up?.release(); down?.release()
                return false
            }
            up.startRecording()
            down.startRecording()
            val upOk = up.recordingState == AudioRecord.RECORDSTATE_RECORDING
            val downOk = down.recordingState == AudioRecord.RECORDSTATE_RECORDING
            if (!upOk || !downOk) {
                try { up.stop() } catch (_: Exception) {}
                try { down.stop() } catch (_: Exception) {}
                up.release(); down.release()
                return false
            }
            uplinkRecord = up
            downlinkRecord = down
            true
        } catch (e: Throwable) {
            Log.w(TAG, "dual ${e.message}")
            false
        }
    }

    private fun trySingle(ctx: Context, source: Int): Boolean {
        return try {
            val (sampleRate, channelConfig, encoding, bufSize) = audioParams()
            val rec = buildRecord(ctx, source, sampleRate, channelConfig, encoding, bufSize) ?: return false
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                rec.release()
                return false
            }
            singleRecord = rec
            true
        } catch (e: Throwable) {
            Log.w(TAG, "single $source ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildRecord(
        ctx: Context,
        source: Int,
        sampleRate: Int,
        channelConfig: Int,
        encoding: Int,
        bufSize: Int
    ): AudioRecord? {
        return try {
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioRecord.Builder()
                    .setContext(ctx)
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(bufSize)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioRecord(source, sampleRate, channelConfig, encoding, bufSize)
            }
            if (rec.state == AudioRecord.STATE_INITIALIZED) rec else {
                rec.release()
                null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "build $source ${e.message}")
            null
        }
    }

    private fun audioParams(): Quad {
        val sampleRate = SAMPLE_RATE
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        val bufSize = (minBuf * 2).coerceAtLeast(4096)
        return Quad(sampleRate, channelConfig, encoding, bufSize)
    }

    private data class Quad(val a: Int, val b: Int, val c: Int, val d: Int)

    private fun startWriterThread() {
        writeThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val pfd = outputPfd ?: return@Thread
            try {
                FileOutputStream(pfd.fileDescriptor).use { fos ->
                    writeWavHeader(fos, 0)
                    val buffer = ByteArray(4096)
                    var total = 0L
                    while (recording.get()) {
                        val n = readMixed(buffer)
                        if (n > 0) {
                            fos.write(buffer, 0, n)
                            total += n
                        } else if (n < 0) {
                            break
                        }
                    }
                    fos.flush()
                    patchHeader(fos, total)
                    Log.i(TAG, "wrote bytes=$total")
                }
            } catch (e: Exception) {
                Log.e(TAG, "writer", e)
            }
        }, "ShellRecorderWriter").also { it.start() }
    }

    private fun readMixed(buffer: ByteArray): Int {
        val up = uplinkRecord
        val down = downlinkRecord
        if (up != null && down != null) {
            val upBuf = ByteArray(2048)
            val downBuf = ByteArray(2048)
            val upRead = up.read(upBuf, 0, upBuf.size)
            val downRead = down.read(downBuf, 0, downBuf.size)
            val n = minOf(upRead, downRead)
            if (n <= 0) return n
            var i = 0
            while (i + 1 < n) {
                val u = (upBuf[i].toInt() and 0xFF) or (upBuf[i + 1].toInt() shl 8)
                val d = (downBuf[i].toInt() and 0xFF) or (downBuf[i + 1].toInt() shl 8)
                val mixed = ((u.toShort() + d.toShort()) / 2).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                buffer[i] = (mixed and 0xFF).toByte()
                buffer[i + 1] = ((mixed shr 8) and 0xFF).toByte()
                i += 2
            }
            return n
        }
        return singleRecord?.read(buffer, 0, buffer.size) ?: 0
    }

    @Throws(RemoteException::class)
    override fun stop(): String? {
        if (!recording.getAndSet(false)) return null
        releaseAllRecords()
        try {
            writeThread?.join(2500)
        } catch (_: Exception) {
        }
        writeThread = null
        releaseWakeLock()
        try {
            outputPfd?.close()
        } catch (_: Exception) {
        }
        outputPfd = null
        statusMessage = "stopped [${activeStrategy?.label ?: "none"}]"
        return statusMessage
    }

    private fun releaseAllRecords() {
        listOf(uplinkRecord, downlinkRecord, singleRecord).forEach { rec ->
            try {
                rec?.stop()
            } catch (_: Exception) {
            }
            try {
                rec?.release()
            } catch (_: Exception) {
            }
        }
        uplinkRecord = null
        downlinkRecord = null
        singleRecord = null
    }

    private fun acquireWakeLock(ctx: Context) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SolidCallRecorder:ShellRec").apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "wakelock ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    @Throws(RemoteException::class)
    override fun isRecording(): Boolean = recording.get()

    @Throws(RemoteException::class)
    override fun getStatus(): String = statusMessage

    @Throws(RemoteException::class)
    override fun getActiveStrategy(): String = activeStrategy?.label ?: ""

    private fun writeWavHeader(out: FileOutputStream, dataSize: Long) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt((36 + dataSize).toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(dataSize.toInt())
        out.write(header.array())
    }

    private fun patchHeader(out: FileOutputStream, dataSize: Long) {
        try {
            val ch = out.channel
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            ch.position(4)
            buf.putInt((36 + dataSize).toInt())
            buf.flip()
            ch.write(buf)
            buf.clear()
            ch.position(40)
            buf.putInt(dataSize.toInt())
            buf.flip()
            ch.write(buf)
            ch.force(true)
        } catch (e: Exception) {
            Log.w(TAG, "patch ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ShellRecorderUS"
        private const val SAMPLE_RATE = 16_000
    }
}
