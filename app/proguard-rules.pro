# R8 is enabled for release. These rules cover the parts that are reached reflectively or from
# native code, which R8 cannot see.

# --- SQLCipher -----------------------------------------------------------------------------------
# The native library calls back into these classes by name via JNI, so shrinking or renaming them
# breaks the database at runtime rather than at build time.
-keep class net.zetetic.database.** { *; }
-keep interface net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# --- Room ----------------------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# --- kotlinx.serialization (type-safe navigation routes) ------------------------------------------
# Navigation's type-safe routes are serialized by kotlinx.serialization, so Routes is the one place
# in this app where code is reached other than by a direct call. If these rules are wrong the failure
# is release-only and looks like navigation silently breaking, which is why they are spelled out
# rather than left to the library's consumer rules.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# The generated serializer objects: Routes$Detail$$serializer and friends. includedescriptorclasses
# keeps the types named in their signatures too, which is what stops the route's own fields from
# being shrunk out from under the serializer.
-keep,includedescriptorclasses class com.personal.bubuprotect.ui.Routes$*$$serializer { *; }

# The sealed interface itself is @Serializable (it is the polymorphic parent), so it needs the same
# treatment as its subclasses - hence both the bare name and the nested-class wildcard.
-keepclassmembers class com.personal.bubuprotect.ui.Routes {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class com.personal.bubuprotect.ui.Routes$* {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.personal.bubuprotect.ui.Routes$* {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Koin ------------------------------------------------------------------------------------------
-keep class org.koin.** { *; }
-dontwarn org.koin.**

# --- Crash reports -------------------------------------------------------------------------------
# Line numbers without the original file name: a stack trace stays readable, but the source layout
# is not handed out in the APK.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
