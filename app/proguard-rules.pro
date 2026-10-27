# Jsoup / OkHttp
-dontwarn org.jsoup.**
-dontwarn okhttp3.**
-dontwarn okio.**

# biweekly uses optional javax references
-dontwarn javax.**
-keep class biweekly.** { *; }
-keep class com.github.** { *; }

# biweekly подтягивает JSON-экспорт через Jackson, который нам не нужен.
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.**
-dontwarn com.google.ical.**
-dontwarn org.slf4j.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.keisha.tusur.timetable.** {
    *** Companion;
}
-keepclasseswithmembers class com.keisha.tusur.timetable.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.keisha.tusur.timetable.**$$serializer { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
