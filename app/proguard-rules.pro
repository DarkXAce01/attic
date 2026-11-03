# Project-specific R8 rules. The Android, Compose, Room and Coil libraries ship their own.

# OpenCV's Java classes are called from its native code by name.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
