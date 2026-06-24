# Minification/shrinking is currently OFF (see app/build.gradle.kts) because keep rules can't be
# tuned without a local build. These rules are kept ready for when R8 is enabled in a later phase.

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# Hilt / Dagger generated code
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.** { *; }

# Kotlin metadata / coroutines
-keepclassmembers class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**
