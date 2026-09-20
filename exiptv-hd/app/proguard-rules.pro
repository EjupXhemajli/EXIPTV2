# EXIPTV — R8-Regeln
#
# Die App nutzt keinerlei Reflection-basiertes Mapping (kein Gson/Moshi/Jackson):
# Xtream-JSON wird per Streaming-Parser von Hand gelesen. Deshalb braucht kein
# einziges Modell eine keep-Regel. Was hier steht, deckt nur Fremdbibliotheken ab,
# die ihre Regeln nicht vollständig selbst mitbringen.

# --- Zeilennummern in Absturzberichten erhalten ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Kotlin Coroutines ---
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# --- OkHttp / Okio (optionale Compile-Time-Abhängigkeiten) ---
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# --- media3 ---
# Renderer und Extractors werden teils über Klassennamen geladen.
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.extractor.** { *; }
-dontwarn androidx.media3.**

# --- Room ---
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# --- Compose ---
-dontwarn androidx.compose.**
