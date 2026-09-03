# Play reviewer instructions

These instructions are reserved for a later Play review stage. A public reviewer environment,
reviewer study, and reviewer URL are not required to create or exercise the current Internal Testing
release and therefore do not gate it.

1. In Play Console App access, provide one durable reviewer-enrollment URL bound to a synthetic
   reviewer study and participant. The URL must remain accessible, reusable, maintained, and
   location-independent throughout review. Each visit mints a fresh scoped one-time app enrollment
   credential; the durable URL itself is not an API key and cannot read research data.
2. Open the link on the test device. It launches Chronicle with the study and participant fields
   filled in and displays the public HTTPS study-server destination. The code remains in the URL
   fragment and is consumed only by enrollment.
3. Tap Enroll Device. Review each study-configured data type; allow or decline as directed by the
   Play Console instructions. Confirm that the displayed server URL matches the destination named
   in the review instructions. Chronicle supports one active study/server on a device at a time;
   different researcher- or institution-operated servers can use the same published app.
4. Open Data Sharing. Sensitive OS access is requested only for an accepted module that needs it,
   and each settings jump is preceded by a separate disclosure.
5. The Play reviewer study requests only app usage, basic device telemetry, upload diagnostics, and
   unlock user identification. The Play artifact does not expose Health Connect, activity/sleep
   recognition, physical sensor, notification/media, audio, or AccessibilityService collection.
6. To review unlock identification, enable Identify user in Settings, allow notifications, lock and
   unlock the device, choose a synthetic user category in the prompt, then disable Identify user and
   confirm the foreground notification stops.
7. To withdraw from Chronicle collection, uninstall the app. Android removes Chronicle's on-device
   app data and future collection stops. Uninstalling cannot notify the enrolled study server or
   request deletion of records already uploaded; questions about those records go to the research
   team identified in the study consent.

Before Play review, replace this paragraph with the exact synthetic review study identity, durable
reviewer URL, reset instructions, server hostname, allowed module list, and configuration reviewers
will see. Prove the
URL can be used repeatedly after app reinstall/reset. Never put a production participant credential,
device API key, or server secret in Play Console.
