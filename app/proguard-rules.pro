# PDFsmith ProGuard / R8 rules.
# Release builds currently ship unminified for maximum reliability; these rules are
# kept so minification can be enabled safely later.

-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.bouncycastle.**
-dontwarn javax.**
-dontwarn java.awt.**
