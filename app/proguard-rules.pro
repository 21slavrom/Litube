-keep class org.koin.** { *; }
-dontwarn org.koin.**

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
