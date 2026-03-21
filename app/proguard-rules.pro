# Add project specific ProGuard rules here.
# Preserve Gson model classes
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class com.agoii.mobile.core.** { *; }
