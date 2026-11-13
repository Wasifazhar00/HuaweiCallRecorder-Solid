package com.solid.callrecorder.recorder

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.solid.callrecorder.IShellRecorder
import com.solid.callrecorder.shizuku.ShellRecorderUserService
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShizukuEnhancedRecorder(private val context: Context) : EnhancedRecorder {

    private var service: IShellRecorder? = null
    private var bound = false
    private var currentOutput: File? = null
    private var currentPfd: ParcelFileDescriptor? = null
    private var lastStrategy: String? = null

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShellRecorderUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("shellrec")
        .debuggable(false)
        .version(6)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder ready")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead")
        service = null
        bound = false
    }

    init {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        } catch (_: Throwable) {
        }
    }

    override fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun requestPermission(requestCode: Int = 1001) {
        try {
            if (!Shizuku.isPreV11()) Shizuku.requestPermission(requestCode)
        } catch (e: Throwable) {
            Log.e(TAG, "requestPermission", e)
        }
    }

    fun lastStrategyLabel(): String? = lastStrategy

    private fun ensureBound(): Boolean {
        if (bound && service != null) {
            try {
                if (service!!.asBinder().pingBinder()) return true
            } catch (_: Exception) {
                service = null
                bound = false
            }
        }
        if (!isAvailable()) return false
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) {
                    service = IShellRecorder.Stub.asInterface(binder)
                    bound = true
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
            latch.await(4, TimeUnit.SECONDS) && bound
        } catch (e: Throwable) {
            Log.e(TAG, "bind", e)
            false
        }
    }

    override fun start(phoneNumber: String?): Boolean {
        lastStrategy = null
        if (!isAvailable() || !ensureBound()) return false

        val dir = RecordingStorage.safeAppDir(context)
        val name = RecordingStorage.newFileName("Shizuku", phoneNumber, "wav")
        val out = File(dir, name)
        return try {
            val pfd = ParcelFileDescriptor.open(
                out,
                ParcelFileDescriptor.MODE_READ_WRITE or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE
            )
            currentOutput = out
            currentPfd = pfd
            val started = service?.start(pfd, phoneNumber) == true
            if (started) {
                lastStrategy = try {
                    service?.activeStrategy?.takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
            } else {
                pfd.close()
                currentPfd = null
                out.delete()
                currentOutput = null
            }
            started
        } catch (e: Exception) {
            Log.e(TAG, "start", e)
            currentOutput?.delete()
            currentOutput = null
            false
        }
    }

    override fun stop(): File? {
        return try {
            service?.stop()
            try {
                currentPfd?.close()
            } catch (_: Exception) {
            }
            currentPfd = null
            val file = currentOutput
            currentOutput = null
            file?.takeIf { it.exists() && it.length() > 44 }
        } catch (e: Exception) {
            Log.e(TAG, "stop", e)
            currentOutput.also { currentOutput = null }
        }
    }

    fun release() {
        try {
            if (bound) {
                Shizuku.unbindUserService(userServiceArgs, object : ServiceConnection {
                    override fun onServiceConnected(n: ComponentName?, b: IBinder?) {}
                    override fun onServiceDisconnected(n: ComponentName?) {}
                }, true)
            }
        } catch (_: Throwable) {
        }
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (_: Throwable) {
        }
        service = null
        bound = false
    }

    companion object {
        private const val TAG = "ShizukuEnhanced"
        private val MANAGER_PACKAGES = arrayOf(
            "moe.shizuku.privileged.api",
            "moe.shizuku.manager"
        )

        fun isShizukuInstalled(context: Context): Boolean {
            val pm = context.packageManager
            for (pkg in MANAGER_PACKAGES) {
                try {
                    pm.getPackageInfo(pkg, 0)
                    return true
                } catch (_: PackageManager.NameNotFoundException) {
                }
            }
            return false
        }
    }
}
