# Google Play listing

App name (30 characters maximum):

Chronicle Research

Short description (80 characters maximum):

Secure data collection for approved research studies.

Full description:

Chronicle is a participant app for approved research studies. A device has one active study at a
time. Before collection starts, Chronicle shows the study and its HTTPS server, explains every
requested data type, records the participant's choices, and collects nothing until consent is
given. A study turns on only the data types its protocol needs; anything it does not request is
never collected. Android permissions and special access are requested separately, after a
disclosure, and can be revoked in Settings.

WHAT A STUDY CAN COLLECT (only when requested and accepted)

- App usage: app name, open and close times, foreground duration, screen and startup events,
  notification seen and interruption events (which app and when, never notification content), and
  optionally the in-app screen name. Per-app bytes sent and received over Wi-Fi or cellular.
- Motion and device sensors (accelerometer, gyroscope, magnetometer, gravity, rotation, light,
  proximity, significant motion, tilt, orientation) at the study's sampling rate up to 200 Hz, in a foreground
  service with an ongoing notification.
- Device telemetry: device brand, model and Android version at enrollment, which sensors the
  device has, reboots, battery, connectivity type, settings such as dark mode, font size, Do
  Not Disturb, volume, storage, and whether location services are on (not a location).
- Unlock identification: after unlock, whether the user is the participant or someone else.
- Study reminders that open the study's questionnaire page.

Records are encrypted on the device and uploaded only to the enrolled study server. Upload
diagnostics record failure category, count, timing and HTTP status, never server URLs or error
text. They also report counts and occurrence times of app crashes, native crashes and app not responding events,
without stack traces. They are uploaded after connectivity recovers and kept on the device until the enrollment
is erased or Chronicle is uninstalled.

NOT COLLECTED

Health Connect or other health records, sleep or physical-activity recognition, step counts, media
titles, audio status, interaction events, Notification Activity module records, app contents, typed
text, messages, notification content, screenshots, websites or searches,
location, contacts, call or SMS logs, photos, video, microphone audio, advertising identifiers or
financial information. No advertising, analytics or tracking SDK. Chronicle does not sell data or
use it for advertising. Each study's consent names its institution and research team and explains
retention. Publishing Chronicle does not give the publisher access to any study's records.

To withdraw, open Settings > Study server details > Withdraw from study. Withdrawing stops collection at
once and sends a request to the enrolled study server. Data already collected is kept or erased by the
study team, according to this study's withdrawal policy. Keep Chronicle installed until the request
is acknowledged. Uninstalling alone removes on-device data but does not request server deletion.
Contact the research team named in the study consent for questions.

Chronicle is not a medical device and does not diagnose, treat, cure, or prevent any medical
condition. Do not use Chronicle for medical decisions. Consult a qualified healthcare professional
for medical advice.

Privacy and compliance: https://www.bcm.edu/privacy
Support: uzairalam998@gmail.com
