# Data Safety form source of truth

This is the approved Play Console declaration for the open artifact Play ships
(`openRelease`). Reconcile it against the final AAB and every supported deployment model
again before submission. In Play's terminology, data sent from the device to an enrolled Chronicle
server is "collected" even when encrypted and used only for research.

## Top-level answers

- Does the app collect or share required user data types? **Yes — collects.**
- Is all collected user data encrypted in transit? **Yes.**
- Can users request deletion through the Play app? **Yes.** Open Settings > Study server details >
  Withdraw from study. Withdrawing stops collection at once and sends an authenticated withdrawal
  request to the enrolled study server. Data already collected is kept or erased by the study team,
  according to this study's withdrawal policy. Keep the app installed until the request is
  acknowledged; uninstalling alone removes on-device app data but cannot send a server withdrawal or
  deletion request.
- Does the app share user data? The artifact supports one active consented study per installation
  and can send that study's records directly to its researcher- or institution-operated server.
  The app publisher is not automatically the study sponsor, server operator, or recipient, so a
  **No** answer is not a safe package-wide default. Declare the applicable types as
  **shared** unless the Play account owner and counsel document that every supported transfer
  satisfies Play's user-initiated/prominent-disclosure exception. Record that determination with the
  submission evidence.
- Ads, marketing, sale, or advertising profiles: **None.**

## Data types to mark as collected

Every module below can be requested by a study on the open artifact that Play ships
(`play_variant` in `privacy.properties`). A study can mark any module required, so each type is
answered **Required** unless the owner documents that every supported study offers it as optional.

| Play data type | Chronicle evidence (module) | Collected/shared | Purpose |
| --- | --- | --- | --- |
| Personal info > User IDs | Study ID, participant ID | Collected and shared with the enrolled study server | App functionality |
| Device or other IDs | Random UUID generated for each enrollment (`random_device_id`) | Collected and shared | App functionality; fraud prevention, security, and compliance |
| App activity > App interactions | App open/close and foreground transitions, and every Android usage event (Activity Resumed, Activity Paused, Activity Stopped, Screen Interactive, Screen Non-interactive, Keyguard Shown, Keyguard Hidden, Device Startup, Device Shutdown, Configuration Change, User Interaction, Shortcut Invocation, Foreground Service Start, Foreground Service Stop, Notification Seen, Notification Interruption and Standby Bucket Changed, and any other event type by its number; which app and when, no notification text) (`usage_events`), in-app screen class (`in_app_activity_class`) | Collected and shared | App functionality; analytics |
| App activity > Installed apps | Package names in usage and network-usage records | Collected and shared | App functionality |
| App activity > Other actions | Unlock identification selection (`user_identification`); per-app bytes sent/received (`app_network_usage`) | Collected and shared | App functionality; analytics |
| Health and fitness > Fitness info | Accelerometer, gyroscope, magnetometer, gravity, linear acceleration, rotation vector, significant motion, tilt, light, proximity and screen-orientation samples (`sensor_*`), declared conservatively because motion data can reveal physical activity | Collected and shared | App functionality |
| App info and performance > Crash logs | App-crash, native-crash and app not responding (ANR) counts with first/last occurrence times (`upload_telemetry`); no stack traces | Collected and shared with the enrolled study server | App functionality; analytics |
| App info and performance > Diagnostics | Device brand, model, product and Android/API version sent at enrollment; battery and connectivity state; device-settings snapshots (display, sound, accessibility, rotation, battery saver, location-services state, thermal state, free/total storage); sensor availability; bounded upload-failure category/count/timing/status/exception class | Collected and shared because approved device telemetry and upload diagnostics are sent to the enrolled study server | App functionality; analytics |

The app keeps a live local view of queue, retry, and connection state. When a research-data upload
fails, it also stores a separate record of that failure containing the affected upload family, failure category
and count, first/last occurrence, HTTP status when available, and bounded exception class. That record is later transmitted to the
exact enrolled study server under the active participant/device enrollment and retained there through the study’s data-retention period or explicit erasure, as disclosed
in the approved-module registry and the study consent.
The same diagnostic channel sends bounded app-crash, native-crash and app not responding (ANR)
counts with first/last occurrence times. These are first-party crash reports even without a
crash-reporting SDK or stack traces.
Participant identity and device identity come from the authenticated request rather
than being duplicated inside the diagnostic body; the destination is inherent in the authenticated
connection. Server URLs, free-form error text, invitation secrets, API keys, authorization tokens,
passwords, and stack traces are not diagnostic fields.

Do **not** mark precise/approximate location, contacts, SMS/MMS, call logs, photos/videos, voice or
sound recordings, music or other audio files, files and documents, web browsing history, search
history, financial information, emails, messages, health info (Health Connect, sleep) or music/media
titles, audio playback status, Interaction Events module records or Notification Activity module records: no module in this artifact collects them and the artifact
requests no permission that reads them. The sensor ceiling is 200 Hz; the app does not request
HIGH_SAMPLING_RATE_SENSORS. The location-services setting is an on/off state, not a
location.

## Handling details for every collected type

- Processing is not ephemeral: records are stored in encrypted local queues until upload or deletion.
- Collection is disclosed for the active study in its consent, enrollment module review, and
  Data Sharing UI.
- The enrollment screen displays the destination server, and sensitive-access disclosures say that
  records are uploaded to the enrolled study server shown during enrollment.
- Optionality must be answered per type: study modules can be required for enrollment or optional.
  Do not blanket-answer
  "optional" if the reviewer study marks a type required.
- Retention follows the IRB-approved protocol and consent. In-app withdrawal stops collection at once.
  Data already collected is kept or erased by the study team, according to the study's withdrawal
  policy. Uninstalling stops future collection and removes on-device app data but does not request server deletion.
  Legal or research-integrity retention must be described by the study consent and handled by the
  named research team.

## Owner to-do

- Change Play Console's **Can users request deletion through the app?** answer from **No** to **Yes**.
- Use this exact deletion guidance: **"In Chronicle, open Settings > Study server details > Withdraw from
  study. Withdrawing stops collection at once and sends a withdrawal request to your study server.
  Data already collected is kept or erased by the study team, according to this study's withdrawal
  policy. Keep Chronicle installed until the request is acknowledged. Uninstalling alone does not
  request server deletion. Contact the research team named in your consent for questions."**

## SDK audit

The open artifact includes AndroidX/Android platform libraries only. It links no Google Play services
or Health Connect client library: Health Connect, Activity Recognition and Sleep ship in the research
build alone, and `:app:verifyNoGoogleSdkRuntime` fails if they reach the open runtime. It includes no ads SDK, analytics SDK,
Crashlytics, Firebase, attribution SDK, or social SDK. Re-run the release runtime SBOM and this
audit for every AAB; `scripts/play-upload.py check` refuses an AAB whose permissions or special
services differ from `privacy.properties`.
