# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keepclasseswithmembers class * {
    native <methods>;
}
# Keep classes and methods that have the @UsedForTesting annotation
-keep @org.futo.inputmethod.annotations.UsedForTesting class *
-keepclassmembers class * {
    @org.futo.inputmethod.annotations.UsedForTesting *;
}

# Keep classes and methods that have the @ExternallyReferenced annotation
-keep @org.futo.inputmethod.annotations.ExternallyReferenced class *
-keepclassmembers class * {
    @org.futo.inputmethod.annotations.ExternallyReferenced *;
}

# Keep native methods
-keepclassmembers class * {
    native <methods>;
}

# Keep classes that are used as a parameter type of methods that are also marked as keep
# to preserve changing those methods' signature.
-keep class org.futo.inputmethod.latin.AssetFileAddress
-keep class org.futo.inputmethod.latin.Dictionary
-keep class org.futo.inputmethod.latin.NgramContext
-keep class org.futo.inputmethod.latin.makedict.ProbabilityInfo
-keep class org.futo.inputmethod.keyboard.KeyboardLayout { *; }

-dontobfuscate
-optimizations !code/allocation/variable

# JSR 305 annotations are for embedding nullability information.
-dontwarn javax.annotation.**

# Keep mozc protobuf
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
    <methods>;
}
-keep class org.mozc.android.inputmethod.japanese.protobuf.** { *; }

# Keep swipe
-keep class org.futo.ml.inference.** { *; }
# alex: AI provera - Anthropic SDK koristi Jackson i refleksiju
-keep class com.anthropic.** { *; }
-keep class org.futo.inputmethod.latin.uix.actions.aicheck.AiCheckResult { *; }
-keep class org.futo.inputmethod.latin.uix.actions.aicheck.AiCheckResult$* { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlin.reflect.** { *; }
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations
-dontwarn com.anthropic.**
-dontwarn com.fasterxml.jackson.**
-dontwarn io.opentelemetry.**
-dontwarn io.swagger.**
-dontwarn com.standardwebhooks.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn java.beans.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.github.victools.**
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedArrayType
-dontwarn java.lang.reflect.AnnotatedWildcardType
-dontwarn java.lang.reflect.AnnotatedTypeVariable
