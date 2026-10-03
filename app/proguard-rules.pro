# Project-specific R8/ProGuard rules.
# Minification is currently disabled (isMinifyEnabled = false in app/build.gradle.kts);
# these rules make it safe to turn on later without breaking reflection-based code.
-dontwarn java.lang.invoke.**

# ---------------------------------------------------------------------------
# Gson: models are populated reflectively, so their field names must survive.
# ---------------------------------------------------------------------------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod,InnerClasses

# Any field annotated with @SerializedName keeps its name, and its class is kept.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-if class * { @com.google.gson.annotations.SerializedName <fields>; }
-keep class <1>

# GitHub Releases API models used by the in-app updater.
-keep class com.lumina.reader.core.update.GitHubReleaseDto { *; }
-keep class com.lumina.reader.core.update.GitHubAssetDto { *; }

# AI (OpenAI-compatible chat completions) request/response models and the Retrofit API.
-keep class com.lumina.reader.core.network.AiMessage { *; }
-keep class com.lumina.reader.core.network.AiRequest { *; }
-keep class com.lumina.reader.core.network.AiPlugin { *; }
-keep class com.lumina.reader.core.network.AiChoice { *; }
-keep class com.lumina.reader.core.network.AiResponse { *; }
-keep class com.lumina.reader.core.network.AiError { *; }
-keep interface com.lumina.reader.core.network.AiApi { *; }

# Gson type adapters and TypeToken subclasses (generic type capture).
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# ---------------------------------------------------------------------------
# Retrofit: service interfaces are proxied reflectively.
# ---------------------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# ---------------------------------------------------------------------------
# Room: generated *_Impl classes are instantiated by name.
# ---------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keepclassmembers class * {
    @androidx.room.TypeConverter <methods>;
}

# ---------------------------------------------------------------------------
# DataStore Preferences uses protobuf-lite internally.
# ---------------------------------------------------------------------------
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# Components declared in the manifest (receivers, provider) are kept by AAPT rules.
