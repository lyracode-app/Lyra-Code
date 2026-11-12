# WebView invokes the chart and desktop-pet bridges by their JavaScript names.
-keepclassmembers,allowoptimization class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Shizuku instantiates this Binder service in another process via reflection.
-keep,allowoptimization class com.yukisoffd.lyracode.system.ShizukuShellUserService {
    public <init>();
}

# Room creates WorkManager's generated database through a no-arg constructor.
-keep,allowoptimization class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
}

# JavaMail / activation discover providers and MIME handlers through META-INF
# registries and class names, outside R8's ordinary reachability graph.
-keep,allowoptimization class com.sun.mail.imap.IMAPStore { public <init>(...); }
-keep,allowoptimization class com.sun.mail.imap.IMAPSSLStore { public <init>(...); }
-keep,allowoptimization class com.sun.mail.pop3.POP3Store { public <init>(...); }
-keep,allowoptimization class com.sun.mail.pop3.POP3SSLStore { public <init>(...); }
-keep,allowoptimization class com.sun.mail.smtp.SMTPTransport { public <init>(...); }
-keep,allowoptimization class com.sun.mail.smtp.SMTPSSLTransport { public <init>(...); }
-keep,allowoptimization class com.sun.mail.handlers.** { public <init>(); }

# JSch stores cipher/MAC/key-exchange/authentication implementation names in
# its configuration table. Authentication implementations can have package-private
# no-arg constructors, so retain constructors regardless of their visibility.
-keepclasseswithmembers,allowoptimization class !com.jcraft.jsch.Log4j2Logger,!com.jcraft.jsch.Slf4jLogger,com.jcraft.jsch.** {
    <init>();
}

# Optional JSch providers are probed dynamically. The app uses the algorithms
# available on Android and does not bundle Bouncy Castle, JNA or junixsocket.
-dontwarn org.bouncycastle.**
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
# Kerberos/GSSAPI is another optional JSch backend, unavailable on Android.
-dontwarn org.ietf.jgss.**
