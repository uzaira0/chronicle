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

Unit tests: `./gradlew :app:testOpenReleaseUnitTest`. Lint: `./gradlew :app:lintOpenRelease`.

## Third-party SDK inventory (`open` and `research` flavors)

The Play and Amazon flavors ship no third-party SDK (see `store/play/data-safety.md`, SDK audit).
The `open` and `research` flavors add one:

| SDK | Version | Used by | Talks to | When |
|---|---|---|---|---|
| `com.google.android.gms:play-services-location` (Activity Recognition, Sleep API) | 21.3.0 | `SleepActivityCaptureController`, `SleepActivityReceiver` | Google Play services on the device, under Google's terms | Only after the participant accepts the physical-activity or sleep module |

The in-app policy (`app/src/googleServices/res/values/strings.xml`, `platform_privacy_policy_full`)
names it. Update both when `app/build.gradle` adds an `openImplementation` or `researchImplementation` SDK.
