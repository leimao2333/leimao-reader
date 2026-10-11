# 项目混淆规则
# Rhino JS 引擎需保留（使用反射）
-keep class org.mozilla.javascript.** { *; }

# Gson 数据模型
-keep class com.novelreader.domain.model.** { *; }
-keep class com.novelreader.data.model.** { *; }

# 序列化相关
-keepattributes *Annotation*
-keepattributes Signature
