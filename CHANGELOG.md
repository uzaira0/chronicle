# Changelog

## [Unreleased]

### Fixed

- Overview reports successful usage, sensor, and battery uploads consistently, and the release-candidate enrollment gate works on supported operator Macs. [#41](https://github.com/uzaira0/chronicle/pull/41)

## Play internal builds

Full notes per release are in the monorepo `CHANGELOG.md`.

- versionCode 65, `2026.09.30-internal.open.1` (release 2026.9.30): after enrollment the app opens Data Sharing when an
  accepted module still needs Android access (Usage Access), and Overview says so; questionnaire reminders are delivered
  again, including when the alarm starts the app; no false enrollment screen after the app is killed; rotating the phone
  keeps the invitation and consent progress; lifecycle events refused for low storage are counted as lost.
- versionCode 64, `2026.09.29-internal.open.1` (release 2026.9.29): fixes from the 2026.9.28 review: persistence lock-order hangs fixed; low-storage batches retried or counted as lost, never dropped silently;
  discarded sensors erased from retry buffers and direct-boot files, including interrupted erasures; direct-boot
  files readable across release builds (R8 keep rules); app-network usage never read across enrollments.
  Codebase sweep: no callback waits on the database or persistence lock (ANR); start-up storage/crypto/WorkManager
  failures contained and retried; observations captured before a switch-off, withdrawal or enrollment change never
  stored or uploaded; switch-off clears cursors, queues, survey alarms and sealed files; build-63 checkpoints kept.
- versionCode 63, `2026.09.28-internal.open.1` (release 2026.9.28): diagnostics kept until the
  server stores them; low storage pauses collection instead of evicting usage rows; malformed rows
  quarantined one by one; research Delete Server withdraws and waits for the server before erasing.
- versionCode 62, `2026.09.27-internal.open.1` (release 2026.9.27): open-source licenses screen;
  discarded sensor and usage data counted and reported; low-storage eviction of the oldest queued
  usage rows; clock-skew re-sign; 8 MiB response limit.
- versionCode 61, `2026.09.25-internal.open.1` (release 2026.9.25): first build declaring
  `SCHEDULE_EXACT_ALARM` in the open flavor (without it, Android 12+ reopened Settings on every launch);
  edge-to-edge layout on Android 15+; identify-user prompt after unlock.
- versionCode 60, `2026.09.16-internal.open.1`: restricted collectors compiled into the open
  flavor; OEM background guidance step; consent and withdrawal wording; settings If-Match.
- versionCode 59: unreleased 2026-09-09 demo build.
