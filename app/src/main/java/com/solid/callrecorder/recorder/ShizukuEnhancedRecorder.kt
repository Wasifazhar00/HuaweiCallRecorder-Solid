package com.solid.callrecorder.recorder

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Skeleton for the Enhanced recording path using Shizuku.
 *
 * Shizuku gives the app shell-level privileges (similar to ADB),
 * which is currently the most reliable way to capture both sides
 * of a call on non-rooted devices.
 *
 * This is a clean skeleton. The actual audio capture logic
 * (usually involving hidden AudioSystem / AudioRecord APIs or
 * shell commands) still needs to be implemented following
 * the techniques used by projects such as ShizuCallRecorder.
 *
 * Usage flow:
 * 1. User installs Shizuku app and starts it (via Wireless Debugging or root)
 * 2. User grants permission to this app inside Shizuku
 * 3. isAvailable() returns true
 * 4. start() / stop() can then use the elevated privileges
 */
class ShizukuEnhancedRecorder(private val context: Context) : EnhancedRecorder {

    private var isBound = false
    private var currentOutput: File? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        isBound = true
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died")
        isBound = false
    }

    init {
        // Register listeners (safe even if Shizuku is not present)
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku not available on this device", e)
        }
    }

    override fun isAvailable(): Boolean {
        return try {
            // Shizuku must be running and this app must be authorized
            Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    /** Request Shizuku permission if not already granted. */
    fun requestPermission(requestCode: Int = 1001) {
        try {
            if (Shizuku.isPreV11()) {
                // Old Shizuku – handled differently
                Log.w(TAG, "Pre-v11 Shizuku not fully supported in this skeleton")
            } else {
                Shizuku.requestPermission(requestCode)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
        }
    }

    override fun start(phoneNumber: String?): Boolean {
        if (!isAvailable()) {
            Log.w(TAG, "Shizuku not available or not authorized")
            return false
        }

        // TODO: Implement real both-sides capture here.
        // Typical approaches:
        // 1. Use Shizuku to run shell commands that start a privileged recorder
        // 2. Or obtain a privileged AudioRecord / MediaRecorder via reflection
        //    + Shizuku's user service / binder
        // See ShizuCallRecorder / CallVault source for working implementations.

        Log.i(TAG, "[Skeleton] Would start enhanced both-sides recording for $phoneNumber")
        Log.i(TAG, "[Skeleton] Real implementation still needs to be added")

        // Placeholder so the rest of the app can compile and fall back gracefully
        return false
    }

    override fun stop(): File? {
        Log.i(TAG, "[Skeleton] Would stop enhanced recording")
        val file = currentOutput
        currentOutput = null
        return file
    }

    fun release() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "ShizukuEnhancedRecorder"

        /** Quick check whether the Shizuku app is installed. */
        fun isShizukuInstalled(context: Context): Boolean {
            return try {
                context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }
}
