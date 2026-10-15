# ==================== General ====================
-optimizationpasses 5
-repackageclasses ''
-allowaccessmodification
-dontpreverify

# ==================== Keep app_process entry points ====================
-keep class com.kooo.hey.audio.VolumeShellMain {
    public static void main(java.lang.String[]);
}

# ==================== AndroidX / Material ====================
-dontwarn androidx.**
-keep class androidx.core.app.** { *; }

# ==================== Aggressive obfuscation ====================
# Strip platform logging in release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# Also strip our AppLog wrapper.
-assumenosideeffects class com.kooo.hey.log.AppLog {
    public static void i(...);
    public static void d(...);
    public static void w(...);
    public static void e(...);
}

# Obfuscation dictionaries — use Java reserved words to confuse decompilers
-obfuscationdictionary proguard-dict.txt
-classobfuscationdictionary proguard-dict.txt
-packageobfuscationdictionary proguard-dict.txt

# Flatten the package hierarchy to hide structure
-flattenpackagehierarchy ''

# Merge interfaces aggressively to reduce class count and hide relationships
-mergeinterfacesaggressively
