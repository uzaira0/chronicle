#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_VARIANT="playRelease"
DEFAULT_PACKAGE="com.bcm.chronicle"

variant="$DEFAULT_VARIANT"
package_name="$DEFAULT_PACKAGE"
serial=""
output_dir="$ROOT_DIR/app/build/release-candidate-evidence/$(date +%Y%m%d-%H%M%S)"
skip_build=0
install_apk=0
enrollment_url_file=""

usage() {
  cat <<'EOF'
Usage: scripts/android-release-candidate-gate.sh [options]

Build and verify a signed non-debug Android beta/release APK, then optionally
install it on an attached device and prove the installed base.apk matches.

Options:
  --variant <name>              Flavored build variant. Default: playRelease.
  --package <application-id>    Package expected after install. Default: com.bcm.chronicle.
  --serial <adb-serial>         Device serial for --install. If omitted, --install requires exactly one device.
  --output-dir <dir>            Evidence output directory. Default: app/build/release-candidate-evidence/<timestamp>.
  --skip-build                  Verify an already-built APK for the selected variant.
  --install                     Install the APK, pull installed base.apk, compare hashes, and verify installed 16 KB.
  --enrollment-url-file <path>  After --install, read a release-safe enrollment deep link from this
                                caller-owned mode-0600 file and dispatch it over adb stdin. The URL
                                is never accepted on the command line or retained as evidence.
  -h, --help                    Show this help.

Required local inputs:
  Copy app/signing.properties.example to app/signing.properties, replace every
  placeholder locally, and point storeFile at the intended Chronicle beta/release
  keystore. app/signing.properties must stay ignored by Git. The script refuses
  Android debug signing material before invoking Gradle.

Expected app/signing.properties shape:
  storeFile=/absolute/path/to/chronicle-release-or-beta.jks
  storePassword=<local secret>
  keyAlias=<release key alias>
  keyPassword=<local secret>

The script records hashes and verifier output but never prints signing passwords.
EOF
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --variant)
      variant="${2:?missing variant}"
      shift 2
      ;;
    --package)
      package_name="${2:?missing package}"
      shift 2
      ;;
    --serial)
      serial="${2:?missing serial}"
      shift 2
      ;;
    --output-dir)
      output_dir="${2:?missing output dir}"
      shift 2
      ;;
    --skip-build)
      skip_build=1
      shift
      ;;
    --install)
      install_apk=1
      shift
      ;;
    --enrollment-url-file)
      enrollment_url_file="${2:?missing enrollment URL file}"
      shift 2
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

variant_suffix="$(python3 - "$variant" <<'PY'
import sys
value = sys.argv[1]
if not value:
    raise SystemExit(2)
print(value[0].upper() + value[1:])
PY
)"
if [[ "$variant" =~ ^(play|amazon|research|open)(Debug|Release|Dogfood|DebugMinified|ReleaseMinified)$ ]]; then
  flavor="${BASH_REMATCH[1]}"
  build_type_pascal="${BASH_REMATCH[2]}"
else
  echo "ERROR: unsupported flavored variant: $variant" >&2
  echo "Expected play, amazon, research, or open plus Debug, Release, Dogfood, DebugMinified, or ReleaseMinified." >&2
  exit 2
fi
build_type="${build_type_pascal,}"
apk_path="$ROOT_DIR/app/build/outputs/apk/$flavor/$build_type/app-$flavor-$build_type.apk"
assemble_task=":app:assemble$variant_suffix"

if [[ -x "${REPO_PREFLIGHT:-$HOME/bin/repo-canonical-preflight}" ]]; then
  if ! "${REPO_PREFLIGHT:-$HOME/bin/repo-canonical-preflight}" --explain >/dev/null; then
    "${REPO_PREFLIGHT:-$HOME/bin/repo-canonical-preflight}" --explain >&2 || true
    echo "ERROR: refusing release validation from a non-canonical checkout." >&2
    exit 2
  fi
fi

if [[ -n "$enrollment_url_file" && "$install_apk" -ne 1 ]]; then
  echo "ERROR: --enrollment-url-file requires --install so the exact release candidate is on device." >&2
  exit 2
fi
if [[ ! "$package_name" =~ ^[A-Za-z][A-Za-z0-9_.]*$ ]]; then
  echo "ERROR: invalid Android package name: $package_name" >&2
  exit 2
fi

mkdir -p "$output_dir"
context_file="$output_dir/context.txt"
{
  echo "canonical_root=$ROOT_DIR"
  echo "variant=$variant"
  echo "assemble_task=$assemble_task"
  echo "apk_path=$apk_path"
  echo "package=$package_name"
} | tee "$context_file"

signing_file="$ROOT_DIR/app/signing.properties"
if [[ ! -f "$signing_file" ]]; then
  {
    echo "ERROR: missing app/signing.properties."
    echo "Copy app/signing.properties.example to app/signing.properties and replace placeholders locally."
  } | tee "$output_dir/signing-audit.txt" >&2
  exit 1
fi

signing_audit="$output_dir/signing-audit.txt"
set +e
python3 - "$signing_file" "$ROOT_DIR" >"$signing_audit" 2>"$output_dir/signing-audit-error.txt" <<'PY'
from pathlib import Path
import sys

signing_file = Path(sys.argv[1])
root = Path(sys.argv[2])
props = {}
for raw in signing_file.read_text().splitlines():
    line = raw.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    key, value = line.split("=", 1)
    props[key.strip()] = value.strip()

required = ["storeFile", "storePassword", "keyAlias", "keyPassword"]
missing = [key for key in required if not props.get(key)]
store = props.get("storeFile", "")
store_path = Path(store)
if not store_path.is_absolute():
    store_path = root / "app" / store
store_norm = str(store_path).replace("\\", "/").lower()
alias = props.get("keyAlias", "").strip().lower()
debug_signing = (
    alias == "androiddebugkey"
    or store_norm == "debug.keystore"
    or store_norm.endswith("/debug.keystore")
    or props.get("storePassword") == "android"
    or props.get("keyPassword") == "android"
)

print("signing_properties=present")
print(f"required_keys_present={'no' if missing else 'yes'}")
if missing:
    print("missing_keys=" + ",".join(missing))
print(f"store_file_exists={'yes' if store_path.is_file() else 'no'}")
print(f"store_file_class={'debug' if store_norm.endswith('/debug.keystore') or store_norm == 'debug.keystore' else 'non_debug_or_unknown'}")
print(f"key_alias_class={'debug' if alias == 'androiddebugkey' else 'non_debug_or_unknown'}")
print(f"debug_signing_material={'yes' if debug_signing else 'no'}")

if missing:
    raise SystemExit("ERROR: app/signing.properties is missing required keys.")
if not store_path.is_file():
    raise SystemExit("ERROR: configured signing storeFile does not exist.")
if debug_signing:
    raise SystemExit("ERROR: refusing Android debug signing material for a release candidate.")
PY
signing_audit_rc=$?
set -e
cat "$signing_audit"
if [[ "$signing_audit_rc" -ne 0 ]]; then
  cat "$output_dir/signing-audit-error.txt" >&2
  exit "$signing_audit_rc"
fi

build_started_epoch="$(date +%s)"
if [[ "$skip_build" -eq 0 ]]; then
  if [[ -n "${JAVA_HOME:-}" ]]; then
    echo "JAVA_HOME=$JAVA_HOME" >>"$context_file"
  fi
  echo "== Gradle $assemble_task =="
  (cd "$ROOT_DIR" && ./gradlew "$assemble_task" --no-daemon --console=plain) 2>&1 | tee "$output_dir/assemble-$variant.txt"
else
  echo "skip_build=true" >>"$context_file"
fi

if [[ ! -f "$apk_path" ]]; then
  echo "ERROR: APK not found after build: $apk_path" >&2
  exit 1
fi

if [[ "$skip_build" -eq 0 ]]; then
  if stat -f '%m' "$apk_path" >/dev/null 2>&1; then
    apk_mtime="$(stat -f '%m' "$apk_path")"
  else
    apk_mtime="$(stat -c '%Y' "$apk_path")"
  fi
  if (( apk_mtime < build_started_epoch )); then
    echo "ERROR: APK mtime predates this build; refusing stale release artifact." >&2
    exit 1
  fi
fi

shasum -a 256 "$apk_path" | tee "$output_dir/apk.sha256"
"$ROOT_DIR/scripts/verify-android-16kb-native-libs.sh" --release-abis-only "$apk_path" | tee "$output_dir/verify-apk-16kb.txt"

find_apksigner() {
  if command -v apksigner >/dev/null 2>&1; then
    command -v apksigner
    return
  fi
  local sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  find "$sdk_root/build-tools" -type f -name apksigner 2>/dev/null | sort -V | tail -n 1
}

apksigner_bin="$(find_apksigner)"
if [[ -z "${apksigner_bin:-}" || ! -x "$apksigner_bin" ]]; then
  echo "ERROR: apksigner not found. Install Android SDK build-tools or set ANDROID_HOME." >&2
  exit 2
fi
"$apksigner_bin" verify --verbose --print-certs "$apk_path" | tee "$output_dir/apksigner-verify.txt"
if rg -qi 'android debug|androiddebugkey|debug\.keystore' "$output_dir/apksigner-verify.txt"; then
  echo "ERROR: apksigner output looks like Android debug signing material." >&2
  exit 1
fi

if [[ "$install_apk" -eq 1 ]]; then
  if ! command -v adb >/dev/null 2>&1; then
    echo "ERROR: adb not found on PATH." >&2
    exit 2
  fi
  if [[ -z "$serial" ]]; then
    mapfile -t devices < <(adb devices | awk 'NR > 1 && $2 == "device" {print $1}')
    if [[ "${#devices[@]}" -ne 1 ]]; then
      echo "ERROR: expected exactly one attached device for --install, found ${#devices[@]}. Pass --serial." >&2
      adb devices -l >&2
      exit 2
    fi
    serial="${devices[0]}"
  fi
  echo "serial=$serial" | tee -a "$context_file"
  adb -s "$serial" install -r "$apk_path" | tee "$output_dir/adb-install.txt"
  adb -s "$serial" shell pm path "$package_name" | tee "$output_dir/pm-path.txt"
  base_apk="$(awk -F: '/base.apk/ {print $2; exit}' "$output_dir/pm-path.txt")"
  if [[ -z "${base_apk:-}" ]]; then
    echo "ERROR: could not find installed base.apk for $package_name." >&2
    exit 1
  fi
  installed_apk="$output_dir/installed-base.apk"
  adb -s "$serial" pull "$base_apk" "$installed_apk" | tee "$output_dir/pull-installed-apk.txt"
  shasum -a 256 "$installed_apk" | tee "$output_dir/installed-base.sha256"
  expected_sha="$(awk '{print $1}' "$output_dir/apk.sha256")"
  installed_sha="$(awk '{print $1}' "$output_dir/installed-base.sha256")"
  if [[ "$expected_sha" != "$installed_sha" ]]; then
    echo "ERROR: installed base.apk does not match release candidate APK." >&2
    echo "expected_sha=$expected_sha" >&2
    echo "installed_sha=$installed_sha" >&2
    exit 1
  fi
  "$ROOT_DIR/scripts/verify-android-16kb-native-libs.sh" --release-abis-only "$installed_apk" | tee "$output_dir/verify-installed-16kb.txt"

  if [[ -n "$enrollment_url_file" ]]; then
    if [[ ! -f "$enrollment_url_file" || -L "$enrollment_url_file" || ! -O "$enrollment_url_file" ]]; then
      echo "ERROR: --enrollment-url-file must be a regular, non-symlink file owned by the current user." >&2
      exit 2
    fi
    if file_mode="$(stat -f '%Lp' "$enrollment_url_file" 2>/dev/null)"; then
      :
    else
      file_mode="$(stat -c '%a' "$enrollment_url_file")"
    fi
    if [[ "$file_mode" != "600" ]]; then
      echo "ERROR: --enrollment-url-file must have mode 0600; found $file_mode." >&2
      exit 2
    fi
    enrollment_url=""
    IFS= read -r enrollment_url < "$enrollment_url_file" || true
    # macOS still ships Bash 3.2, whose regex engine rejects large interval
    # quantifiers. Match the URL-safe alphabet first, then
    # enforce the capability length arithmetically so this release gate behaves
    # identically on operator Macs and CI Linux hosts.
    if [[ ! "$enrollment_url" =~ \#accessCode=([A-Za-z0-9_-]+)$ ]]; then
      echo "ERROR: the enrollment URL file must contain one URL ending in a 32-256 character URL-safe #accessCode fragment." >&2
      exit 2
    fi
    enrollment_code="${BASH_REMATCH[1]}"
    enrollment_code_length="${#enrollment_code}"
    if (( enrollment_code_length < 32 || enrollment_code_length > 256 )); then
      echo "ERROR: the enrollment URL file must contain one URL ending in a 32-256 character URL-safe #accessCode fragment." >&2
      exit 2
    fi
    unset enrollment_code_length
    redact_enrollment_code() {
      local line
      while IFS= read -r line; do
        printf '%s\n' "${line//"$enrollment_code"/[REDACTED]}"
      done
    }
    echo "enrollment_invitation_supplied=true" >>"$context_file"
    echo "enrollment_url_retained=false" >>"$context_file"
    echo "enrollment_credential_retained=false" >>"$context_file"
    if ! printf '%s\n' "$enrollment_url" | adb -s "$serial" shell \
      "IFS= read -r invitation; exec am start -a android.intent.action.VIEW -d \"\$invitation\" \"$package_name\"" \
      >/dev/null 2>&1; then
      echo "ERROR: enrollment invitation could not be dispatched to $package_name." >&2
      exit 1
    fi
    unset enrollment_url
    echo "enrollment_intent_dispatched=true" >>"$context_file"
    sleep 3
    adb -s "$serial" shell dumpsys window | rg 'mCurrentFocus|mFocusedApp' \
      | redact_enrollment_code | tee "$output_dir/window-focus-after-enrollment-url.txt" || true
    if rg -aFq -- "$enrollment_code" "$output_dir"; then
      echo "ERROR: enrollment credential appeared in retained release evidence." >&2
      exit 1
    fi
    if ! rg -q 'com\.openlattice\.chronicle/\.Enrollment|com\.openlattice\.chronicle\.Enrollment|Enrollment' \
      "$output_dir/window-focus-after-enrollment-url.txt"; then
      echo "ERROR: enrollment deep link did not visibly resolve to the Enrollment flow." >&2
      exit 1
    fi
  fi
fi

echo "Android release candidate gate completed. Evidence: $output_dir"
