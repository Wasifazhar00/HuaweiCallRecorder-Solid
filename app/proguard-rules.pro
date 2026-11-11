# AIDL stubs
-keep class com.solid.callrecorder.IShellRecorder { *; }
-keep class com.solid.callrecorder.IShellRecorder$Stub { *; }
-keep class com.solid.callrecorder.IShellRecorder$Stub$Proxy { *; }

# UserService must keep name (bound by ComponentName string)
-keep class com.solid.callrecorder.shizuku.ShellRecorderUserService { *; }
-keep class com.solid.callrecorder.shizuku.WrappedShellContext { *; }

# Shizuku
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# Hidden API bypass
-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn org.lsposed.hiddenapibypass.**

# Kotlin
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings { <fields>; }
