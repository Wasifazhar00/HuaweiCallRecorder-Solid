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
import android.os.PowerManager
import android.os.RemoteException
import android.util.Log
import com.solid.callrecorder.IShellRecorder
import com.solid.callrecorder.recorder.RecordingStrategy
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Privileged recorder (Shizuku shell UID).
 * 16 kHz mono PCM WAV – smaller than 44.1 kHz, still clear for speech.
 */
class ShellRecorderUserService : IShellRecorder.Stub {

    private val recording = AtomicBoolean(false)
    private var uplinkRecord: AudioRecord? = null
    private var downlinkRecord: AudioRecord? = null
    private var singleRecord: AudioRecord? = null
    private var writeThread: Thread? = null
    private var outputFile: File? = null
    private var statusMessage: String = "idle"
    private var activeStrategy: RecordingStrategy? = null
    private var shellContext: Context? = null
    private var wakeLock: PowerManager.WakeLock? = null

    constructor(context: Context) {
        val base = obtainSystemContext(context)
        shellContext = WrappedShellContext(base)
        Log.i(TAG, "ShellRecorderUserService created uid=${android.os.Process.myUid()}")
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
            Log.w(TAG, "system context reflection failed, using fallback", e)
            fallback.applicationContext
        }
    }

    @Throws(RemoteException::class)
    override fun start(outputPath: String, phoneNumber: String?): Boolean {
        if (recording.get()) return false
        val ctx = shellContext ?: return false
        outputFile = File(outputPath)
        outputFile?.parentFile?.mkdirs()
        activeStrategy = null

        val audioMode = try {
            (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode
        } catch (_: Exception) {
            null
        }

        val isVoipLikely = audioMode == AudioManager.MODE_IN_COMMUNICATION
        Log.i(TAG, "start() path=$outputPath voipLikely=$isVoipLikely")

        val latch = CountDownLatch(1)
        var success = false

        Handler(Looper.getMainLooper()).post {
            try {
                success = runStrategyLadder(ctx, isVoipLikely)
            } catch (e: Throwable) {
                Log.e(TAG, "strategy ladder failed", e)
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
            statusMessage = activeStrategy?.let {
                "recording [${it.label}] ${it.description}"
            } ?: "recording"
            Log.i(TAG, "SUCCESS strategy=${activeStrategy?.label}")
        } else {
            statusMessage = "all strategies failed"
        }
        return success
    }

    private fun runStrategyLadder(ctx: Context, voipLikely: Boolean): Boolean {
        val order = if (voipLikely) {
            listOf(
                RecordingStrategy.VOICE_COMMUNICATION,
                RecordingStrategy.DUAL_UPLINK_DOWNLINK,
                RecordingStrategy.MIC_PLUS_DOWNLINK,
                RecordingStrategy.VOICE_CALL,
                RecordingStrategy.MIC_ONLY
            )
        } else {
            listOf(
                RecordingStrategy.DUAL_UPLINK_DOWNLINK,
                RecordingStrategy.MIC_PLUS_DOWNLINK,
                RecordingStrategy.VOICE_CALL,
                RecordingStrategy.VOICE_COMMUNICATION,
                RecordingStrategy.MIC_ONLY
            )
        }

        for (strategy in order) {
            val ok = when (strategy) {
                RecordingStrategy.DUAL_UPLINK_DOWNLINK -> tryDual(ctx)
                RecordingStrategy.MIC_PLUS_DOWNLINK -> tryMicPlusDownlink(ctx)
                RecordingStrategy.VOICE_CALL -> trySingle(ctx, MediaRecorder.AudioSource.VOICE_CALL, strategy)
                RecordingStrategy.VOICE_COMMUNICATION -> trySingle(ctx, MediaRecorder.AudioSource.VOICE_COMMUNICATION, strategy)
                RecordingStrategy.MIC_ONLY -> trySingle(ctx, MediaRecorder.AudioSource.MIC, strategy)
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
            val level = measureRms(up, down)
            if (level < SILENCE_THRESHOLD) {
                up.stop(); up.release()
                down.stop(); down.release()
                return false
            }
            uplinkRecord = up
            downlinkRecord = down
            true
        } catch (e: Throwable) {
            Log.w(TAG, "tryDual failed", e)
            false
        }
    }

    private fun tryMicPlusDownlink(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            val (sampleRate, channelConfig, encoding, bufSize) = audioParams()
            val mic = buildRecord(ctx, MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, encoding, bufSize)
            val down = buildRecord(ctx, MediaRecorder.AudioSource.VOICE_DOWNLINK, sampleRate, channelConfig, encoding, bufSize)
            if (mic == null || down == null) {
                mic?.release(); down?.release()
                return false
            }
            mic.startRecording()
            down.startRecording()
            val level = measureRms(mic, down)
            if (level < SILENCE_THRESHOLD) {
                mic.stop(); mic.release()
                down.stop(); down.release()
                return false
            }
            uplinkRecord = mic
            downlinkRecord = down
            true
        } catch (e: Throwable) {
            Log.w(TAG, "tryMicPlusDownlink failed", e)
            false
        }
    }

    private fun trySingle(ctx: Context, source: Int, strategy: RecordingStrategy): Boolean {
        return try {
            val (sampleRate, channelConfig, encoding, bufSize) = audioParams()
            val rec = buildRecord(ctx, source, sampleRate, channelConfig, encoding, bufSize) ?: return false
            rec.startRecording()
            val level = measureRms(rec, null)
            if (level < SILENCE_THRESHOLD && strategy != RecordingStrategy.MIC_ONLY) {
                rec.stop(); rec.release()
                return false
            }
            singleRecord = rec
            true
        } catch (e: Throwable) {
            Log.w(TAG, "trySingle failed", e)
            false
        }
    }

    private fun measureRms(primary: AudioRecord, secondary: AudioRecord?): Double {
        val buf = ShortArray(2048)
        var sumSquares = 0.0
        var samples = 0
        val deadline = System.currentTimeMillis() + 350
        while (System.currentTimeMillis() < deadline) {
            val n = primary.read(buf, 0, buf.size)
            if (n > 0) {
                for (i in 0 until n) {
                    val s = buf[i].toDouble()
                    sumSquares += s * s
                    samples++
                }
            }
            secondary?.let {
                val n2 = it.read(buf, 0, buf.size)
                if (n2 > 0) {
                    for (i in 0 until n2) {
                        val s = buf[i].toDouble()
                        sumSquares += s * s
                        samples++
                    }
                }
            }
        }
        if (samples == 0) return 0.0
        return sqrt(sumSquares / samples)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
            }.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (e: Throwable) {
            Log.w(TAG, "buildRecord source=$source: ${e.message}")
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
            val file = outputFile ?: return@Thread
            try {
                FileOutputStream(file).use { fos ->
                    writeWavHeader(fos, 0, SAMPLE_RATE, 1, 16)
                    val buffer = ByteArray(4096)
                    var totalBytes = 0L
                    while (recording.get()) {
                        val read = readMixed(buffer)
                        if (read > 0) {
                            fos.write(buffer, 0, read)
                            totalBytes += read
                        } else if (read < 0) break
                    }
                    patchWavHeader(file, totalBytes)
                    Log.i(TAG, "Writer finished bytes=$totalBytes")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Writer error", e)
            }
        }, "ShellRecorderWriter").also { it.start() }
    }

    private fun readMixed(buffer: ByteArray): Int {
        return when {
            uplinkRecord != null && downlinkRecord != null -> {
                val upBuf = ByteArray(2048)
                val downBuf = ByteArray(2048)
                val upRead = uplinkRecord!!.read(upBuf, 0, upBuf.size)
                val downRead = downlinkRecord!!.read(downBuf, 0, downBuf.size)
                val n = minOf(upRead, downRead)
                if (n > 0) {
                    for (i in 0 until n step 2) {
                        if (i + 1 < n) {
                            val u = (upBuf[i].toInt() and 0xFF) or (upBuf[i + 1].toInt() shl 8)
                            val d = (downBuf[i].toInt() and 0xFF) or (downBuf[i + 1].toInt() shl 8)
                            val mixed = ((u.toShort() + d.toShort()) / 2).toInt()
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            buffer[i] = (mixed and 0xFF).toByte()
                            buffer[i + 1] = ((mixed shr 8) and 0xFF).toByte()
                        }
                    }
                    n
                } else 0
            }
            singleRecord != null -> singleRecord!!.read(buffer, 0, buffer.size)
            else -> 0
        }
    }

    @Throws(RemoteException::class)
    override fun stop(): String? {
        if (!recording.getAndSet(false)) return null
        releaseAllRecords()
        writeThread?.join(2500)
        writeThread = null
        releaseWakeLock()
        statusMessage = "stopped [${activeStrategy?.label ?: "none"}]"
        val path = outputFile?.absolutePath
        outputFile = null
        return path
    }

    private fun releaseAllRecords() {
        try {
            uplinkRecord?.apply { stop(); release() }
            downlinkRecord?.apply { stop(); release() }
            singleRecord?.apply { stop(); release() }
        } catch (_: Exception) {
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
            Log.w(TAG, "WakeLock failed", e)
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

    private fun writeWavHeader(out: FileOutputStream, dataSize: Long, sampleRate: Int, channels: Int, bits: Int) {
        val byteRate = sampleRate * channels * bits / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt((36 + dataSize).toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * bits / 8).toShort())
        header.putShort(bits.toShort())
        header.put("data".toByteArray())
        header.putInt(dataSize.toInt())
        out.write(header.array())
    }

    private fun patchWavHeader(file: File, dataSize: Long) {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(4)
                raf.writeInt(Integer.reverseBytes((36 + dataSize).toInt()))
                raf.seek(40)
                raf.writeInt(Integer.reverseBytes(dataSize.toInt()))
            }
        } catch (e: Exception) {
            Log.w(TAG, "patchWavHeader", e)
        }
    }

    companion object {
        private const val TAG = "ShellRecorderUS"
        private const val SILENCE_THRESHOLD = 25.0
        /** Speech-optimized; ~2.75× smaller than 44.1 kHz WAV. */
        private const val SAMPLE_RATE = 16_000
    }
}
