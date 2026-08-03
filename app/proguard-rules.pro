# Project-specific R8 rules.
-keepattributes *Annotation*,Signature,SourceFile,LineNumberTable

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Compile-time-only Error Prone annotations referenced by Tink.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# kotlinx.serialization.
#
# Replaces the Gson rules left behind by the migration (Gson is no longer a dependency). R8 cannot
# see the reflective link from a @Serializable class to its Companion and generated $serializer, so
# lookups that are not resolved statically at the call site — generic and polymorphic types in
# particular — need these keeps to survive minification. Rules are the ones published with the
# library.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-dontnote kotlinx.serialization.**

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# AndCode REST payloads are matched by @SerialName, and persisted JSON must keep stable field names
# across app updates, so these must not be renamed.
-keep class com.yugahashimoto.andcode.core.api.** { *; }
-keep class com.yugahashimoto.andcode.data.connection.ConnectionProfile { *; }
-keep class com.yugahashimoto.andcode.data.settings.Draft { *; }
-keep class com.yugahashimoto.andcode.runtime.local.LocalRuntimeMetadata { *; }

# Vosk wake word spotting, and the JNA bridge it reaches the native library through.
#
# Neither artifact ships consumer rules, so without these R8 renames the fields JNA's own native
# code resolves by name from Native.initIDs() — "Can't obtain peer field ID for class
# com.sun.jna.Pointer" — which fails com.sun.jna.Native's static initializer and leaves every
# org.vosk class permanently unloadable in a release build. Members have to be kept, not just the
# class names. Callbacks and Structure subclasses are read reflectively from native code too, so
# anything deriving from a JNA type keeps its members as well.
-keep class com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keepclassmembers class * implements com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**

# Firebase Crashlytics
-keep class com.google.firebase.crashlytics.** { *; }
-dontwarn com.google.firebase.crashlytics.**
