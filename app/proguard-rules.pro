# --- Sudachi ------------------------------------------------------------------
# Sudachi loads plugin classes by name from the JSON settings string (e.g.
# SimpleOovProviderPlugin). R8 would otherwise strip them as "unused" since
# nothing references them statically.
-keep class com.worksap.nlp.sudachi.** { *; }

# --- Room ---------------------------------------------------------------------
# KSP-generated DAO/database implementations are referenced by name.
-keep class **_Impl { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# --- Hilt / Dagger ------------------------------------------------------------
-keep class dagger.hilt.** { *; }
-keep class hilt_aggregated_deps.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper

# --- AnkiDroid API ------------------------------------------------------------
# The library uses ContentProvider lookups by class name.
-keep class com.ichi2.anki.** { *; }

# --- Kotlinx coroutines -------------------------------------------------------
# Built-in rules generally cover this; keep ServiceLoader entries as a guard.
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }

# --- Compose ------------------------------------------------------------------
# Compose ships its own consumer-proguard rules; this silences spurious warnings.
-dontwarn androidx.compose.**
