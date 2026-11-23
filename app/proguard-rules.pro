# 默认不做混淆（个人工具，体积不是关键）
# 如需开启，把 app/build.gradle.kts 里 release 的 isMinifyEnabled 改成 true
-keep class com.audiosleep.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
