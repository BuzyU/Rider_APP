# Proguard / R8 Configuration for Rider Voice

# LiveKit & WebRTC
-keep class io.livekit.android.** { *; }
-keep class org.webrtc.** { *; }
-dontwarn io.livekit.android.**
-dontwarn org.webrtc.**

# Mapbox Native & Maps Compose
-keep class com.mapbox.** { *; }
-dontwarn com.mapbox.**

# Room Database & SQLite
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**
-keep class * extends androidx.room.RoomDatabase

# Kotlin Reflection & Annotations
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Gson & Data Models
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.ridervoice.models.** { *; }
-keep class com.ridervoice.data.local.entities.** { *; }

# Retrofit
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**
