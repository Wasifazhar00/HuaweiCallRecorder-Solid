package com.solid.callrecorder;

interface IShellRecorder {
    boolean start(String outputPath, String phoneNumber);
    String stop();
    boolean isRecording();
    String getStatus();
    /** Returns the label of the strategy that succeeded, e.g. DUAL_UPLINK_DOWNLINK */
    String getActiveStrategy();
}
