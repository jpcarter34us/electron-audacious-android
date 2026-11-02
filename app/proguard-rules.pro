# JNI entry points.
-keepclasseswithmembernames class io.github.jpcarter34us.audacious.engine.Native { native <methods>; }
# OkHttp 3.12 on old platforms.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
