# Kotlin metadata, for reflection-based libraries reading Kotlin class info
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep class kotlin.Metadata { *; }

# Glance widgets — keep receiver classes and Compose runtime
-keep class com.hazia.forgetmenot.widget.** { *; }
-keep class androidx.glance.** { *; }
