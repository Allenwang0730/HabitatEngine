# Habitat ProGuard rules
# 引擎/宿主大量依赖 Gson 反射与按名注册，暂时整体保留（改窄需逐一验证）
-keep class com.ailun.habitat.** { *; }
-keep class rikka.shizuku.** { *; }

# Ktor / Netty：服务端引擎引用了 Android 上不存在的可选依赖
-dontwarn io.netty.**
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.eclipse.jetty.**
-dontwarn reactor.blockhound.**
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.jboss.marshalling.**
-dontwarn com.google.protobuf.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
-dontwarn sun.misc.**
-dontwarn sun.security.**
-dontwarn io.micrometer.**
-dontwarn com.typesafe.config.**
# Netty 通过反射实例化 Channel/SelectorProvider，并用 AtomicXxxFieldUpdater 访问 volatile 字段
-keep class io.netty.channel.socket.nio.** { *; }
-keep class io.netty.channel.** extends io.netty.channel.Channel { <init>(...); }
-keepclassmembers class io.netty.** { volatile <fields>; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
