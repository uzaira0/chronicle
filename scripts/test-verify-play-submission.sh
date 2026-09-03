#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "$ROOT_DIR/app/build/play-submission-test.XXXXXX")"
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
  mkdir -p "$destination/store/play" "$destination/app/src/play/assets"
  cp "$ROOT_DIR/store/play/"*.md "$destination/store/play/"
  cp "$ROOT_DIR/store/play/privacy.properties" "$destination/store/play/"
  cp "$ROOT_DIR/app/src/play/assets/approved-module-registry.json" \
    "$destination/app/src/play/assets/"
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
  python3 - "$fixture" <<'PY'
from pathlib import Path
import hashlib
import sys

root = Path(sys.argv[1])
properties_path = root / "store/play/privacy.properties"
paths = {
    "approved_module_registry_sha256": "app/src/play/assets/approved-module-registry.json",
    "listing_sha256": "store/play/listing.md",
    "data_safety_sha256": "store/play/data-safety.md",
    "health_apps_declaration_sha256": "store/play/health-apps-declaration.md",
    "accessibility_declaration_sha256": "store/play/accessibility-declaration.md",
    "foreground_service_declaration_sha256": "store/play/foreground-service-declaration.md",
    "reviewer_instructions_sha256": "store/play/reviewer-instructions.md",
    "play_console_inventory_sha256": "store/play/play-console-inventory-2026-08-26.md",
}
lines = properties_path.read_text(encoding="utf-8").splitlines()
for key, relative in paths.items():
    digest = hashlib.sha256((root / relative).read_bytes()).hexdigest()
    matches = [index for index, line in enumerate(lines) if line.startswith(f"{key}=")]
    if len(matches) != 1:
        raise SystemExit(f"expected exactly one property {key}")
    lines[matches[0]] = f"{key}={digest}"
properties_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
PY
}

python3 "$ROOT_DIR/scripts/verify-play-submission.py" --root "$ROOT_DIR" >/dev/null
python3 "$ROOT_DIR/scripts/verify-play-submission.py" --root "$ROOT_DIR" --sealed >/dev/null

pending_fixture="$TEST_ROOT/pending"
copy_fixture "$pending_fixture"
set_property "$pending_fixture/store/play/privacy.properties" submission_status pending_owner_approval
expect_failure \
  'sealed release requires submission_status=approved' \
  python3 "$ROOT_DIR/scripts/verify-play-submission.py" --root "$pending_fixture" --sealed

approved_fixture="$TEST_ROOT/approved"
copy_fixture "$approved_fixture"
python3 - "$approved_fixture" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
data_safety = root / "store/play/data-safety.md"
data_safety.write_text(
    data_safety.read_text(encoding="utf-8").replace(
        "This is the proposed Play Console declaration",
        "This is the approved Play Console declaration",
        1,
    ),
    encoding="utf-8",
)
PY

for status_key in \
  submission_status \
  play_account_status \
  package_ownership_status \
  signing_lineage_status \
  version_status \
  store_category_status \
  target_ages_status \
  data_safety_status \
  legal_copy_status \
  retention_wording_status \
  research_app_classification_status \
  support_identity_status; do
  set_property "$approved_fixture/store/play/privacy.properties" "$status_key" approved
done
set_property "$approved_fixture/store/play/privacy.properties" approved_by synthetic-release-owner
set_property "$approved_fixture/store/play/privacy.properties" approved_at_utc 2026-08-22T12:00:00Z
set_property "$approved_fixture/store/play/privacy.properties" release_candidate_id chronicle-play-v54-rc1
set_property "$approved_fixture/store/play/privacy.properties" maximum_uploaded_version_code 53
set_property "$approved_fixture/store/play/privacy.properties" upload_certificate_sha256 \
  AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
set_property "$approved_fixture/store/play/privacy.properties" play_app_signing_certificate_sha256 \
  BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB
set_property "$approved_fixture/store/play/privacy.properties" target_ages 18_and_over
set_property "$approved_fixture/store/play/privacy.properties" data_sharing_answer shared
refresh_source_hashes "$approved_fixture"
python3 "$ROOT_DIR/scripts/verify-play-submission.py" \
  --root "$approved_fixture" --sealed >/dev/null

printf '\nchanged after owner approval\n' >>"$approved_fixture/store/play/listing.md"
expect_failure \
  'listing_sha256 does not match store/play/listing.md' \
  python3 "$ROOT_DIR/scripts/verify-play-submission.py" --root "$approved_fixture" --sealed

inventory_fixture="$TEST_ROOT/inventory"
copy_fixture "$inventory_fixture"
printf '\nchanged after inventory capture\n' \
  >>"$inventory_fixture/store/play/play-console-inventory-2026-08-26.md"
expect_failure \
  'play_console_inventory_sha256 does not match store/play/play-console-inventory-2026-08-26.md' \
  python3 "$ROOT_DIR/scripts/verify-play-submission.py" --root "$inventory_fixture"

semantic_fixture="$TEST_ROOT/semantic"
copy_fixture "$semantic_fixture"
python3 - "$semantic_fixture/store/play/listing.md" <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
source = path.read_text(encoding="utf-8")
updated, count = re.subn(
    r"They are\s+uploaded after connectivity recovers and deleted from the device after acknowledgment or after\s+30 days\.",
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

printf 'Play submission verifier behavior tests passed.\n'
