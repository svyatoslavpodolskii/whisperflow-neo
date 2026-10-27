# ═══════════════════════════════════════════════════════════════════════════════
# WhisperFlow ProGuard Rules
# ═══════════════════════════════════════════════════════════════════════════════

# Keep app classes
-keep class com.example.whisperflow.** { *; }
-keepclassmembers class com.example.whisperflow.** { *; }

# ═══════════════════════════════════════════════════════════════════════════════
# Kotlin
# ═══════════════════════════════════════════════════════════════════════════════
-dontwarn kotlin.**
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Lazy {
    public <methods>;
}
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ═══════════════════════════════════════════════════════════════════════════════
# Jetpack Compose
# ═══════════════════════════════════════════════════════════════════════════════
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }

# ═══════════════════════════════════════════════════════════════════════════════
# DataStore
# ═══════════════════════════════════════════════════════════════════════════════
-keep class androidx.datastore.** { *; }
-keepclassmembers class androidx.datastore.** { *; }

# ═══════════════════════════════════════════════════════════════════════════════
# OkHttp & Networking
# ═══════════════════════════════════════════════════════════════════════════════
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keepclassmembers class okhttp3.** { *; }

# ═══════════════════════════════════════════════════════════════════════════════
# JSON (org.json)
# ═══════════════════════════════════════════════════════════════════════════════
-keep class org.json.** { *; }

# ═══════════════════════════════════════════════════════════════════════════════
# TensorFlow Lite
# ═══════════════════════════════════════════════════════════════════════════════
-dontwarn org.tensorflow.**
-keep class org.tensorflow.** { *; }
-keepclassmembers class org.tensorflow.** { *; }

# ═══════════════════════════════════════════════════════════════════════════════
# Google Play Services / Wearable
# ═══════════════════════════════════════════════════════════════════════════════
-dontwarn com.google.android.gms.**
-keep class com.google.android.gms.** { *; }
-keep class com.google.android.gms.wearable.** { *; }
-keepclassmembers class com.google.android.gms.wearable.** { *; }

# ═══════════════════════════════════════════════════════════════════════════════
# Services (IME, Assistant, WatchRelay)
# ═══════════════════════════════════════════════════════════════════════════════
-keep class * extends android.inputmethodservice.InputMethodService { *; }
-keep class * extends android.service.voice.VoiceInteractionService { *; }
-keep class * extends android.service.voice.VoiceInteractionSessionService { *; }
-keep class * extends com.google.android.gms.wearable.WearableListenerService { *; }

# ═══════════════════════════════════════════════════════════════════════════════
# AndroidX
# ═══════════════════════════════════════════════════════════════════════════════
-dontwarn androidx.**
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.activity.** { *; }

# ═══════════════════════════════════════════════════════════════════════════════
# Keep enums
# ═══════════════════════════════════════════════════════════════════════════════
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ═══════════════════════════════════════════════════════════════════════════════
# Keep Parcelable
# ═══════════════════════════════════════════════════════════════════════════════
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# ═══════════════════════════════════════════════════════════════════════════════
# Keep Serializable
# ═══════════════════════════════════════════════════════════════════════════════
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ═══════════════════════════════════════════════════════════════════════════════
# Suppress warnings
# ═══════════════════════════════════════════════════════════════════════════════
-dontwarn java.lang.invoke.**
-dontwarn javax.annotation.**
-dontwarn sun.misc.Unsafe

# Netty probes optional desktop TLS, logging and instrumentation providers at runtime.
# The embedded Android server uses plain HTTP; none of these optional JARs are packaged.
-dontwarn io.netty.internal.tcnative.**
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.bouncycastle.asn1.pkcs.PrivateKeyInfo
-dontwarn org.bouncycastle.openssl.**
-dontwarn org.bouncycastle.operator.InputDecryptorProvider
-dontwarn org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
-dontwarn org.conscrypt.BufferAllocator
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.HandshakeListener
-dontwarn org.eclipse.jetty.npn.**
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn reactor.blockhound.integration.BlockHoundIntegration
