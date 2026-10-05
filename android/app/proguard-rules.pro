# Retrofit, OkHttp, kotlinx.serialization, Room and WorkManager ship their own consumer rules.
# Belt and braces for the API DTOs and the Retrofit interface (reflection on generic suspend signatures):
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep class dev.mealprep.app.data.api.** { *; }
# WorkManager stores each job's worker class name in its database: keep the names stable across releases.
-keepnames class * extends androidx.work.ListenableWorker
