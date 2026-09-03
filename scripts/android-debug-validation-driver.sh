#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_PACKAGE="com.bcm.chronicle.debug"
RECEIVER_CLASS="com.openlattice.chronicle.debug.DebugSyncConfigReceiver"

serial=""
package_name="$DEFAULT_PACKAGE"
output_dir="$ROOT_DIR/app/build/debug-validation-evidence/$(date +%Y%m%d-%H%M%S)"
expected_apk="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
server_id=""
trigger_validation_work=0
skip_pull_apk=0
skip_expected_apk_check=0

usage() {
  cat <<'EOF'
Usage: scripts/android-debug-validation-driver.sh [options]

Options:
  --serial <adb-serial>          Device serial. If omitted, exactly one attached device is required.
  --package <application-id>     Installed debug package. Default: com.bcm.chronicle.debug
  --output-dir <dir>             Evidence output directory. Default: app/build/debug-validation-evidence/<timestamp>
  --expected-apk <apk>           Canonical APK expected to match the installed base.apk.
                                 Default: app/build/outputs/apk/debug/app-debug.apk
  --server-id <id>               Exercise pause/resume for this local upload_servers row id.
  --trigger-validation-work      Enqueue app sync plus expansion collection/upload against the active destination.
  --skip-pull-apk                Skip installed base.apk pull and 16 KB verifier.
  --skip-expected-apk-check      Pull and verify installed base.apk but do not require hash match to --expected-apk.
  -h, --help                     Show this help.

Default behavior is state-only: verify installed APK 16 KB compatibility, dump redacted
local state, and assert exactly one enabled study destination. By default the pulled base.apk must
match the canonical debug APK hash, which catches stale installs from non-canonical
checkouts. Passing --server-id toggles only local destination enabled state. Passing
--trigger-validation-work intentionally runs upload/sync work against the one active destination.
EOF
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --serial)
      serial="${2:?missing serial}"
      shift 2
      ;;
    --package)
      package_name="${2:?missing package}"
      shift 2
      ;;
    --output-dir)
      output_dir="${2:?missing output dir}"
      shift 2
      ;;
    --expected-apk)
      expected_apk="${2:?missing expected APK}"
      shift 2
      ;;
    --server-id)
      server_id="${2:?missing server id}"
      shift 2
      ;;
    --trigger-validation-work)
      trigger_validation_work=1
      shift
      ;;
    --skip-pull-apk)
      skip_pull_apk=1
      shift
      ;;
    --skip-expected-apk-check)
      skip_expected_apk_check=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb not found on PATH." >&2
  exit 2
fi

if [[ -x "${REPO_PREFLIGHT:-$HOME/bin/repo-canonical-preflight}" ]]; then
  if ! "${REPO_PREFLIGHT:-$HOME/bin/repo-canonical-preflight}" --explain >/dev/null; then
    "${REPO_PREFLIGHT:-$HOME/bin/repo-canonical-preflight}" --explain >&2 || true
    echo "ERROR: refusing validation from a non-canonical checkout." >&2
    echo "Run Chronicle Android validation from the canonical checkout." >&2
    exit 2
  fi
fi

if [[ -z "$serial" ]]; then
  mapfile -t devices < <(adb devices | awk 'NR > 1 && $2 == "device" {print $1}')
  if [[ "${#devices[@]}" -ne 1 ]]; then
    echo "ERROR: expected exactly one attached device, found ${#devices[@]}. Pass --serial." >&2
    adb devices -l >&2
    exit 2
  fi
  serial="${devices[0]}"
fi

mkdir -p "$output_dir"
receiver_component="$package_name/$RECEIVER_CLASS"

run_adb() {
  adb -s "$serial" "$@"
}

run_broadcast() {
  local label="$1"
  local action="$2"
  shift 2
  local log_file="$output_dir/${label}.txt"
  echo "== $label =="
  run_adb shell am broadcast -n "$receiver_component" -a "$action" "$@" | tee "$log_file"
}

require_broadcast_ok() {
  local label="$1"
  if ! rg -q 'result=-1' "$output_dir/${label}.txt"; then
    echo "ERROR: broadcast $label did not report result=-1. See $output_dir/${label}.txt" >&2
    exit 1
  fi
}

echo "serial=$serial" | tee "$output_dir/context.txt"
echo "package=$package_name" | tee -a "$output_dir/context.txt"
echo "receiver=$receiver_component" | tee -a "$output_dir/context.txt"
echo "canonical_root=$ROOT_DIR" | tee -a "$output_dir/context.txt"
echo "device_page_size=$(run_adb shell getconf PAGE_SIZE 2>/dev/null | tr -d '\r')" | tee -a "$output_dir/context.txt"
if [[ "$skip_expected_apk_check" -eq 0 ]]; then
  echo "expected_apk=$expected_apk" | tee -a "$output_dir/context.txt"
fi

run_adb shell pm path "$package_name" | tee "$output_dir/pm-path.txt"

if [[ "$skip_pull_apk" -eq 0 ]]; then
  base_apk="$(awk -F: '/base.apk/ {print $2; exit}' "$output_dir/pm-path.txt")"
  if [[ -z "${base_apk:-}" ]]; then
    echo "ERROR: could not find base.apk for $package_name." >&2
    exit 1
  fi
  installed_apk="$output_dir/installed-base.apk"
  run_adb pull "$base_apk" "$installed_apk" | tee "$output_dir/pull-installed-apk.txt"
  shasum -a 256 "$installed_apk" | tee "$output_dir/installed-base.sha256"
  if [[ "$skip_expected_apk_check" -eq 0 ]]; then
    if [[ ! -f "$expected_apk" ]]; then
      echo "ERROR: expected APK not found: $expected_apk" >&2
      echo "Pass --expected-apk or --skip-expected-apk-check." >&2
      exit 1
    fi
    shasum -a 256 "$expected_apk" | tee "$output_dir/expected-apk.sha256"
    installed_sha="$(awk '{print $1}' "$output_dir/installed-base.sha256")"
    expected_sha="$(awk '{print $1}' "$output_dir/expected-apk.sha256")"
    if [[ "$installed_sha" != "$expected_sha" ]]; then
      echo "ERROR: installed base.apk does not match expected canonical APK." >&2
      echo "installed_sha=$installed_sha" >&2
      echo "expected_sha=$expected_sha" >&2
      echo "Install the canonical APK from $expected_apk before validation." >&2
      exit 1
    fi
  fi
  "$ROOT_DIR/scripts/verify-android-16kb-native-libs.sh" "$installed_apk" | tee "$output_dir/verify-installed-16kb.txt"
fi

run_broadcast "dump-before" "com.openlattice.chronicle.debug.DUMP_LOCAL_STATE"
require_broadcast_ok "dump-before"

run_broadcast "assert-single-before" \
  "com.openlattice.chronicle.debug.ASSERT_SINGLE_DESTINATION" \
  --ei expected_enabled_servers 1
require_broadcast_ok "assert-single-before"

if [[ -n "$server_id" ]]; then
  run_broadcast "disable-server" \
    "com.openlattice.chronicle.debug.SET_SERVER_ENABLED" \
    --el server_id "$server_id" \
    --ez enabled false
  require_broadcast_ok "disable-server"

  run_broadcast "assert-paused" \
    "com.openlattice.chronicle.debug.ASSERT_SINGLE_DESTINATION" \
    --ei expected_enabled_servers 0
  require_broadcast_ok "assert-paused"
fi

if [[ "$trigger_validation_work" -eq 1 ]]; then
  run_broadcast "trigger-validation-work" \
    "com.openlattice.chronicle.debug.TRIGGER_VALIDATION_WORK"
  require_broadcast_ok "trigger-validation-work"
fi

if [[ -n "$server_id" ]]; then
  run_broadcast "enable-server" \
    "com.openlattice.chronicle.debug.SET_SERVER_ENABLED" \
    --el server_id "$server_id" \
    --ez enabled true
  require_broadcast_ok "enable-server"

  run_broadcast "assert-single-after-resume" \
    "com.openlattice.chronicle.debug.ASSERT_SINGLE_DESTINATION" \
    --ei expected_enabled_servers 1
  require_broadcast_ok "assert-single-after-resume"
fi

run_broadcast "dump-after" "com.openlattice.chronicle.debug.DUMP_LOCAL_STATE"
require_broadcast_ok "dump-after"

echo "Android debug validation driver completed. Evidence: $output_dir"
