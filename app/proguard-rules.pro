# LearnAnywhere (app): keep Gemini & TTS internals intact; only strip debug logging.
-keep class com.learnanywhere.audio.AudiobookPlayer { *; }
-keep class com.learnanywhere.agent.** { *; }
-dontwarn kotlinx.coroutines.**
-dontwarn okhttp3.**
-keep class com.google.gson.** { *; }
-keepclassmembers class * extends okhttp3.Interceptor { *; }
