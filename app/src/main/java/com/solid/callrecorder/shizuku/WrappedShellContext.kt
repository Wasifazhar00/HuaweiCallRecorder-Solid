package com.solid.callrecorder.shizuku

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Process

/**
 * Context that pretends to be the system package "com.android.shell".
 *
 * AudioFlinger validates both UID and package name when opening
 * VOICE_UPLINK / VOICE_DOWNLINK / VOICE_CALL sources.
 * Running under Shizuku gives us UID 2000 (shell).
 * This wrapper makes the package identity match as well.
 *
 * Technique originates from scrcpy (FakeContext) and is used by
 * modern FOSS call recorders (ShizuCallRecorder, cally, etc.).
 */
class WrappedShellContext(base: Context) : ContextWrapper(base) {

    companion object {
        const val SHELL_PACKAGE = "com.android.shell"
    }

    override fun getPackageName(): String = SHELL_PACKAGE

    override fun getOpPackageName(): String = SHELL_PACKAGE

    @SuppressLint("NewApi")
    override fun getAttributionSource(): AttributionSource {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AttributionSource.Builder(Process.SHELL_UID)
                .setPackageName(SHELL_PACKAGE)
                .build()
        } else {
            // Fallback for older APIs – still return a builder-based source when possible
            AttributionSource.Builder(Process.myUid())
                .setPackageName(SHELL_PACKAGE)
                .build()
        }
    }

    override fun getApplicationContext(): Context = this

    override fun createPackageContext(packageName: String?, flags: Int): Context = this
}
