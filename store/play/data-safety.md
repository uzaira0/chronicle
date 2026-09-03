# Data Safety form source of truth

This is the approved Play Console declaration completed by the account owner for the current
`playRelease` contract. Reconcile it against the final AAB and every supported deployment model
again before submission. In Play's terminology, data sent from the device to an enrolled Chronicle
server is "collected" even when encrypted and used only for research.

## Top-level answers

- Does the app collect or share required user data types? **Yes — collects.**
- Is all collected user data encrypted in transit? **Yes.**
- Can users request deletion through the Play app? **No.** To withdraw from Chronicle collection,
  uninstall the app. Android then removes Chronicle's on-device app data and future collection
  stops. An uninstall cannot notify the enrolled study server or request deletion of records already
  uploaded; questions about those records go to the research team identified in the study consent.
- Does the app share user data? The artifact supports one active consented study per installation
  and can send that study's records directly to its researcher- or institution-operated server. A
  an installation can be removed when the participant withdraws. The app publisher is not
  automatically the study sponsor, server operator, or recipient. A single-operator
  **No** answer is therefore not a safe package-wide default. Declare the applicable types as
  **shared** unless the Play account owner and counsel document that every supported transfer
  satisfies Play's user-initiated/prominent-disclosure exception. Record that determination with the
  submission evidence.
- Ads, marketing, sale, or advertising profiles: **None.**

## Data types to mark as collected

| Play data type | Chronicle evidence | Collected/shared | Required or optional | Purpose |
| --- | --- | --- | --- | --- |
| Personal info > User IDs | Study ID, participant ID | Collected and shared with the enrolled study server | Required | App functionality |
| Device or other IDs | App-generated source-device/device-instance UUID | Collected and shared with the enrolled study server | Required | App functionality; fraud prevention, security, and compliance |
| App activity > App interactions | App foreground/background transitions and optional in-app activity class | Collected and shared with the enrolled study server | Required | App functionality; analytics |
| App activity > Installed apps | Package names observed in app-usage records | Collected and shared with the enrolled study server | Required in the submitted Console declaration | App functionality |
| App activity > Other actions | Whether the person using the device selects study participant or someone else after unlock | Collected and shared with the enrolled study server | Optional | App functionality |
| App info and performance > Diagnostics | Battery state, thermal state, free storage, connectivity state, and bounded upload-failure category/count/timing/status/exception class | Collected and shared because approved device telemetry and upload diagnostics are sent to the enrolled study server | Required | App functionality; analytics |

The app keeps a live local view of queue, retry, and connection state. When a research-data upload
fails, it also stores a bounded aggregate containing the affected upload family, failure category
and count, first/last occurrence, HTTP status when available, and bounded exception class. That aggregate is later transmitted to the
exact enrolled study server under the active participant/device enrollment and retained there only
until the earlier of 30 days from its last occurrence or 30 days from its first server receipt.
Participant identity and device identity come from the authenticated request rather
than being duplicated inside the diagnostic body; the destination is inherent in the authenticated
connection. Server URLs, free-form error text, invitation secrets, API keys, authorization tokens,
passwords, and stack traces are not diagnostic fields.

Do **not** mark precise/approximate location, contacts, SMS/MMS, photos/videos, audio files, files and
documents, web browsing history, financial information, other user-generated content, other app
performance data, or microphone recordings. Also do not mark Health and fitness, notification content/metadata, media content,
accessibility interaction data, or physical sensor data: the Play artifact does not expose those
collectors or request their permissions.

## Handling details for every collected type

- Processing is not ephemeral: records are stored in encrypted local queues until upload or deletion.
- Collection is disclosed for the active study in its consent, enrollment module review, and
  Data Sharing UI.
- The enrollment screen displays the destination server, and sensitive-access disclosures say that
  records are uploaded to the enrolled study server shown during enrollment.
- Optionality must be answered per type: study modules can be required for enrollment or optional.
  Do not blanket-answer
  "optional" if the reviewer study marks a type required.
- Retention follows the IRB-approved protocol and consent. Uninstalling stops future collection and
  removes on-device app data but does not request server deletion. Any process for already-uploaded
  records, including legal or research-integrity retention, must be described by the study consent
  and handled by the named research team.

## SDK audit

The minimal Play flavor includes AndroidX/Android platform libraries. It deliberately excludes
Google Play Services Activity Recognition and AndroidX Health Connect and includes no ads SDK,
analytics SDK, Crashlytics, Firebase, attribution SDK, or social SDK. The authoritative Play API
inventory reported no uploaded APK, AAB, or active track for this package and a maximum uploaded
version code of zero. Re-run the release runtime SBOM and this audit for every AAB.
