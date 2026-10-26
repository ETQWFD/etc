# Reai: keep llama.cpp JNI callback interface & provider names (JNI uses hardcoded method names)
-keep class me.rerere.ai.local.LlamaNative$TokenCallback { *; }
-keepclassmembers class * implements me.rerere.ai.local.LlamaNative$TokenCallback { *; }
-keep class me.rerere.ai.provider.providers.local.** { *; }
-keep class me.rerere.ai.local.** { *; }
