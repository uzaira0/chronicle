# Google Play readiness discovery report — 2026-08-14

## Scope and exclusions

Goal: prepare the existing BCM Chronicle Android Play flavor and its supporting public policy pages
for a first Google Play Console submission. Excluded from neutral discovery terms because they are
already fixed by the product/request: Google Play/Play Console, Chronicle, Baylor College of Medicine
(BCM), Android, Health Connect, AccessibilityService, foreground services, Data Safety, Play App
Signing, app bundles, the production hostname, the existing package ID, and the IRB/study model.

## Neutral discovery queries

1. mobile application marketplace submission requirements health research data privacy 2026 official
2. app distribution platform sensitive health data declaration privacy policy requirements official developer
3. mobile app store background service permission declaration review video requirements official
4. mobile marketplace accessibility service non accessibility tool policy disclosure official
5. mobile application target api deadline 2026 official distribution platform
6. mobile app account deletion data deletion policy requirements official marketplace
7. mobile application screenshots icon feature graphic specifications official store listing
8. mobile app app signing bundle upload production release official developer
9. research study mobile application health data informed consent marketplace policy official
10. mobile marketplace user data prominent disclosure consent sensitive permissions official
11. mobile app testing track reviewer credentials restricted access official developer
12. mobile app sdk data safety third party disclosure official developer

## Discovery coverage

| Category | Result |
| --- | --- |
| Standards/specifications | Adopt current Play User Data, Data Safety, Health Content, Accessibility, FGS, target API, signing, and asset requirements. |
| Academic/survey literature | Research-consent guidance supports IRB-approved purpose, procedures, risks, confidentiality, contacts, and withdrawal; no new runtime dependency. |
| Open-source frameworks | No new framework is needed. Existing Android/AndroidX implementations cover the required behavior. |
| AI/ML | Not applicable; the app does not add an on-device or hosted model. |
| Platforms/SaaS | Play Console App content, Data Safety, Health Apps, App access, Play App Signing, Internal testing, and pre-launch report are required operational steps. |
| Adjacent fields | Human-subject protections and electronic informed-consent guidance inform legal/IRB review. |
| Registries/ecosystem | Audit the Gradle release runtime/SBOM and Google Play SDK Index for every candidate; current source contains no ads, analytics, Firebase, attribution, or social SDK. |
| Community sources | Searched only for failure vocabulary; decisions use official primary sources. |
| Hardware | No new hardware dependency. Signed phone/tablet evidence is required for listing and declaration videos. |
| Historical/replacement search | AAB + Play App Signing replace direct APK production distribution for new Play apps; the Health Apps declaration is the current health-policy path. |

## Current primary sources

- [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311)
- [Data Safety form definitions](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Prominent disclosure and consent](https://support.google.com/googleplay/android-developer/answer/11150561)
- [AccessibilityService policy](https://support.google.com/googleplay/android-developer/answer/10964491)
- [Health Content and Services](https://support.google.com/googleplay/android-developer/answer/16679511)
- [Health Apps declaration](https://support.google.com/googleplay/android-developer/answer/14738291)
- [Health research categories and consent](https://support.google.com/googleplay/android-developer/answer/13996367)
- [Publish a Health Connect app](https://developer.android.com/health-and-fitness/health-connect/publish)
- [Foreground service declarations](https://support.google.com/googleplay/android-developer/answer/13392821)
- [Prepare an app for review / App access](https://support.google.com/googleplay/android-developer/answer/9859455)
- [2026 target API requirements](https://developer.android.com/google/play/requirements/target-sdk)
- [App signing](https://developer.android.com/studio/publish/app-signing)
- [Store listing assets](https://support.google.com/googleplay/android-developer/answer/9866151)
- [BCM privacy/compliance contact](https://www.bcm.edu/about-us/our-campus/compliance)

## Decision ledger

- **Adopt:** Play's existing Console declarations, Internal testing, pre-launch report, App Signing,
  AAB format, and current target API. Do not build substitutes.
- **Compose:** derive Console answers from the merged Play manifest, wire DTOs, SDK inventory, public
  privacy page, consent UI, and IRB-approved protocol.
- **Adapt:** public Caddy routing, policy copy, listing copy, prominent sensitive-access disclosures,
  and the release verification script.
- **Build:** only small repository artifacts/checks that Play does not provide: console-answer files,
  exact declaration scripts/copy, and regression checks.

## Verified gaps and changes

- The live `/privacy` and `/withdrawal` URLs returned 401 Basic Auth on 2026-08-14. Source routing is
  changed so those two SPA pages bypass the researcher dashboard guard; deployment and a public 200
  smoke test remain required.
- The app previously forced Usage Access before loading the study or showing module consent. It now
  enrolls without the grant and requests it from Data Sharing only for an active accepted module,
  after a purpose/use/sharing disclosure.
- Target SDK 36 already meets the 2026 new-app/update requirement.
- The Play flavor now removes Health Connect, activity/sleep recognition, accessibility,
  notification/media, audio, and continuous hardware-sensor collection. Only unlock identification
  retains a `specialUse` foreground service and requires a declaration/video.
- Data Safety, no-health/no-accessibility, single-FGS, listing, reviewer, and asset source documents
  now exist under `store/play/`.

## Smoke and release plan

1. Run web unit tests and `selfhost/verify-config.sh`.
2. Build/process the merged `playRelease` manifest; run `scripts/verify-store-readiness.sh play`.
3. Run Android unit tests, lint, release runtime dependency/SBOM checks, and 16 KB native-library check.
4. Produce a signed `bundlePlayRelease` AAB with no global mobile-signing secret, using the upload key
   registered for `com.bcm.chronicle`; inspect the AAB/package/version/signing identity and verify that
   enrollment installs only the per-device API credential issued by the selected study server.
5. Deploy the web/config change and verify unauthenticated HTTPS 200 responses and readable policy
   content for `/privacy` and `/withdrawal` from outside the BCM network.
6. Exercise fresh enrollment on phone/tablet: consent precedes all sensitive access, declined modules
   remain inert, revocation stops collection, and withdrawal stops/clears as stated.
7. Capture synthetic-data screenshots and declaration videos from that exact signed candidate.
8. Upload to Internal testing, complete Console forms from these files, add persistent reviewer access,
   run pre-launch report, resolve findings, then stage production rollout.
