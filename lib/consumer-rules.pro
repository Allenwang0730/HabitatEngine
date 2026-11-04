# Habitat 引擎 consumer 规则：宿主 App 开启 R8 时保留 Gson 反射用到的模型字段。

# 工作流模型（JSON 反序列化目标）
-keep class com.ailun.habitat.WorkflowGraph { *; }
-keep class com.ailun.habitat.WorkflowNode { *; }
-keep class com.ailun.habitat.HabitatWorkflow { *; }
-keep class com.ailun.habitat.TriggerConfig { *; }
-keep class com.ailun.habitat.TriggerConfig$* { *; }
# 工作区 index.json 用 Gson 按字段名/枚举名读写：混淆后旧索引读不回来（条目全丢），必须保留
-keep class com.ailun.habitat.workspace.** { *; }

# Gson：@SerializedName 字段、TypeToken 泛型签名
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
