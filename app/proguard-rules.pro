# ══════════════════════════════════════════════════════════════════
# NexusBrowser ProGuard / R8 Rules
# ══════════════════════════════════════════════════════════════════

# ── JavaScript Interface ───────────────────────────────────────────
# BUG FIX: @JavascriptInterface methods rename ho jaate hain R8 se
# kyunki JS "NexusVideoScraper.onVideoFound()" exact naam pe call karta hai.
# Ye rule us class aur method ko obfuscation se bachata hai.
-keepclassmembers class com.nexus.browser.NexusJsInterface {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.nexus.browser.NexusJsInterface { *; }

# ── Data classes — Kotlin data classes ke copy()/component*() methods ─
-keepclassmembers class com.nexus.browser.VideoQuality { *; }
-keepclassmembers class com.nexus.browser.VideoStream  { *; }

# ── Coroutines ─────────────────────────────────────────────────────
# BUG FIX: Coroutine internals ko R8 se bachao
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ── AndroidX / Material ────────────────────────────────────────────
-keep class androidx.recyclerview.widget.** { *; }
-keep class com.google.android.material.bottomsheet.** { *; }

# ── General Android ────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Fragment
-keep public class * extends androidx.fragment.app.Fragment

# Remove verbose logging from release builds (APK size + performance)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
