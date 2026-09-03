# Google Play screenshot capture contract

No screenshot PNG is currently approved or tracked. The obsolete pre-minimal set was removed because
it showed Health Connect UI that is absent from the Play artifact. Do not restore or submit that set.

Capture replacements only after one release candidate is sealed and installed from its signed AAB
split set (or from the identical Internal Testing delivery). Every capture must be bound to:

- package `com.bcm.chronicle`, version code/name, release-candidate identifier, and AAB SHA-256;
- installed split paths, split SHA-256 values, and signing-certificate SHA-256;
- the approved-module-registry SHA-256 embedded in the artifact;
- the synthetic test deployment receipt and test-study identity; and
- the emulator profile, Android API level, logical density, pixel dimensions, and capture command.

Use only a persistent synthetic test study and fresh single-use enrollment credentials. No real
participant, health, app-usage, unlock-response, notification, server credential, access code, API
key, hostname, or other private operational data may appear. Remove PNG metadata and an unused alpha
channel without retouching, compositing, or changing pixels.

## Required form factors

| Directory | Required profile | Required image shape |
| --- | --- | --- |
| `phone` | Google Play phone | portrait, 9:16, 1080 × 1920 |
| `tablet-7` | Google Play tablet, at least 600 dp minimum width | portrait, 9:16, 1080 × 1920 |
| `tablet-10` | Google Play tablet, at least 720 dp minimum width | portrait, 9:16, 1440 × 2560 |

Create the following files in each directory. Alt text must remain truthful, specific, and at most
140 characters after the exact final UI is captured.

1. `01-enrollment-review.png` — study identity, purpose, collection summary, privacy link, and accept/cancel controls.
2. `02-overview.png` — one active synthetic study, collection state, upload state, and server-health status.
3. `03-data-sharing.png` — only minimal approved modules and participant-controlled collection choices.
4. `04-unlock-identification.png` — the optional post-unlock user-category prompt and its skip/control path.
5. `05-uploads.png` — local queue and successful synthetic delivery status without participant records.
6. `06-settings.png` — settings and the uninstall-based exit disclosure; no withdrawal button or
   server-deletion claim may appear.

Health Connect, activity/sleep recognition, physical sensors, notification/media access, audio,
AccessibilityService, location, and other excluded surfaces must not appear anywhere in the images.
The capture gate must reject unexpected files, missing required files, wrong dimensions, alpha,
metadata, or a screenshot whose recorded artifact identity differs from the sealed release receipt.
