# M2Git ProGuard Rules

# JGit - heavy reflection usage
-keep class org.eclipse.jgit.** { *; }
-keepclassmembers class org.eclipse.jgit.** { *; }

# JSCH (mwiede fork)
-keep class com.jcraft.jsch.** { *; }
-keep class com.github.mwiede.jsch.** { *; }

# BouncyCastle - crypto requires class names
-keep class org.bouncycastle.** { *; }

# Conscrypt
-keep class org.conscrypt.** { *; }

# Milton WebDAV server - uses reflection for resource methods
-keep class io.milton.** { *; }
-keep class milton.** { *; }

# Xerces removed - no longer a dependency

# SLF4J / Logback
-keep class ch.qos.logback.** { *; }
-keep class org.slf4j.** { *; }

# Guava removed - no longer a dependency

# Android components
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.app.Fragment
-keep public class * extends androidx.fragment.app.Fragment
-keep public class * extends android.app.Application
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# WebView JavaScript interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# View binding
-keepclassmembers class * implements android.view.View$OnTouchListener {
    *;
}

# Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Enum
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Timber - don't warn about removed calls
-dontwarn timber.log.Timber

# DNSJava
-keep class org.xbill.DNS.** { *; }

# Commons IO
-dontwarn org.apache.commons.io.**

# xml-apis conflicts with Android runtime (org.xml.sax etc.)
-dontwarn org.xml.sax.**
-dontwarn org.w3c.dom.**
-dontwarn org.xmlpull.v1.**
-dontwarn sun.net.spi.nameservice.**

# Allow R8 to handle library class implementing program class
-ignorewarnings
