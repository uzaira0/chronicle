# Chronicle

Android data collection app for behavioral research studies.

## Build and install the sideload (`open`) APK

Release builds refuse to configure without release signing, so set that up first:

```bash
cp app/signing.properties.example app/signing.properties
# Edit app/signing.properties: point storeFile at a non-debug keystore and fill its
# passwords and alias. Never commit this file or the keystore.
./gradlew :app:assembleOpenRelease
adb install -r app/build/outputs/apk/open/release/app-open-release.apk
```

Unit tests: `./gradlew :app:testOpenDebugUnitTest`. Lint: `./gradlew :app:lintOpenRelease`.

### Build settings

Set either the environment variable or the Gradle property.

| Environment variable | Gradle property | Needed for |
|---|---|---|
| `CHRONICLE_MODELS_DIR` | `-PchronicleModelsDir` | A `chronicle-models` checkout other than `../chronicle-models` (the monorepo layout is used when neither exists). Required while a root-repository Gradle build runs on the same checkout: both builds write `chronicle-models/build`, and a jar rewritten under a running compiler or test JVM fails with NoClassDefFoundError or corrupt-archive errors. Point it at a separate copy of the same commit (`git -C ../chronicle-models archive HEAD`). |
| `CHRONICLE_SIGNING_PROPERTIES` | `chronicleSigningProperties` | Optional path to the release signing properties file; defaults to `app/signing.properties`. Required signing material is validated for release artifacts. |
| `CHRONICLE_PRODUCTION_HOST` | `chronicleProductionHost` | `research` builds (required); the fixed server host. |
| `CHRONICLE_RC_ID` | `chronicleRcId` | Store release candidates; recorded in the build. |

Put Gradle properties in `~/.gradle/gradle.properties`, not in this repository.

## Third-party SDK inventory (`research` flavor)

The `amazon` and `open` flavors ship no third-party SDK; `:app:verifyNoGoogleSdkRuntime` fails if
Google Play services or Health Connect reaches either runtime. Play ships `open` (see
`store/play/data-safety.md`, SDK audit). The `research` flavor adds:

| SDK | Version | Used by | Talks to | When |
|---|---|---|---|---|
| `com.google.android.gms:play-services-location` (Activity Recognition, Sleep API) | 21.3.0 | `SleepActivityCaptureController`, `SleepActivityReceiver` | Google Play services on the device, under Google's terms | Only after the participant accepts the physical-activity or sleep module |
| `androidx.health.connect:connect-client` | 1.1.0-rc03 | `AndroidHealthMetricSource`, `HealthConnectPermissions` | The Health Connect app on the device | Only after the participant accepts the Health Connect module and grants its read permissions |

The research in-app policy (`platform_privacy_policy_full` in `app/src/research/res/values/platform_policy.xml`)
names them; the `open` policy (`app/src/main/res/values/strings.xml`) states that none ships. Update
both when `app/build.gradle` adds an `openImplementation` or `researchImplementation` SDK.

## HTTP timeouts

`Utils.createRetrofitAdapter` (`app/src/main/java/com/openlattice/chronicle/utils/Utils.kt`) is the one
HTTP client. Upstream sets none, so OkHttp's defaults applied there (10 s connect, read and write; no call
deadline).

| Setting | Value | Effect |
|---|---|---|
| Interactive call deadline (`CallDeadline.INTERACTIVE_TIMEOUT_MS`) | 30 s | Enrollment, preview and health checks show the connection error after at most 30 s |
| Connect timeout | 30 s | A server that does not accept the connection fails the attempt after 30 s |
| Read and write timeouts | 60 s each | A stalled upload read or write fails after 60 s and retries later |
| Collected-upload call deadline (`UploadTransport`) | none | Large uploads are bounded only by the read and write timeouts |
