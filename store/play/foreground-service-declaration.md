# Foreground service declarations

The Play artifact has one `specialUse` service. It needs one Play Console declaration and a durable
unlisted demo-video URL. It runs only while enrolled/configured, shows an ongoing notification, and
stops when identification is disabled, access is revoked, enrollment becomes inactive, or the app
is uninstalled.

## DeviceUnlockMonitoringService

- Functionality: on an explicitly configured shared research tablet, display a prompt after unlock so
  the current device user can choose exactly one of two categories: study participant or someone
  else. It does not identify a person biometrically and does not capture screen content.
- Why immediate/continuous: the prompt must correspond to the unlock event; delayed work cannot
  reliably associate later device data with the intended user category.
- Why specialUse: shared-device attribution for a longitudinal research protocol does not match a
  standard FGS type.
- Video: enable Identify user in Settings, lock/unlock, show the prompt and ongoing notification,
  disable Identify user, and show that the service stops.

If Play rejects this `specialUse`, do not relabel it as an inaccurate service type. Redesign the
runtime around an approved API or remove that feature from the Play flavor.
