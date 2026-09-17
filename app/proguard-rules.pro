# MoRead proguard rules (release minify currently disabled; rules kept for future use)
-keep class org.mozilla.universalchardet.** { *; }
# pdfbox-android 可选依赖（JPEG2000，桌面端）
-dontwarn com.gemalto.jp2.**

# junrar 携带的 slf4j-api 在 Android 无绑定实现
-dontwarn org.slf4j.impl.**

# junrar 反射构造（Archive 内部）
-keep class com.github.junrar.** { *; }
