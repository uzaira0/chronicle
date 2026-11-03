#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-$ROOT_DIR/app/build}/play-submission-test.XXXXXX")"
trap 'rm -rf -- "$TEST_ROOT"' EXIT HUP INT TERM

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

expect_failure() {
  local expected="$1"
  shift
  local output status=0
  output="$("$@" 2>&1)" || status=$?
  [[ "$status" -ne 0 ]] || fail "command unexpectedly succeeded: $*"
  grep -Fq "$expected" <<<"$output" || {
    printf '%s\n' "$output" >&2
    fail "failure did not contain: $expected"
  }
}

copy_fixture() {
  local destination="$1"
  mkdir -p "$destination/store/play" "$destination/app/src/open/assets"
  cp "$ROOT_DIR/store/play/"*.md "$destination/store/play/"
  cp "$ROOT_DIR/store/play/privacy.properties" "$destination/store/play/"
  cp "$ROOT_DIR/app/src/open/assets/approved-module-registry.json" \
    "$destination/app/src/open/assets/"
  write_console_fixture "$destination"
}

write_console_fixture() {
  python3 - "$1" "$ROOT_DIR/scripts/verify-play-submission.py" <<'PY'
import csv
from pathlib import Path
import runpy
import sys

root = Path(sys.argv[1])
owner = runpy.run_path(sys.argv[2])
declared = owner["read_properties"](root / "store/play/privacy.properties")["deletion_request_supported"]
with (root / "store/play/data-safety-console.csv").open("w", newline="", encoding="utf-8") as output:
    writer = csv.writer(output)
    writer.writerow([owner["DATA_SAFETY_QUESTION_COLUMN"], owner["DATA_SAFETY_RESPONSE_COLUMN"]])
    writer.writerow([owner["DATA_SAFETY_DELETION_QUESTION"], declared.upper()])
PY
}

set_property() {
  local properties="$1" key="$2" value="$3"
  python3 - "$properties" "$key" "$value" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
key = sys.argv[2]
value = sys.argv[3]
lines = path.read_text(encoding="utf-8").splitlines()
replacement = f"{key}={value}"
matches = [index for index, line in enumerate(lines) if line.startswith(f"{key}=")]
if len(matches) != 1:
    raise SystemExit(f"expected exactly one property {key}")
lines[matches[0]] = replacement
path.write_text("\n".join(lines) + "\n", encoding="utf-8")
PY
}

refresh_source_hashes() {
  local fixture="$1"
  python3 - "$fixture" "$ROOT_DIR/scripts/verify-play-submission.py" <<'PY'
from pathlib import Path
import runpy
import sys

root = Path(sys.argv[1])
properties_path = root / "store/play/privacy.properties"
verifier = runpy.run_path(sys.argv[2])
properties = verifier["read_properties"](properties_path)
relative, _ = verifier["registry_contract"](properties)
paths = {**verifier["SOURCE_HASHES"], "approved_module_registry_sha256": relative}
lines = properties_path.read_text(encoding="utf-8").splitlines()
for key, relative in paths.items():
    digest = verifier["sha256"](root / relative)
    matches = [index for index, line in enumerate(lines) if line.startswith(f"{key}=")]
    if len(matches) != 1:
        raise SystemExit(f"expected exactly one property {key}")
    lines[matches[0]] = f"{key}={digest}"
properties_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
PY
}

python3 "$ROOT_DIR/scripts/verify-play-submission.py" --root "$ROOT_DIR" >/dev/null

approved_fixture="$TEST_ROOT/approved"
copy_fixture "$approved_fixture"
set_property "$approved_fixture/store/play/privacy.properties" play_variant openRelease
refresh_source_hashes "$approved_fixture"
python3 "$ROOT_DIR/scripts/verify-play-submission.py" --root "$approved_fixture" >/dev/null

legacy_variant="$TEST_ROOT/legacy-variant"
cp -R "$approved_fixture" "$legacy_variant"
set_property "$legacy_variant/store/play/privacy.properties" play_variant playRelease
expect_failure \
  'play_variant must be openRelease' \
  python3 "$ROOT_DIR/scripts/verify-play-submission.py" --root "$legacy_variant"

distribution_fixture="$TEST_ROOT/distribution"
copy_fixture "$distribution_fixture"
set_property "$distribution_fixture/store/play/privacy.properties" play_variant openRelease
python3 - "$distribution_fixture/app/src/open/assets/approved-module-registry.json" <<'PY'
from pathlib import Path
import json
import sys

path = Path(sys.argv[1])
registry = json.loads(path.read_text(encoding="utf-8"))
registry["distribution"] = "PLAY"
path.write_text(json.dumps(registry), encoding="utf-8")
PY
refresh_source_hashes "$distribution_fixture"
expect_failure \
  'approved-module registry distribution must be OPEN for openRelease' \
  python3 "$ROOT_DIR/scripts/verify-play-submission.py" --root "$distribution_fixture"

printf '\nchanged after owner approval\n' >>"$approved_fixture/store/play/listing.md"
expect_failure \
  'listing_sha256 does not match store/play/listing.md' \
  python3 "$ROOT_DIR/scripts/verify-play-submission.py" --root "$approved_fixture"

deletion_fixture="$TEST_ROOT/deletion"
copy_fixture "$deletion_fixture"
set_property "$deletion_fixture/store/play/privacy.properties" play_variant openRelease
set_property "$deletion_fixture/store/play/privacy.properties" deletion_request_supported false
expect_failure \
  'Play Console deletion answer differs from the shipped UI declaration' \
  python3 "$ROOT_DIR/scripts/verify-play-submission.py" --root "$deletion_fixture"

inventory_fixture="$TEST_ROOT/inventory"
copy_fixture "$inventory_fixture"
printf '\nchanged after inventory capture\n' \
  >>"$inventory_fixture/store/play/play-console-inventory-2026-08-26.md"
expect_failure \
  'play_console_inventory_sha256 does not match store/play/play-console-inventory-2026-08-26.md' \
  python3 "$ROOT_DIR/scripts/verify-play-submission.py" --root "$inventory_fixture"

semantic_fixture="$TEST_ROOT/semantic"
copy_fixture "$semantic_fixture"
python3 - "$semantic_fixture/store/play/listing.md" "$ROOT_DIR/scripts/verify-play-submission.py" <<'PY'
from pathlib import Path
import re
import runpy
import sys

path = Path(sys.argv[1])
source = path.read_text(encoding="utf-8")
disclosure = runpy.run_path(sys.argv[2])["DEFERRED_UPLOAD_DISCLOSURE"]
updated, count = re.subn(
    r"\s+".join(re.escape(word) for word in disclosure.split()),
    "They remain only on the device.",
    source,
    count=1,
)
if count != 1:
    raise SystemExit("could not mutate deferred upload diagnostics copy")
path.write_text(updated, encoding="utf-8")
PY
refresh_source_hashes "$semantic_fixture"
expect_failure \
  'listing does not disclose deferred upload-diagnostic delivery and retention' \
  python3 "$ROOT_DIR/scripts/verify-play-submission.py" --root "$semantic_fixture"

module_fixture="$TEST_ROOT/module"
copy_fixture "$module_fixture"
set_property "$module_fixture/store/play/privacy.properties" approved_module_ids \
  usage_events,in_app_activity_class,device_lifecycle,user_identification,upload_telemetry,battery_telemetry,connectivity_state,app_network_usage,device_settings,audio_content
expect_failure \
  'approved module list drifted' \
  python3 "$ROOT_DIR/scripts/verify-play-submission.py" --root "$module_fixture"

console_fixture="$TEST_ROOT/console"
copy_fixture "$console_fixture"
rm "$console_fixture/store/play/data-safety-console.csv"
set_property "$console_fixture/store/play/privacy.properties" data_safety_status pending_owner_approval
python3 "$ROOT_DIR/scripts/verify-play-submission.py" --root "$console_fixture" >/dev/null
set_property "$console_fixture/store/play/privacy.properties" data_safety_status approved
expect_failure \
  'data_safety_status=approved requires store/play/data-safety-console.csv' \
  python3 "$ROOT_DIR/scripts/verify-play-submission.py" --root "$console_fixture"

status_fixture="$TEST_ROOT/status"
copy_fixture "$status_fixture"
set_property "$status_fixture/store/play/privacy.properties" submission_status yes
expect_failure \
  'submission_status must be approved or pending_owner_approval' \
  python3 "$ROOT_DIR/scripts/verify-play-submission.py" --root "$status_fixture"

review_fixture="$TEST_ROOT/review"
copy_fixture "$review_fixture"
set_property "$review_fixture/store/play/privacy.properties" release_stage play_review
expect_failure \
  'Play review requires reviewer_environment_status=approved' \
  python3 "$ROOT_DIR/scripts/verify-play-submission.py" --root "$review_fixture"

printf 'Play submission verifier behavior tests passed.\n'
