# ══════════════════════════════════════════════════════════════════
# NexusBrowser ProGuard / R8 Rules — Play Store Production Build
# ══════════════════════════════════════════════════════════════════

# NOTE: NexusJsInterface and VideoStream have been removed from this build.
# No JavaScript bridge rules are needed.

# ── Kotlin Coroutines ──────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ── AndroidX / Material ────────────────────────────────────────────
-keep class androidx.recyclerview.widget.** { *; }
-keep class com.google.android.material.bottomsheet.** { *; }

# ── Room Database — keep generated DAO implementations ────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# ── General Android ────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Fragment
-keep public class * extends androidx.fragment.app.Fragment

# ── ExoPlayer / Media3 ────────────────────────────────────────────
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }

# Remove verbose logging from release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
