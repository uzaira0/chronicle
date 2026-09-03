# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-dontwarn org.joda.convert.FromString
-dontwarn org.joda.convert.ToString

# chronicle-models retains Jakarta Validation annotations as server-side contract
# metadata, but Android deliberately excludes that runtime because its TYPE_USE
# reflection metadata crashes API 23. No Android code invokes a Validator.
-dontwarn jakarta.validation.Valid
-dontwarn jakarta.validation.constraints.DecimalMax
-dontwarn jakarta.validation.constraints.DecimalMin
-dontwarn jakarta.validation.constraints.Max
-dontwarn jakarta.validation.constraints.Min
-dontwarn jakarta.validation.constraints.NotBlank
-dontwarn jakarta.validation.constraints.NotNull
-dontwarn jakarta.validation.constraints.Pattern
-dontwarn jakarta.validation.constraints.Size

# ── Kotlin runtime & metadata ────────────────────────────────────────────────
# R8 in AGP 9 aggressively strips Kotlin internals.  Intrinsics contains
# null-check helpers (checkNotNullParameter, etc.) that the compiler injects
# at every non-null parameter boundary.  Stripping them causes
# NoSuchMethodError at the very start of any Kotlin function.
-keep class kotlin.jvm.internal.Intrinsics { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# Permit R8 to consolidate otherwise-unconstrained classes into one package. Classes whose
# names are persisted, serialized, loaded reflectively, referenced through JNI, or inspected
# by the release verifier are protected by the targeted rules below and AGP's generated rules.
-repackageclasses 'com.bcm.chronicle.optimized'

# ── WorkManager Workers ─────────────────────────────────────────────────────
# WorkManager persists worker class names and invokes the two-argument constructor. Preserve that
# reflective identity without pinning the complete implementation graph: excluded Play workers
# must remain shrinkable once their distribution branch is unreachable.
-keepnames class * extends androidx.work.ListenableWorker
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# The immutable candidate and approved-registry identities are deliberately part of the
# inspectable release surface. They contain no credential and must survive R8 for the AAB
# verifier and delivered-split diagnostics.
# Keep field names for artifact verification while still allowing R8 to propagate the immutable
# flavor constants and remove code belonging to excluded Play capabilities.
-keep,allowoptimization class com.openlattice.chronicle.BuildConfig { *; }

# ── Moshi reflective and polymorphic serialization ──────────────────────────
# Moshi's reflection adapter needs constructors, names, and Kotlin metadata for
# every DTO it creates. ChronicleJson owns the explicit polymorphic allowlists.
-keep interface com.openlattice.chronicle.android.ChronicleSample
-keep interface com.openlattice.chronicle.sources.SourceDevice

# chronicle-models DTO packages used by Retrofit and local queue persistence.
-keep interface com.openlattice.chronicle.android.ChronicleSample
-keep class com.openlattice.chronicle.android.ChronicleData { *; }
-keep class com.openlattice.chronicle.android.LegacyChronicleData { *; }
-keep class com.openlattice.chronicle.android.ChronicleUsageEvent** { *; }
-keep class com.openlattice.chronicle.android.AndroidDeviceSensorAvailability { *; }
-keep class com.openlattice.chronicle.android.AndroidSensorSetting** { *; }
-keep class com.openlattice.chronicle.android.AndroidSensorType { *; }
-keep class com.openlattice.chronicle.android.InteractionPointerCaptureCapability { *; }
-keep class com.openlattice.chronicle.sources.** { *; }
-keep class com.openlattice.chronicle.data.** { *; }
-keep class com.openlattice.chronicle.base.** { *; }
-keep class com.openlattice.chronicle.study.** { *; }
-keep class com.openlattice.chronicle.crypto.** { *; }
-keep class com.openlattice.chronicle.notifications.StudyNotificationSettings { *; }
-keep class com.openlattice.chronicle.participantaccess.MobileReminderConfiguration { *; }
-keep class com.openlattice.chronicle.participantaccess.MobileReminderForm { *; }
-keep class com.openlattice.chronicle.participantaccess.ParticipantFormKind { *; }
-keep class com.openlattice.chronicle.collection.AndroidDataCollectionSetting** { *; }
-keep class com.openlattice.chronicle.collection.ConsentTrigger { *; }
-keep class com.openlattice.chronicle.collection.AndroidConnectivityStateEvent { *; }
-keep class com.openlattice.chronicle.collection.AndroidDeviceSettingsEvent { *; }
-keep class com.openlattice.chronicle.collection.BatterySample { *; }
-keep class com.openlattice.chronicle.collection.BatteryPolicy { *; }
-keep class com.openlattice.chronicle.collection.NetworkPolicy { *; }
-keep class com.openlattice.chronicle.collection.InteractionPolicy { *; }
-keep class com.openlattice.chronicle.collection.Collection* { *; }

# App-owned DTOs persisted or transported through reflective Moshi adapters.
-keep class com.openlattice.chronicle.models.ExtractedUsageEvent { *; }
-keep class com.openlattice.chronicle.models.ExtractedActivities { *; }
-keep class com.openlattice.chronicle.models.ExtractUsageStat { *; }
-keep class com.openlattice.chronicle.collection.state.PendingCollectionAckRecord { *; }

# ── Apache Olingo ────────────────────────────────────────────────────────────
# FullQualifiedName is adapted explicitly and is also used by Retrofit request
# maps. Preserve Olingo's public model surface across optimization.
-keep class org.apache.olingo.** { *; }
-dontwarn org.apache.olingo.**

# ── chronicle-api dependency ─────────────────────────────────────────────────
# Retrofit creates the service interface dynamically; Moshi reflects over the
# request/response DTOs in these app-owned packages.
-keep interface com.openlattice.chronicle.api.ChronicleStudyApi { *; }
-keep class com.openlattice.chronicle.api.EnrollmentResponse { *; }
-keep class com.openlattice.chronicle.api.EnrollmentPreviewResponse** { *; }
-keep class com.openlattice.chronicle.api.MobileEnrollmentManifest { *; }
-keep class com.openlattice.chronicle.serialization.ChronicleJson** { *; }
-keep class com.openlattice.chronicle.serialization.JsonSerializer { *; }
-keep class com.openlattice.chronicle.serialization.ChronicleCallAdapterFactory { *; }
-keep class com.openlattice.chronicle.serialization.ChronicleCallException { *; }

# ── SQLCipher (net.zetetic:sqlcipher-android) ────────────────────────────────
# The native libsqlcipher.so resolves net.zetetic.database.sqlcipher.SQLiteDatabase
# members by name via JNI — notably the long field `mNativeHandle`. R8 release
# minification renames/strips them, so the native JNI lookup throws
# NoSuchFieldError "mNativeHandle" → SIGABRT the first time ChronicleDb opens
# the encrypted DB in MainActivity.onCreate. Debug builds aren't minified,
# which masked this. Keep the whole SQLCipher surface (Zetetic's official
# consumer rule).
-keep class net.zetetic.database.** { *; }
-keep interface net.zetetic.database.** { *; }
-keepclassmembers class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
