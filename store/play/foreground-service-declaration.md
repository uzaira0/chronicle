# Foreground service declarations

The open artifact Play ships has two `specialUse` services. Each needs a Play Console declaration
and a durable unlisted demo-video URL. Each runs only while enrolled and its module is active, shows
an ongoing notification, and stops when the module is turned off, access is revoked, enrollment
becomes inactive, or the app is uninstalled.

## DeviceUnlockMonitoringService

- Functionality: on an explicitly configured shared research tablet, display a prompt after unlock so
  the current device user can choose exactly one of two categories: study participant or someone
  else. It does not identify a person biometrically and does not capture screen content.
- Why immediate/continuous: the prompt must correspond to the unlock event; delayed work cannot
  reliably associate later device data with the intended user category.
- Why specialUse: shared-device attribution for a longitudinal research protocol does not match a
  standard FGS type.
- Video: turn on Identify device user in Settings (allow notifications, and keep Pop on screen on
  for Device user prompts), lock/unlock, show the prompt and ongoing notification, turn off
  Identify device user, and show that the service stops.

## HardwareSensorService

- Functionality: sample the motion and device sensors the study requests (`sensor_*` modules) at
  the study's rate and duty cycle, and store the samples for upload to the enrolled study server.
- Why immediate/continuous: research sensor streams need sampling while the app is in the
  background; Android stops sensor listeners in a backgrounded process without a foreground service.
- Why specialUse: continuous research sampling of non-body sensors does not match a standard FGS
  type. `health` was considered and not used: the service is not a fitness or health tracker for
  the user.
- Video: enroll in a study with a sensor module requested, accept it, show the ongoing sensor
  notification, then remove the sensor module (or withdraw) and show that the service stops.

If Play rejects either `specialUse`, do not relabel it as an inaccurate service type. Redesign the
runtime around an approved API or remove that feature from the Play artifact.
