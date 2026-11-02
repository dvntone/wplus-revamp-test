# Add project specific ProGuard rules here.

# Keep Room entities and DAOs
-keep class com.wscanplus.android.data.db.** { *; }

# Keep core heuristics and public API
-keep class com.wscanplus.android.core.** { *; }

# Keep Shizuku classes
-keep class rikka.shizuku.** { *; }

# Firebase – keep model fields for reflection-based serialization
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# OkHttp / Retrofit
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Kotlin serialization / coroutines
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }
