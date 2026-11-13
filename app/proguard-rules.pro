# Default ProGuard rules. R8 minification is disabled for this project
# (see app/build.gradle.kts), so these rules act as a safety net only.

# Keep MediaCodec / MediaMuxer related classes (accessed reflectively by the platform).
-keep class android.media.** { *; }
-dontwarn android.media.**
