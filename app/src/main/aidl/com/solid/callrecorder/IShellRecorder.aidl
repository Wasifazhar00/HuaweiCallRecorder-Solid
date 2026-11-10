package com.solid.callrecorder;

interface IShellRecorder {
    boolean start(String outputPath, String phoneNumber);
    String stop();
    boolean isRecording();
    String getStatus();
}
