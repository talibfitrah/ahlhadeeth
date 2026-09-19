-keep class org.murabbie.ahlalhadeeth.data.** { *; }
-keep class androidx.sqlite.driver.bundled.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# جسر JavaScript لمشغّل يوتيوب (أسماء الدوال تُستدعى من JS)
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keep class org.murabbie.ahlalhadeeth.ui.screens.YtBridge { *; }
# NewPipe Extractor + Rhino (محرك JS لفك توقيع يوتيوب)
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.schabi.newpipe.extractor.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
