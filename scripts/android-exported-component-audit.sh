#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="${1:-$ROOT_DIR/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml}"

if [[ ! -f "$MANIFEST" ]]; then
  echo "ERROR: merged manifest not found: $MANIFEST" >&2
  echo "Run: ./gradlew :app:processReleaseManifest --no-daemon --console=plain" >&2
  exit 2
fi

python3 - "$MANIFEST" <<'PY'
import sys
import xml.etree.ElementTree as ET

manifest = sys.argv[1]
android = "{http://schemas.android.com/apk/res/android}"

# name -> (component kind, required permission or None)
allowed = {
    "com.openlattice.chronicle.MainActivity": ("activity", None),
    "com.openlattice.chronicle.Enrollment": ("activity", None),
    "com.openlattice.chronicle.HealthConnectRationaleActivity": ("activity", None),
    "com.openlattice.chronicle.ViewPermissionUsageActivity": ("activity-alias", "android.permission.START_VIEW_PERMISSION_USAGE"),
    "com.openlattice.chronicle.services.notifications.NotificationPermissionListener": ("receiver", None),
    "com.openlattice.chronicle.receivers.lifecycle.StartOnBoot": ("receiver", None),
    "com.openlattice.chronicle.receivers.lifecycle.DeviceLifecycleReceiver": ("receiver", None),
    "com.openlattice.chronicle.collection.activity.SleepActivityReceiver": ("receiver", None),
    "com.openlattice.chronicle.receivers.lifecycle.UnlockDeviceReceiver": ("receiver", None),
    "com.openlattice.chronicle.collection.interaction.InteractionCollectionService": ("service", "android.permission.BIND_ACCESSIBILITY_SERVICE"),
    "androidx.health.platform.client.impl.sdkservice.HealthDataSdkService": ("service", None),
    "androidx.work.impl.background.systemjob.SystemJobService": ("service", "android.permission.BIND_JOB_SERVICE"),
    "androidx.work.impl.diagnostics.DiagnosticsReceiver": ("receiver", "android.permission.DUMP"),
    "androidx.profileinstaller.ProfileInstallReceiver": ("receiver", "android.permission.DUMP"),
}

root = ET.parse(manifest).getroot()
actual = {}
for elem in root.iter():
    kind = elem.tag.split("}")[-1]
    if kind not in {"activity", "activity-alias", "receiver", "service", "provider"}:
        continue
    if elem.get(android + "exported") != "true":
        continue
    name = elem.get(android + "name")
    actual[name] = (kind, elem.get(android + "permission"))

failures = []
for name, (kind, permission) in sorted(actual.items()):
    print(f"{kind}\t{name}\tpermission={permission or ''}")
    expected = allowed.get(name)
    if expected is None:
        failures.append(f"unexpected exported component: {kind} {name} permission={permission or ''}")
        continue
    if expected[0] != kind:
        failures.append(f"{name}: kind {kind!r}, expected {expected[0]!r}")
    if expected[1] != permission:
        failures.append(f"{name}: permission {permission!r}, expected {expected[1]!r}")

missing = sorted(set(allowed) - set(actual))
for name in missing:
    failures.append(f"expected exported component missing: {name}")

debug_exported = [name for name in actual if ".debug." in name.lower() or name.lower().endswith("debugsyncconfigreceiver")]
for name in debug_exported:
    failures.append(f"debug component exported in release manifest: {name}")

if failures:
    print("\nFAIL:")
    for failure in failures:
        print(f"- {failure}")
    sys.exit(1)

print("\nExported component audit passed.")
PY
