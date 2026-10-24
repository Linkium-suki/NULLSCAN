# OpenCV native bindings are accessed via JNI
-keep class org.opencv.** { *; }

# ML Kit
-keep class com.google.mlkit.vision.text.** { *; }

# Room entities accessed via reflection-free generated code; keep default rules
