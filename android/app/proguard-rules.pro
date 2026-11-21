# Keep JavascriptInterface bridge methods in EditorScreen
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Kotlinx Serialization models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Suppress harmless POI / PDFBox optional desktop warnings
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn org.apache.poi.**
-dontwarn org.apache.logging.log4j.**
-dontwarn com.tom_roush.pdfbox.**
