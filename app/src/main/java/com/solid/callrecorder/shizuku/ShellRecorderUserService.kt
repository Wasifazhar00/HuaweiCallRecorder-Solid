package com.solid.callrecorder.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.solid.callrecorder.IShellRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Privileged recorder that runs inside the Shizuku-spawned process (UID 2000 = shell).
 *
 * Strategy ladder (inspired by ShizuCallRecorder / cally):
 *  1. Dual VOICE_UPLINK + VOICE_DOWNLINK (best – separate sides)
 *  2. VOICE_CALL
 *  3. VOICE_COMMUNICATION
 *  4. MIC (last resort)
 *
 * Audio is written as 16-bit PCM WAV.
 *
 * Shizuku instantiates this class in the elevated process.
 * The public constructor that takes Context is required by Shizuku.
 */
class ShellRecorderUserService : IShellRecorder.Stub {

    private val recording = AtomicBoolean(false)
    private var uplinkRecord: AudioRecord? = null
    private var downlinkRecord: AudioRecord? = null
    private var singleRecord: AudioRecord? = null
    private var writeThread: Thread? = null
    private var outputFile: File? = null
    private var statusMessage: String = "idle"
    private var shellContext: Context? = null

    /** Required by Shizuku UserService – called when the privileged process starts the service. */
    constructor(context: Context) {
        val base = try {
            val atClass = Class.forName("android.app.ActivityThread")
            val systemMain = atClass.getMethod("systemMain").invoke(null)
            atClass.getMethod("getSystemContext").invoke(systemMain) as Context
        } catch (e: Throwable) {
            Log.w(TAG, "Could not obtain system context via reflection", e)
            context.applicationContext
        }
        shellContext = WrappedShellContext(base)
        Log.i(TAG, "ShellRecorderUserService created (uid=${android.os.Process.myUid()})")
    }

    /** No-arg constructor required for some Shizuku / binder edge cases. */
    constructor() : this(
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val systemMain = atClass.getMethod("systemMain").invoke(null)
            atClass.getMethod("getSystemContext").invoke(systemMain) as Context
        } catch (e: Throwable) {
            throw RuntimeException("Cannot create ShellRecorderUserService without context", e)
        }
    )

    @Throws(RemoteException::class)
    override fun start(outputPath: String, phoneNumber: String?): Boolean {
        if (recording.get()) return false

        val ctx = shellContext ?: return false
        outputFile = File(outputPath)

        // Create AudioRecords on the main looper (AppOps / attribution safety)
        val latch = CountDownLatch(1)
        var success = false

        Handler(Looper.getMainLooper()).post {
            try {
                success = tryStartDual(ctx) || tryStartSingle(ctx)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start privileged recorder", e)
                statusMessage = "error: ${e.message}"
                success = false
            } finally {
                latch.countDown()
            }
        }

        latch.await(3, TimeUnit.SECONDS)

        if (success) {
            recording.set(true)
            startWriterThread()
            statusMessage = "recording"
            Log.i(TAG, "Privileged recording started → $outputPath")
        }
        return success
    }

    private fun tryStartDual(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false

        return try {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
            val bufSize = minBuf * 2

            val up = buildRecord(ctx, MediaRecorder.AudioSource.VOICE_UPLINK, sampleRate, channelConfig, encoding, bufSize)
            val down = buildRecord(ctx, MediaRecorder.AudioSource.VOICE_DOWNLINK, sampleRate, channelConfig, encoding, bufSize)

            if (up == null || down == null) {
                up?.release()
                down?.release()
                return false
            }

            up.startRecording()
            down.startRecording()

            Thread.sleep(80)
            if (up.recordingState != AudioRecord.RECORDSTATE_RECORDING ||
                down.recordingState != AudioRecord.RECORDSTATE_RECORDING
            ) {
                up.stop(); up.release()
                down.stop(); down.release()
                return false
            }

            uplinkRecord = up
            downlinkRecord = down
            statusMessage = "dual UPLINK+DOWNLINK"
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Dual source failed", e)
            false
        }
    }

    private fun tryStartSingle(ctx: Context): Boolean {
        val candidates = listOf(
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )

        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        val bufSize = minBuf * 2

        for (source in candidates) {
            try {
                val rec = buildRecord(ctx, source, sampleRate, channelConfig, encoding, bufSize) ?: continue
                rec.startRecording()
                Thread.sleep(60)
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    singleRecord = rec
                    statusMessage = "single source=$source"
                    return true
                }
                rec.stop()
                rec.release()
            } catch (e: Throwable) {
                Log.w(TAG, "Source $source failed", e)
            }
        }
        return false
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
                // Builder + setContext is the key for shell attribution
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
            Log.w(TAG, "buildRecord source=$source failed: ${e.message}")
            null
        }
    }

    private fun startWriterThread() {
        writeThread = Thread({
            val file = outputFile ?: return@Thread
            try {
                FileOutputStream(file).use { fos ->
                    writeWavHeader(fos, 0, 44100, 1, 16)

                    val buffer = ByteArray(4096)
                    var totalBytes = 0L

                    while (recording.get()) {
                        val read: Int = when {
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

                        if (read > 0) {
                            fos.write(buffer, 0, read)
                            totalBytes += read
                        } else if (read < 0) {
                            break
                        }
                    }

                    patchWavHeader(file, totalBytes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Writer thread error", e)
            }
        }, "ShellRecorderWriter").also { it.start() }
    }

    @Throws(RemoteException::class)
    override fun stop(): String? {
        if (!recording.getAndSet(false)) return null

        try {
            uplinkRecord?.apply { stop(); release() }
            downlinkRecord?.apply { stop(); release() }
            singleRecord?.apply { stop(); release() }
        } catch (_: Exception) {}
        uplinkRecord = null
        downlinkRecord = null
        singleRecord = null

        writeThread?.join(2000)
        writeThread = null

        statusMessage = "stopped"
        val path = outputFile?.absolutePath
        outputFile = null
        Log.i(TAG, "Privileged recording stopped → $path")
        return path
    }

    @Throws(RemoteException::class)
    override fun isRecording(): Boolean = recording.get()

    @Throws(RemoteException::class)
    override fun getStatus(): String = statusMessage

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
            Log.w(TAG, "Could not patch WAV header", e)
        }
    }

    companion object {
        private const val TAG = "ShellRecorderUS"
    }
}
