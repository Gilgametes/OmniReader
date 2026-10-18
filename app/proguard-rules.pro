# Keep Room entities' generated code and Hilt components intact.
-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepclassmembers class com.omnireader.app.data.** { *; }
-keepattributes *Annotation*
# kotlinx.serialization
-keepclassmembers class * { @kotlinx.serialization.SerialName <fields>; }
