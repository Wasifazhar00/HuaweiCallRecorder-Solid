package com.solid.callrecorder.recorder

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.solid.callrecorder.IShellRecorder
import com.solid.callrecorder.shizuku.ShellRecorderUserService
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Enhanced path: binds Shizuku UserService (shell UID) and drives privileged capture.
 * After start(), call [lastStrategyLabel] to learn which source succeeded.
 */
class ShizukuEnhancedRecorder(private val context: Context) : EnhancedRecorder {

    private var service: IShellRecorder? = null
    private var bound = false
    private var currentOutput: File? = null
    private var lastStrategy: String? = null

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShellRecorderUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("shellrecorder")
        .debuggable(false)
        .version(2)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                service = IShellRecorder.Stub.asInterface(binder)
                bound = true
                Log.i(TAG, "UserService connected")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            Log.w(TAG, "UserService disconnected")
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died")
        service = null
        bound = false
    }

    init {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku not present", e)
        }
    }

    override fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    fun requestPermission(requestCode: Int = 1001) {
        try {
            if (!Shizuku.isPreV11()) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "requestPermission failed", e)
        }
    }

    /** Label of the strategy that succeeded on the last start(), or null. */
    fun lastStrategyLabel(): String? = lastStrategy

    private fun ensureBound(): Boolean {
        if (bound && service != null) return true
        if (!isAvailable()) return false

        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) {
                    service = IShellRecorder.Stub.asInterface(binder)
                    bound = true
                    Log.i(TAG, "UserService bound successfully")
                }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                bound = false
            }
        }

        return try {
            Shizuku.bindUserService(userServiceArgs, conn)
            val ok = latch.await(5, TimeUnit.SECONDS) && bound
            if (!ok) Log.w(TAG, "bindUserService timed out or failed")
            ok
        } catch (e: Throwable) {
            Log.e(TAG, "bindUserService error", e)
            false
        }
    }

    override fun start(phoneNumber: String?): Boolean {
        lastStrategy = null
        if (!isAvailable()) {
            Log.w(TAG, "Shizuku not available / not authorized")
            return false
        }
        if (!ensureBound()) {
            Log.w(TAG, "Could not bind privileged UserService")
            return false
        }

        val recordingsDir = File(context.getExternalFilesDir(null), "CallRecordings")
        if (!recordingsDir.exists()) recordingsDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeNumber = phoneNumber?.replace(Regex("[^0-9+]"), "") ?: "unknown"
        val outFile = File(recordingsDir, "Enhanced_${safeNumber}_$timestamp.wav")
        currentOutput = outFile

        return try {
            val started = service?.start(outFile.absolutePath, phoneNumber) == true
            if (started) {
                lastStrategy = try {
                    service?.activeStrategy?.takeIf { it.isNotBlank() }
                } catch (_: Exception) { null }
                val status = try { service?.status } catch (_: Exception) { null }
                Log.i(TAG, "Enhanced started → ${outFile.name} strategy=$lastStrategy status=$status")
            } else {
                Log.w(TAG, "UserService.start returned false (all sources failed/silent)")
                currentOutput = null
            }
            started
        } catch (e: Exception) {
            Log.e(TAG, "start via UserService failed", e)
            currentOutput = null
            false
        }
    }

    override fun stop(): File? {
        return try {
            val path = service?.stop()
            val file = path?.let { File(it) } ?: currentOutput
            currentOutput = null
            Log.i(TAG, "Enhanced stopped → ${file?.absolutePath} strategy=$lastStrategy")
            file
        } catch (e: Exception) {
            Log.e(TAG, "stop failed", e)
            currentOutput
        }
    }

    fun release() {
        try {
            if (bound) {
                Shizuku.unbindUserService(userServiceArgs, connection, true)
            }
        } catch (_: Throwable) {}
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (_: Throwable) {}
        service = null
        bound = false
        lastStrategy = null
    }

    companion object {
        private const val TAG = "ShizukuEnhancedRecorder"

        fun isShizukuInstalled(context: Context): Boolean {
            return try {
                context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                try {
                    context.packageManager.getPackageInfo("moe.shizuku.manager", 0)
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
            }
        }
    }
}
