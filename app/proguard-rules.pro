# JARVIS release rules
-keep class com.jarvis.ai.provider.** { *; }
-keep class com.jarvis.ai.data.** { *; }
-keepclasseswithmembers class * {
    native <methods>;
}
