# Room, Compose, CameraX, Coil and kotlinx.serialization ship their own consumer rules.
# Keep this file for app-specific rules only.

# OpenCV's native code looks up its Java classes and fields (Mat.nativeObj, …) by name over JNI.
-keep class org.opencv.** { *; }

# The admin map's page calls Android.onReady()/onPerson() by name (feature/tracking/MapView.kt).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
