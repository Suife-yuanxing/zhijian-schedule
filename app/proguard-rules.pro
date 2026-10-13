# 纸间课表 ProGuard 规则

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Hilt
-keep,allowobfuscation @interface dagger.hilt.android.AndroidEntryPoint

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.zhijian.schedule.**$$serializer { *; }
-keepclassmembers class com.zhijian.schedule.** {
    *** Companion;
}
-keepclasseswithmembers class com.zhijian.schedule.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# jsoup
-keep class org.jsoup.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Glance 资源在 json/xml 中引用，避免资源混淆误删
-keep class androidx.glance.** { *; }
