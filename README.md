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
| `CHRONICLE_MODELS_DIR` | `-PchronicleModelsDir` | A `chronicle-models` checkout other than `../chronicle-models` (the monorepo layout is used when neither exists). |
| `GITHUB_ACTOR`, `GITHUB_TOKEN` | `gpr.user`, `gpr.key` | Resolving packages from GitHub Packages; not needed inside the monorepo. |
| `CHRONICLE_PRODUCTION_HOST` | `chronicleProductionHost` | `research` builds (required); the fixed server host. |
| `MOBILE_SIGNING_SECRET` | `mobileSigningSecret` | `research` builds only; the deployment-wide request-signing key. Public flavors do not use it. |
| `CHRONICLE_RC_ID` | `chronicleRcId` | Store release candidates; recorded in the build. |

Put Gradle properties in `~/.gradle/gradle.properties`, not in this repository.

## Third-party SDK inventory (`open` and `research` flavors)

The Play and Amazon flavors ship no third-party SDK (see `store/play/data-safety.md`, SDK audit).
The `open` and `research` flavors add one:

| SDK | Version | Used by | Talks to | When |
|---|---|---|---|---|
| `com.google.android.gms:play-services-location` (Activity Recognition, Sleep API) | 21.3.0 | `SleepActivityCaptureController`, `SleepActivityReceiver` | Google Play services on the device, under Google's terms | Only after the participant accepts the physical-activity or sleep module |

The in-app policy (`app/src/googleServices/res/values/strings.xml`, `platform_privacy_policy_full`)
names it. Update both when `app/build.gradle` adds an `openImplementation` or `researchImplementation` SDK.
