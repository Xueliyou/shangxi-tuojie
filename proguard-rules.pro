# WebView / 入口类全部保留，其余类名与成员名交由 R8 混淆
-keep class com.whale.netease.MainActivity { *; }
-keep class com.whale.netease.DownloadService { *; }
-keep class com.whale.netease.ApiBridge { *; }

# JS 注入接口不能被裁剪/改名
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*
-keepattributes JavascriptInterface
-dontwarn **
