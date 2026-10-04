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
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# Gson & Data Models
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keep,allowobfuscation,allowshrinking,allowoptimization class * extends com.google.gson.reflect.TypeToken
-keep class com.ridervoice.models.** { *; }
-keepclassmembers class com.ridervoice.models.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.ridervoice.data.local.entities.** { *; }
-keepclassmembers class com.ridervoice.data.local.entities.** { *; }

# Retrofit
-keep interface com.ridervoice.network.** { *; }
-keep class com.ridervoice.network.** { *; }
-keepclassmembers interface * {
    @retrofit2.http.* <methods>;
}
-keep class retrofit2.** { *; }
-keep interface retrofit2.Call { *; }
-keep class retrofit2.Response { *; }
-keep class kotlin.coroutines.Continuation { *; }
-dontwarn retrofit2.**
-dontwarn okhttp3.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**
