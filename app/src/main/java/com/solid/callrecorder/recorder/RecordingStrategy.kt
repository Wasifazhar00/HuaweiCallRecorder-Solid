package com.solid.callrecorder.recorder

/**
 * Ordered capture strategies used by the privileged UserService.
 * The first strategy that produces non-silent audio wins.
 */
enum class RecordingStrategy(val label: String, val description: String) {
    DUAL_UPLINK_DOWNLINK(
        "DUAL_UPLINK_DOWNLINK",
        "VOICE_UPLINK + VOICE_DOWNLINK mixed (best for cellular/VoLTE)"
    ),
    MIC_PLUS_DOWNLINK(
        "MIC_PLUS_DOWNLINK",
        "MIC + VOICE_DOWNLINK (Samsung-friendly / partial VoIP)"
    ),
    VOICE_CALL(
        "VOICE_CALL",
        "VOICE_CALL single source (mixed both sides when allowed)"
    ),
    VOICE_COMMUNICATION(
        "VOICE_COMMUNICATION",
        "VOICE_COMMUNICATION (preferred for VoIP / Wi-Fi calling on Android 14+)"
    ),
    MIC_ONLY(
        "MIC_ONLY",
        "MIC only (local side – last resort)"
    );

    companion object {
        fun fromLabel(label: String?): RecordingStrategy? =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) }
    }
}

/** Result returned after a successful (or failed) start attempt. */
data class CaptureResult(
    val success: Boolean,
    val strategy: RecordingStrategy? = null,
    val detail: String = "",
    val avgLevel: Double = 0.0
) {
    fun toStatusLine(): String {
        return if (success && strategy != null) {
            "OK [${strategy.label}] level=${String.format("%.1f", avgLevel)} – ${strategy.description}"
        } else {
            "FAIL – $detail"
        }
    }
}
