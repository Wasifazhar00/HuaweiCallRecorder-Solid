package com.solid.callrecorder;

import android.os.ParcelFileDescriptor;

interface IShellRecorder {
    /** Writes a WAV into the app-owned file descriptor so playback does not need shared storage. */
    boolean start(in ParcelFileDescriptor output, String phoneNumber);
    String stop();
    boolean isRecording();
    String getStatus();
    String getActiveStrategy();
}
