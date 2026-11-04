# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.roylet.photos.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.roylet.photos.**$$serializer { *; }

# OkHttp, Coil, Media3, kotlinx-serialization and MapLibre ship their own consumer rules.
# Defensive extras for MapLibre: native code looks classes up by name over JNI, and the
# GeoJSON models are (de)serialised reflectively with Gson.
-keep class org.maplibre.geojson.** { *; }
-keep class org.maplibre.android.geometry.** { *; }
-keepclasseswithmembers class org.maplibre.** { native <methods>; }
-dontwarn org.maplibre.**
