# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   https://developer.android.com/topic/performance/app-optimization/add-keep-rules

# Keep TensorFlow Lite and support/runtime APIs that may be reached through
# reflection or native registration.
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.tensorflow.lite.nnapi.** { *; }
-keep class com.google.android.gms.tflite.** { *; }

# Keep classes that expose JNI methods. R8 must not rename or remove these
# entry points because their names are resolved from native code.
-keepclasseswithmembers,allowoptimization,includedescriptorclasses class * {
    native <methods>;
}

# Preserve native library loading and model metadata annotations.
-keepclassmembers,allowoptimization class * {
    native <methods>;
}
-keepattributes *Annotation*
