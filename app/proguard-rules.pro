# MoRead proguard rules (release minify currently disabled; rules kept for future use)
-keep class org.mozilla.universalchardet.** { *; }
# pdfbox-android 可选依赖（JPEG2000，桌面端）
-dontwarn com.gemalto.jp2.**

# junrar 携带的 slf4j-api 在 Android 无绑定实现
-dontwarn org.slf4j.impl.**

# junrar 反射构造（Archive 内部）
-keep class com.github.junrar.** { *; }

# Release 包剥离所有 android.util.Log 调用（防日志泄露令牌/错误栈）
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
