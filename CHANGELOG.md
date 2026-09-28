# Changelog

## [Unreleased]

### Fixed

- Overview reports successful usage, sensor, and battery uploads consistently, and the release-candidate enrollment gate works on supported operator Macs. [#41](https://github.com/uzaira0/chronicle/pull/41)

## Play internal builds

Full notes per release are in the monorepo `CHANGELOG.md`.

- versionCode 62, `2026.09.27-internal.open.1` (release 2026.9.27): open-source licenses screen;
  discarded sensor and usage data counted and reported; low-storage eviction of the oldest queued
  usage rows; clock-skew re-sign; 8 MiB response limit.
- versionCode 61, `2026.09.25-internal.open.1` (release 2026.9.25): first build declaring
  `SCHEDULE_EXACT_ALARM` in the open flavor (without it, Android 12+ reopened Settings on every launch);
  edge-to-edge layout on Android 15+; identify-user prompt after unlock.
- versionCode 60, `2026.09.16-internal.open.1`: restricted collectors compiled into the open
  flavor; OEM background guidance step; consent and withdrawal wording; settings If-Match.
- versionCode 59: unreleased 2026-09-09 demo build.
