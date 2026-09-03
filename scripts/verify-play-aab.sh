#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AAB_PATH="$ROOT_DIR/app/build/outputs/bundle/playRelease/app-play-release.aab"
OUTPUT_DIR="$ROOT_DIR/app/build/play-aab-evidence/$(date +%Y%m%d-%H%M%S)"
SKIP_BUILD=0
FORBIDDEN_LITERALS_FILE=""
EXPECTED_CERT_SHA256="${PLAY_UPLOAD_CERT_SHA256:-}"
ALLOW_UNPINNED_CERT=0
EXPECTED_RC_ID="${CHRONICLE_RC_ID:-}"
ALLOW_UNSEALED_RC=0
SEALED_SUBMISSION=0
BUNDLETOOL_JAR=""
DEVICE_SERIAL=""
VERIFY_INSTALLED=0
VERIFY_PLAY_DELIVERED=0
EXPECTED_INSTALLED_CERT_SHA256=""
PRIOR_SEALED_RECEIPT=""
RELEASE_AUTHORITY_SHA="${CHRONICLE_RELEASE_AUTHORITY_SHA:-}"
ATTESTATION_REPOSITORY="uzaira0/methodic"
ATTESTATION_SIGNER_WORKFLOW="github.com/uzaira0/methodic/.github/workflows/build-android-apk.yml"
RUNTIME_EGRESS_ORIGIN=""
RUNTIME_EGRESS_SECONDS=45
MAPPING_PATH="$ROOT_DIR/app/build/outputs/mapping/playRelease/mapping.txt"
PACKAGE_NAME="com.bcm.chronicle"
EGRESS_CLEANUP_NEEDED=0
EGRESS_CLEANUP_FAILED=0
EGRESS_SERIAL=""
EGRESS_PACKAGE_UID=""
EGRESS_CHAIN_NAME=""
EGRESS_UI_DUMP_LOCAL=""
EGRESS_UI_DUMP_REMOTE=""
PLAY_SUBMISSION_SOURCES=(
  app/src/play/assets/approved-module-registry.json
  store/play/privacy.properties
  store/play/listing.md
  store/play/data-safety.md
  store/play/health-apps-declaration.md
  store/play/accessibility-declaration.md
  store/play/foreground-service-declaration.md
  store/play/reviewer-instructions.md
)

normalize_cert_sha256() {
  printf '%s' "$1" | tr -d '[:space:]:' | tr '[:lower:]' '[:upper:]'
}

read_submission_property() {
  local properties_path="$1" property_name="$2"
  sed -n "s/^${property_name}=//p" "$properties_path" | head -n 1
}

resolve_gh_attestation_command() {
  if [[ "${BASH_SOURCE[0]}" != "$0" &&
    "${CHRONICLE_PLAY_AAB_HELPERS_ONLY:-0}" == "1" &&
    -n "${CHRONICLE_TEST_GH_ATTESTATION_COMMAND:-}" ]]; then
    printf '%s\n' "$CHRONICLE_TEST_GH_ATTESTATION_COMMAND"
    return
  fi
  printf '%s\n' gh
}

snapshot_stable_input() {
  local source_path="$1" destination_path="$2" label="$3"
  local before_sha after_sha snapshot_sha
  [[ -f "$source_path" && ! -L "$source_path" ]] || {
    printf '%s must be a regular non-symlink file\n' "$label" >&2
    return 1
  }
  [[ ! -e "$destination_path" && ! -L "$destination_path" ]] || {
    printf '%s snapshot target already exists\n' "$label" >&2
    return 1
  }
  before_sha="$(shasum -a 256 "$source_path" | awk '{print $1}')" || return 1
  cp -- "$source_path" "$destination_path" || return 1
  after_sha="$(shasum -a 256 "$source_path" | awk '{print $1}')" || return 1
  snapshot_sha="$(shasum -a 256 "$destination_path" | awk '{print $1}')" || return 1
  [[ "$before_sha" == "$after_sha" && "$before_sha" == "$snapshot_sha" ]] || {
    printf '%s changed while its authenticated snapshot was created\n' "$label" >&2
    return 1
  }
  chmod 600 "$destination_path"
}

verify_sealed_source_checkout() {
  local root="$1" skip_build="$2" phase="$3" status
  case "$phase" in
    initial_seal)
      [[ "$skip_build" -eq 0 ]] || {
        printf 'initial sealed verification must build the AAB from the checked source tree\n' >&2
        return 1
      }
      ;;
    play_delivery)
      [[ "$skip_build" -eq 1 ]] || {
        printf 'Play-delivered verification must inspect the previously sealed AAB without rebuilding\n' >&2
        return 1
      }
      ;;
    *)
      printf 'unknown sealed verification phase: %s\n' "$phase" >&2
      return 1
      ;;
  esac
  if git -C "$root" symbolic-ref -q HEAD >/dev/null; then
    printf 'sealed verification requires a detached source checkout\n' >&2
    return 1
  fi
  status="$(git -C "$root" status --porcelain=v1 --untracked-files=all --ignore-submodules=none)"
  [[ -z "$status" ]] || {
    printf 'sealed verification requires no tracked, staged, or untracked source inputs\n' >&2
    return 1
  }
}

verify_release_authority_binding() {
  local android_root="$1" authority_sha="${2,,}"
  local superproject authority_head android_head android_path tree_entry
  [[ "$authority_sha" =~ ^[0-9a-f]{40}$ ]] || {
    printf 'release authority must be one full Git commit SHA\n' >&2
    return 1
  }
  superproject="$(git -C "$android_root" rev-parse --show-superproject-working-tree 2>/dev/null)"
  [[ -n "$superproject" ]] || {
    printf 'sealed Android source must be checked out as a release-authority submodule\n' >&2
    return 1
  }
  authority_head="$(git -C "$superproject" rev-parse HEAD)" || return 1
  [[ "$authority_head" == "$authority_sha" ]] || {
    printf 'release authority SHA differs from the checked-out superproject HEAD\n' >&2
    return 1
  }
  android_head="$(git -C "$android_root" rev-parse HEAD)" || return 1
  android_path="$(basename "$android_root")"
  tree_entry="$(git -C "$superproject" ls-tree "$authority_sha" -- "$android_path")" || return 1
  [[ "$tree_entry" == "160000 commit $android_head"$'\t'"$android_path" ]] || {
    printf 'release authority gitlink does not identify the checked-out Android source commit\n' >&2
    return 1
  }
}

verify_prior_sealed_receipt() {
  local receipt_path="$1" aab_path="$2" mapping_path="$3" registry_path="$4"
  local policy_path="$5" verifier_path="$6" root="$7" expected_rc="$8"
  local expected_upload_signer="$9" release_authority_sha="${10}"
  local attestation_output="${11}"
  local receipt_file gh_attestation_command
  receipt_file="$receipt_path"
  [[ -f "$receipt_file" && ! -L "$receipt_file" ]] || {
    printf 'prior sealed receipt must be a regular non-symlink file\n' >&2
    return 1
  }
  gh_attestation_command="$(resolve_gh_attestation_command)"
  "$gh_attestation_command" attestation verify "$receipt_file" \
    --repo "$ATTESTATION_REPOSITORY" \
    --signer-workflow "$ATTESTATION_SIGNER_WORKFLOW" \
    --source-digest "$release_authority_sha" \
    --deny-self-hosted-runners \
    --format json >"$attestation_output" || return 1
  python3 - "$attestation_output" <<'PY' || return 1
from pathlib import Path
import json
import sys

result = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if not isinstance(result, list) or not result:
    raise SystemExit("GitHub attestation verification returned no verified attestations")
PY
  python3 - \
    "$receipt_path" "$aab_path" "$mapping_path" "$registry_path" \
    "$policy_path" "$verifier_path" "$root" "$expected_rc" \
    "$expected_upload_signer" "$release_authority_sha" \
    "${PLAY_SUBMISSION_SOURCES[@]}" <<'PY' || return 1
from pathlib import Path
import hashlib
import json
import subprocess
import sys

(
    receipt_path,
    aab_path,
    mapping_path,
    registry_path,
    policy_path,
    verifier_path,
    root_path,
    expected_rc,
    expected_upload_signer,
    release_authority_sha,
    *submission_sources,
) = sys.argv[1:]
root = Path(root_path)

def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

receipt_file = Path(receipt_path)
receipt = json.loads(receipt_file.read_text(encoding="utf-8"))
policy_properties = dict(
    line.split("=", 1)
    for line in Path(policy_path).read_text(encoding="utf-8").splitlines()
    if line and not line.lstrip().startswith("#") and "=" in line
)
expected_source_digests = {
    relative: digest(root / relative)
    for relative in submission_sources
}
checks = {
    "schemaVersion": 3,
    "result": "passed",
    "versionCode": int(policy_properties["version_code"]),
    "versionName": policy_properties["version_name"],
    "submissionMode": "sealed_owner_approved",
    "verificationPhase": "initial_seal",
    "releaseCandidateId": expected_rc,
    "aabSha256": digest(Path(aab_path)),
    "aabUploadSignerCertificateSha256": expected_upload_signer,
    "approvedModuleRegistrySha256": digest(Path(registry_path)),
    "mappingSha256": digest(Path(mapping_path)),
    "verifierScriptSha256": digest(Path(verifier_path)),
    "playSubmissionPolicySha256": digest(Path(policy_path)),
    "sourceCommit": subprocess.check_output(
        ["git", "-C", str(root), "rev-parse", "HEAD"], text=True
    ).strip(),
    "sourceTree": subprocess.check_output(
        ["git", "-C", str(root), "rev-parse", "HEAD^{tree}"], text=True
    ).strip(),
    "sourceCheckoutClean": True,
    "detachedSource": True,
    "releaseAuthorityRepository": "uzaira0/methodic",
    "releaseAuthorityCommit": release_authority_sha,
    "playSubmissionSourceDigests": expected_source_digests,
}
for key, expected in checks.items():
    if receipt.get(key) != expected:
        raise SystemExit(f"prior sealed receipt mismatch for {key}")
PY
}

verify_play_delivered_policy_signer() {
  local expected="$1" approved="$2"
  expected="$(normalize_cert_sha256 "$expected")"
  approved="$(normalize_cert_sha256 "$approved")"
  [[ "$expected" =~ ^[0-9A-F]{64}$ && "$expected" == "$approved" ]]
}

require_compiled_manifest_pair() {
  local manifest_path="$1" field_name="$2" expected_value="$3"
  python3 - "$manifest_path" "$field_name" "$expected_value" <<'PY'
from pathlib import Path
import re
import sys

manifest = Path(sys.argv[1]).read_text(encoding="utf-8")
field = re.escape(sys.argv[2])
value = re.escape(sys.argv[3])
if re.search(rf'2: "{field}".{{0,300}}?3: "{value}"', manifest, re.DOTALL) is None:
    raise SystemExit(1)
PY
}

resolve_exact_unshared_package_uid() {
  local package_rows="$1" package_name="$2" package_uid match_count uid_count
  match_count="$(awk -v expected="package:$package_name" \
    '$1 == expected && $2 ~ /^uid:[0-9]+$/ { count++ } END { print count + 0 }' \
    "$package_rows")"
  [[ "$match_count" -eq 1 ]] || return 1
  package_uid="$(awk -v expected="package:$package_name" \
    '$1 == expected && $2 ~ /^uid:[0-9]+$/ { sub(/^uid:/, "", $2); print $2 }' \
    "$package_rows")"
  [[ "$package_uid" =~ ^[0-9]+$ ]] || return 1
  uid_count="$(awk -v uid="uid:$package_uid" '$2 == uid { count++ } END { print count + 0 }' \
    "$package_rows")"
  [[ "$uid_count" -eq 1 ]] || return 2
  printf '%s\n' "$package_uid"
}

extract_single_current_apk_signer() {
  local apksigner_command="$1" apk_path="$2" signer_output normalized
  local -a signer_digests=()
  signer_output="$("$apksigner_command" verify --print-certs "$apk_path")" || return 1
  while IFS= read -r normalized; do
    signer_digests+=("$normalized")
  done < <(
    printf '%s\n' "$signer_output" \
      | sed -En 's/^Signer #[0-9]+ certificate SHA-256 digest: (.*)$/\1/p'
  )
  [[ "${#signer_digests[@]}" -eq 1 ]] || return 2
  normalized="$(
    printf '%s' "${signer_digests[0]}" \
      | tr -d ':[:space:]' \
      | tr '[:lower:]' '[:upper:]'
  )"
  [[ "$normalized" =~ ^[0-9A-F]{64}$ ]] || return 1
  printf '%s\n' "$normalized"
}

create_evidence_output_dir() {
  local output_dir="$1" output_parent
  output_parent="$(dirname "$output_dir")"
  mkdir -p -- "$output_parent" || return 1
  mkdir -- "$output_dir"
}

validate_evidence_output_path() {
  local output_dir="$1" evidence_root="$2"
  python3 - "$output_dir" "$evidence_root" <<'PY'
from pathlib import Path
import sys

output = Path(sys.argv[1]).resolve(strict=False)
root = Path(sys.argv[2]).resolve(strict=False)
try:
    output.relative_to(root)
except ValueError as error:
    raise SystemExit("evidence output must remain below the project evidence root") from error
if output == root:
    raise SystemExit("evidence output must be a new child of the project evidence root")
PY
}

validate_runtime_ip_origin() {
  local origin="$1" origin_metadata="$2" ipv4_file="$3" ipv6_file="$4"
  python3 - "$origin" "$origin_metadata" "$ipv4_file" "$ipv6_file" <<'PY'
import ipaddress
from pathlib import Path
import sys
from urllib.parse import urlsplit

raw = sys.argv[1]
parsed = urlsplit(raw)
if (
    parsed.scheme != "https"
    or not parsed.hostname
    or parsed.username is not None
    or parsed.password is not None
    or parsed.path not in ("", "/")
    or parsed.query
    or parsed.fragment
):
    raise SystemExit("runtime egress origin must be an exact HTTPS root origin")
try:
    port = parsed.port or 443
except ValueError as error:
    raise SystemExit("runtime egress origin has an invalid port") from error
if not 1 <= port <= 65535:
    raise SystemExit("runtime egress origin port is outside 1-65535")
try:
    address = ipaddress.ip_address(parsed.hostname)
except ValueError as error:
    raise SystemExit(
        "runtime egress proof requires an HTTPS IP-literal origin so a shared virtual host "
        "cannot false-green"
    ) from error

ipv4 = [str(address)] if address.version == 4 else []
ipv6 = [str(address)] if address.version == 6 else []
Path(sys.argv[2]).write_text(
    f"origin={raw.rstrip('/')}\nhost={parsed.hostname}\nport={port}\n",
    encoding="utf-8",
)
Path(sys.argv[3]).write_text("".join(f"{value}\n" for value in ipv4), encoding="utf-8")
Path(sys.argv[4]).write_text("".join(f"{value}\n" for value in ipv6), encoding="utf-8")
PY
}

active_enrollment_summary_matches() {
  local ui_dump_path="$1" expected_origin="$2"
  python3 - "$ui_dump_path" "$expected_origin" <<'PY'
import sys
import xml.etree.ElementTree as ET

expected = sys.argv[2].rstrip("/")
root = ET.parse(sys.argv[1]).getroot()
matches = []
for node in root.iter("node"):
    text = node.attrib.get("text", "")
    origin_lines = [
        line.strip()[len("Server origin:"):].strip()
        for line in text.splitlines()
        if line.strip().startswith("Server origin:")
    ]
    exact_origin = len(origin_lines) == 1 and origin_lines[0] == expected
    active_uploads = "Uploads: active" in text or "Uploads: enabled" in text
    healthy_connection = "Connection: Healthy" in text
    if exact_origin and active_uploads and healthy_connection:
        matches.append(text)
if len(matches) != 1:
    raise SystemExit(1)
PY
}

write_apk_payload_identities() {
  local split_root="$1" output_path="$2"
  python3 - "$split_root" "$output_path" <<'PY'
import hashlib
from pathlib import Path
import sys
import zipfile

root = Path(sys.argv[1])
identities = []
def is_signing_artifact(name: str) -> bool:
    parts = name.split("/")
    if len(parts) != 2 or parts[0].upper() != "META-INF":
        return False
    filename = parts[1].upper()
    return filename == "MANIFEST.MF" or filename.endswith((".SF", ".RSA", ".DSA", ".EC"))

for apk in sorted(root.rglob("*.apk")):
    digest = hashlib.sha256()
    with zipfile.ZipFile(apk) as archive:
        names = sorted(
            name
            for name in archive.namelist()
            if not name.endswith("/")
            and not is_signing_artifact(name)
            and name != "stamp-cert-sha256"
        )
        for name in names:
            encoded = name.encode("utf-8")
            payload_hash = hashlib.sha256(archive.read(name)).digest()
            digest.update(len(encoded).to_bytes(4, "big"))
            digest.update(encoded)
            digest.update(payload_hash)
    identities.append(digest.hexdigest())
Path(output_path := sys.argv[2]).write_text(
    "".join(f"{value}\n" for value in sorted(identities)),
    encoding="utf-8",
)
PY
}

remove_runtime_ui_dump() {
  local cleanup_failed=0
  if [[ -n "$EGRESS_UI_DUMP_LOCAL" ]]; then
    rm -f -- "$EGRESS_UI_DUMP_LOCAL" 2>/dev/null || cleanup_failed=1
    [[ ! -e "$EGRESS_UI_DUMP_LOCAL" ]] || cleanup_failed=1
  fi
  if [[ -n "$EGRESS_UI_DUMP_REMOTE" && -n "$EGRESS_SERIAL" ]]; then
    adb -s "$EGRESS_SERIAL" shell rm -f "$EGRESS_UI_DUMP_REMOTE" >/dev/null 2>&1 ||
      cleanup_failed=1
    adb -s "$EGRESS_SERIAL" shell test ! -e "$EGRESS_UI_DUMP_REMOTE" >/dev/null 2>&1 ||
      cleanup_failed=1
  fi
  if [[ "$cleanup_failed" -eq 0 ]]; then
    EGRESS_UI_DUMP_LOCAL=""
    EGRESS_UI_DUMP_REMOTE=""
    return 0
  fi
  return 1
}

cleanup_runtime_egress() {
  local output_rules chain_result chain_status firewall_tool
  set +e
  EGRESS_CLEANUP_FAILED=0
  remove_runtime_ui_dump || EGRESS_CLEANUP_FAILED=1
  if [[ "$EGRESS_CLEANUP_NEEDED" -ne 1 ]]; then
    if [[ "$EGRESS_CLEANUP_FAILED" -ne 0 ]]; then
      printf 'ERROR: temporary runtime UI-dump deletion could not be proven.\n' >&2
    fi
    set -e
    return 0
  fi
  adb -s "$EGRESS_SERIAL" shell \
    "iptables -w 5 -t filter -D OUTPUT -m owner --uid-owner $EGRESS_PACKAGE_UID -j $EGRESS_CHAIN_NAME" \
    >/dev/null 2>&1
  adb -s "$EGRESS_SERIAL" shell \
    "iptables -w 5 -t filter -F $EGRESS_CHAIN_NAME" >/dev/null 2>&1
  adb -s "$EGRESS_SERIAL" shell \
    "iptables -w 5 -t filter -X $EGRESS_CHAIN_NAME" >/dev/null 2>&1
  adb -s "$EGRESS_SERIAL" shell \
    "ip6tables -w 5 -t filter -D OUTPUT -m owner --uid-owner $EGRESS_PACKAGE_UID -j $EGRESS_CHAIN_NAME" \
    >/dev/null 2>&1
  adb -s "$EGRESS_SERIAL" shell \
    "ip6tables -w 5 -t filter -F $EGRESS_CHAIN_NAME" >/dev/null 2>&1
  adb -s "$EGRESS_SERIAL" shell \
    "ip6tables -w 5 -t filter -X $EGRESS_CHAIN_NAME" >/dev/null 2>&1
  for firewall_tool in iptables ip6tables; do
    output_rules="$(
      adb -s "$EGRESS_SERIAL" shell "$firewall_tool -w 5 -t filter -S OUTPUT" 2>&1
    )" || EGRESS_CLEANUP_FAILED=1
    if grep -Fq -- "$EGRESS_CHAIN_NAME" <<<"$output_rules"; then
      EGRESS_CLEANUP_FAILED=1
    fi
    chain_status=0
    chain_result="$(
      adb -s "$EGRESS_SERIAL" shell \
        "$firewall_tool -w 5 -t filter -S $EGRESS_CHAIN_NAME" 2>&1
    )" || chain_status=$?
    if [[ "$chain_status" -eq 0 ]] ||
       ! grep -Eqi 'No chain/target/match|No such file|does a matching rule exist' \
         <<<"$chain_result"; then
      EGRESS_CLEANUP_FAILED=1
    fi
  done
  if [[ "$EGRESS_CLEANUP_FAILED" -eq 0 ]]; then
    EGRESS_CLEANUP_NEEDED=0
  fi
  set -e
}

if [[ "${CHRONICLE_PLAY_AAB_HELPERS_ONLY:-0}" == "1" ]]; then
  if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    printf 'ERROR: CHRONICLE_PLAY_AAB_HELPERS_ONLY is valid only when sourcing the verifier.\n' >&2
    exit 2
  fi
  return 0
fi

usage() {
  printf '%s\n' \
    'Usage: scripts/verify-play-aab.sh [options]' \
    '' \
    'Build and inspect the exact signed com.bcm.chronicle Play App Bundle.' \
    '' \
    'Options:' \
    '  --skip-build                 Inspect the existing bundle without rebuilding.' \
    '  --aab <path>                 Override the bundle path.' \
    '  --output-dir <path>          Write evidence below this project-scoped directory.' \
    '  --forbidden-literals <path>  Private newline-delimited strings that must be absent.' \
    '  --expected-cert-sha256 <hex> Require this upload-certificate SHA-256 fingerprint.' \
    '  --allow-unpinned-cert        Diagnostic only: record, but do not pin, signer identity.' \
    '  --expected-rc-id <id>         Require this immutable release-candidate identifier.' \
    '  --allow-unsealed-rc           Diagnostic only: permit the UNSEALED source default.' \
    '  --bundletool-jar <path>       Build and install the exact device split set.' \
    '  --device-serial <serial>      Device used for split installation and pullback proof.' \
    '  --verify-installed            Verify an already-installed split set without replacing it.' \
    '  --verify-play-delivered       Verify an already-installed Play-delivered split set without replacing it.' \
    '  --installed-cert-sha256 <sha> Required Play app-signing certificate SHA-256.' \
    '  --prior-sealed-receipt <path> Initial sealed receipt required for Play-delivered verification.' \
    '  --release-authority-sha <sha>  Exact methodic source SHA for receipt attestation.' \
    '  --runtime-egress-origin <url> On a rootable enrolled emulator, allow only this exact HTTPS origin,' \
    '                                relaunch Chronicle, and require allowed traffic with zero blocked traffic.' \
    '  --runtime-egress-seconds <n>  Bounded observation window (1-300 seconds; default: 45).' \
    '  --mapping <path>              R8 original-name mapping bound to this AAB.' \
    '  -h, --help                   Show this help.'
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --skip-build)
      SKIP_BUILD=1
      shift
      ;;
    --aab)
      AAB_PATH="${2:?missing AAB path}"
      shift 2
      ;;
    --output-dir)
      OUTPUT_DIR="${2:?missing output directory}"
      shift 2
      ;;
    --forbidden-literals)
      FORBIDDEN_LITERALS_FILE="${2:?missing forbidden-literals path}"
      shift 2
      ;;
    --expected-cert-sha256)
      EXPECTED_CERT_SHA256="${2:?missing certificate SHA-256 fingerprint}"
      shift 2
      ;;
    --allow-unpinned-cert)
      ALLOW_UNPINNED_CERT=1
      shift
      ;;
    --expected-rc-id)
      EXPECTED_RC_ID="${2:?missing release-candidate identifier}"
      shift 2
      ;;
    --allow-unsealed-rc)
      ALLOW_UNSEALED_RC=1
      shift
      ;;
    --bundletool-jar)
      BUNDLETOOL_JAR="${2:?missing bundletool jar path}"
      shift 2
      ;;
    --device-serial)
      DEVICE_SERIAL="${2:?missing device serial}"
      shift 2
      ;;
    --verify-installed)
      VERIFY_INSTALLED=1
      shift
      ;;
    --verify-play-delivered)
      VERIFY_INSTALLED=1
      VERIFY_PLAY_DELIVERED=1
      shift
      ;;
    --installed-cert-sha256)
      EXPECTED_INSTALLED_CERT_SHA256="${2:?missing installed signing certificate SHA-256}"
      shift 2
      ;;
    --prior-sealed-receipt)
      PRIOR_SEALED_RECEIPT="${2:?missing prior sealed receipt path}"
      shift 2
      ;;
    --release-authority-sha)
      RELEASE_AUTHORITY_SHA="${2:?missing release authority SHA}"
      shift 2
      ;;
    --runtime-egress-origin)
      RUNTIME_EGRESS_ORIGIN="${2:?missing runtime egress origin}"
      shift 2
      ;;
    --runtime-egress-seconds)
      RUNTIME_EGRESS_SECONDS="${2:?missing runtime egress duration}"
      shift 2
      ;;
    --mapping)
      MAPPING_PATH="${2:?missing R8 mapping path}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'ERROR: unknown option: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

validate_evidence_output_path \
  "$OUTPUT_DIR" "$ROOT_DIR/app/build/play-aab-evidence" || {
  printf 'ERROR: evidence output must be a new child below %s\n' \
    "$ROOT_DIR/app/build/play-aab-evidence" >&2
  exit 2
}

if [[ -n "$EXPECTED_RC_ID" && ! "$EXPECTED_RC_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]]; then
  printf 'ERROR: expected RC identifier is not a safe 1-128 character identifier.\n' >&2
  exit 2
fi
if [[ "$ALLOW_UNSEALED_RC" -eq 1 && -n "$EXPECTED_RC_ID" && "$EXPECTED_RC_ID" != "UNSEALED" ]]; then
  printf 'ERROR: --allow-unsealed-rc cannot be combined with a sealed expected RC identifier.\n' >&2
  exit 2
fi
if [[ -z "$EXPECTED_RC_ID" && "$ALLOW_UNSEALED_RC" -ne 1 ]]; then
  printf 'ERROR: refusing release verification without an expected RC identifier.\n' >&2
  printf 'Set CHRONICLE_RC_ID, pass --expected-rc-id, or use diagnostic-only --allow-unsealed-rc.\n' >&2
  exit 2
fi
if [[ -n "$RELEASE_AUTHORITY_SHA" && ! "$RELEASE_AUTHORITY_SHA" =~ ^[0-9a-fA-F]{40}$ ]]; then
  printf 'ERROR: release authority SHA must contain exactly 40 hexadecimal characters.\n' >&2
  exit 2
fi
if [[ -n "$BUNDLETOOL_JAR" || -n "$DEVICE_SERIAL" ]]; then
  [[ -n "$BUNDLETOOL_JAR" && -n "$DEVICE_SERIAL" ]] || {
    printf 'ERROR: --bundletool-jar and --device-serial must be supplied together.\n' >&2
    exit 2
  }
  [[ -f "$BUNDLETOOL_JAR" ]] || {
    printf 'ERROR: bundletool jar does not exist: %s\n' "$BUNDLETOOL_JAR" >&2
    exit 2
  }
fi
if [[ "$VERIFY_INSTALLED" -eq 1 && -z "$BUNDLETOOL_JAR" ]]; then
  printf 'ERROR: --verify-installed requires --bundletool-jar and --device-serial.\n' >&2
  exit 2
fi
if [[ "$VERIFY_PLAY_DELIVERED" -eq 1 ]]; then
  [[ -n "$EXPECTED_INSTALLED_CERT_SHA256" ]] || {
    printf 'ERROR: --verify-play-delivered requires --installed-cert-sha256.\n' >&2
    exit 2
  }
elif [[ -n "$EXPECTED_INSTALLED_CERT_SHA256" ]]; then
  printf 'ERROR: --installed-cert-sha256 is valid only with --verify-play-delivered.\n' >&2
  exit 2
fi
if [[ -n "$PRIOR_SEALED_RECEIPT" && "$VERIFY_PLAY_DELIVERED" -ne 1 ]]; then
  printf 'ERROR: --prior-sealed-receipt is valid only with --verify-play-delivered.\n' >&2
  exit 2
fi
if [[ -n "$EXPECTED_INSTALLED_CERT_SHA256" ]]; then
  EXPECTED_INSTALLED_CERT_SHA256="$(
    printf '%s' "$EXPECTED_INSTALLED_CERT_SHA256" \
      | tr -d '[:space:]:' \
      | tr '[:lower:]' '[:upper:]'
  )"
  [[ "$EXPECTED_INSTALLED_CERT_SHA256" =~ ^[0-9A-F]{64}$ ]] || {
    printf 'ERROR: installed signing certificate SHA-256 must contain exactly 64 hex digits.\n' >&2
    exit 2
  }
fi
if [[ -n "$RUNTIME_EGRESS_ORIGIN" && "$VERIFY_INSTALLED" -ne 1 ]]; then
  printf 'ERROR: --runtime-egress-origin requires --verify-installed.\n' >&2
  exit 2
fi
if [[ ! "$RUNTIME_EGRESS_SECONDS" =~ ^[0-9]+$ ]] ||
   (( RUNTIME_EGRESS_SECONDS < 1 || RUNTIME_EGRESS_SECONDS > 300 )); then
  printf 'ERROR: runtime egress duration must be an integer from 1 through 300 seconds.\n' >&2
  exit 2
fi

EXPECTED_CERT_SHA256="$(normalize_cert_sha256 "$EXPECTED_CERT_SHA256")"
if [[ "$ALLOW_UNPINNED_CERT" -eq 1 && -n "$EXPECTED_CERT_SHA256" ]]; then
  printf 'ERROR: --allow-unpinned-cert cannot be combined with an expected certificate.\n' >&2
  exit 2
fi
if [[ -z "$EXPECTED_CERT_SHA256" && "$ALLOW_UNPINNED_CERT" -ne 1 ]]; then
  printf 'ERROR: refusing release verification without an expected signer certificate.\n' >&2
  printf 'Set PLAY_UPLOAD_CERT_SHA256, pass --expected-cert-sha256, or use the diagnostic-only --allow-unpinned-cert option.\n' >&2
  exit 2
fi
if [[ -n "$EXPECTED_CERT_SHA256" && ! "$EXPECTED_CERT_SHA256" =~ ^[0-9A-F]{64}$ ]]; then
  printf 'ERROR: expected certificate SHA-256 must contain exactly 64 hexadecimal characters.\n' >&2
  exit 2
fi
if [[ "$ALLOW_UNPINNED_CERT" -eq 0 && "$ALLOW_UNSEALED_RC" -eq 0 ]]; then
  SEALED_SUBMISSION=1
fi

verification_phase="technical"
if [[ "$SEALED_SUBMISSION" -eq 1 ]]; then
  [[ -n "$RELEASE_AUTHORITY_SHA" ]] || {
    printf 'ERROR: sealed verification requires --release-authority-sha.\n' >&2
    exit 2
  }
  RELEASE_AUTHORITY_SHA="${RELEASE_AUTHORITY_SHA,,}"
  verification_phase="initial_seal"
  if [[ "$VERIFY_PLAY_DELIVERED" -eq 1 ]]; then
    verification_phase="play_delivery"
    [[ -n "$PRIOR_SEALED_RECEIPT" ]] || {
      printf 'ERROR: sealed Play-delivered verification requires --prior-sealed-receipt.\n' >&2
      exit 2
    }
  elif [[ -n "$PRIOR_SEALED_RECEIPT" ]]; then
    printf 'ERROR: an initial seal cannot consume a prior sealed receipt.\n' >&2
    exit 2
  fi
  python3 "$ROOT_DIR/scripts/verify-play-submission.py" --root "$ROOT_DIR" --sealed >/dev/null
  verify_sealed_source_checkout "$ROOT_DIR" "$SKIP_BUILD" "$verification_phase" || {
    printf 'ERROR: sealed source preflight failed before the build.\n' >&2
    exit 1
  }
  verify_release_authority_binding "$ROOT_DIR" "$RELEASE_AUTHORITY_SHA" || {
    printf 'ERROR: sealed source is not bound to the exact release-authority gitlink.\n' >&2
    exit 1
  }
  if [[ "$VERIFY_PLAY_DELIVERED" -eq 1 ]]; then
    policy_play_signer="$(read_submission_property \
      "$ROOT_DIR/store/play/privacy.properties" \
      play_app_signing_certificate_sha256)"
    verify_play_delivered_policy_signer \
      "$EXPECTED_INSTALLED_CERT_SHA256" "$policy_play_signer" || {
      printf 'ERROR: installed certificate differs from the owner-approved Play app-signing certificate.\n' >&2
      exit 1
    }
  fi
fi

for command_name in unzip protoc jarsigner keytool gitleaks rg shasum tr find sort sed head awk python3 cmp; do
  command -v "$command_name" >/dev/null || {
    printf 'ERROR: required command is unavailable: %s\n' "$command_name" >&2
    exit 2
  }
done
if [[ -n "$BUNDLETOOL_JAR" ]]; then
  for command_name in java adb; do
    command -v "$command_name" >/dev/null || {
      printf 'ERROR: split verification requires unavailable command: %s\n' "$command_name" >&2
      exit 2
    }
  done
fi
if [[ "$verification_phase" == "play_delivery" ]]; then
  gh_attestation_command="$(resolve_gh_attestation_command)"
  command -v "$gh_attestation_command" >/dev/null || {
    printf 'ERROR: Play-delivery verification requires GitHub CLI attestation support.\n' >&2
    exit 2
  }
fi

repo_preflight="${REPO_PREFLIGHT:-}"
if [[ -z "$repo_preflight" ]] && command -v repo-canonical-preflight >/dev/null 2>&1; then
  repo_preflight="$(command -v repo-canonical-preflight)"
fi
if [[ -n "$repo_preflight" ]]; then
  "$repo_preflight" --explain >/dev/null
  "$repo_preflight" >/dev/null
fi

if ! create_evidence_output_dir "$OUTPUT_DIR"; then
  printf 'ERROR: evidence output already exists; no-clobber policy refuses reuse: %s\n' \
    "$OUTPUT_DIR" >&2
  exit 1
fi
if [[ "$verification_phase" == "play_delivery" ]]; then
  [[ -f "$AAB_PATH" && -f "$MAPPING_PATH" ]] || {
    printf 'ERROR: Play-delivered verification requires the exact previously sealed AAB and mapping.\n' >&2
    exit 1
  }
  sealed_aab_snapshot="$OUTPUT_DIR/prior-sealed-app-play-release.aab"
  sealed_mapping_snapshot="$OUTPUT_DIR/prior-sealed-mapping.txt"
  sealed_receipt_snapshot="$OUTPUT_DIR/prior-sealed-receipt.json"
  snapshot_stable_input "$AAB_PATH" "$sealed_aab_snapshot" 'prior sealed AAB' || exit 1
  snapshot_stable_input "$MAPPING_PATH" "$sealed_mapping_snapshot" 'prior sealed mapping' || exit 1
  snapshot_stable_input "$PRIOR_SEALED_RECEIPT" "$sealed_receipt_snapshot" \
    'prior sealed receipt' || exit 1
  AAB_PATH="$sealed_aab_snapshot"
  MAPPING_PATH="$sealed_mapping_snapshot"
  PRIOR_SEALED_RECEIPT="$sealed_receipt_snapshot"
  verify_prior_sealed_receipt \
    "$PRIOR_SEALED_RECEIPT" \
    "$AAB_PATH" \
    "$MAPPING_PATH" \
    "$ROOT_DIR/app/src/play/assets/approved-module-registry.json" \
    "$ROOT_DIR/store/play/privacy.properties" \
    "$ROOT_DIR/scripts/verify-play-aab.sh" \
    "$ROOT_DIR" \
    "$EXPECTED_RC_ID" \
    "$EXPECTED_CERT_SHA256" \
    "$RELEASE_AUTHORITY_SHA" \
    "$OUTPUT_DIR/prior-sealed-receipt-attestation.json" || {
    printf 'ERROR: prior sealed receipt is not an authenticated matching release seal.\n' >&2
    exit 1
  }
  chmod 600 "$OUTPUT_DIR/prior-sealed-receipt-attestation.json"
fi
build_started_epoch="$(date +%s)"
if [[ "$SKIP_BUILD" -eq 0 ]]; then
  (
    cd "$ROOT_DIR"
    # Bundle tasks can otherwise remain UP-TO-DATE after a source commit whose only artifact
    # difference is embedded VCS provenance. Force one exact post-commit release rebuild.
    ./gradlew :app:bundlePlayRelease --rerun-tasks --no-build-cache --no-daemon --console=plain
  ) 2>&1 | tee "$OUTPUT_DIR/bundle-play-release.txt"
fi

[[ -f "$AAB_PATH" ]] || {
  printf 'ERROR: Play App Bundle is missing: %s\n' "$AAB_PATH" >&2
  exit 1
}
[[ -f "$MAPPING_PATH" ]] || {
  printf 'ERROR: R8 original-name mapping is missing for this Play bundle: %s\n' "$MAPPING_PATH" >&2
  exit 1
}
if [[ "$SKIP_BUILD" -eq 0 ]]; then
  if stat -f '%m' "$AAB_PATH" >/dev/null 2>&1; then
    aab_mtime="$(stat -f '%m' "$AAB_PATH")"
  else
    aab_mtime="$(stat -c '%Y' "$AAB_PATH")"
  fi
  (( aab_mtime >= build_started_epoch )) || {
    printf 'ERROR: refusing a stale Play App Bundle.\n' >&2
    exit 1
  }
fi

submission_readiness_args=(play)
if [[ "$SEALED_SUBMISSION" -eq 1 ]]; then
  submission_readiness_args+=(--sealed)
fi
if [[ "$verification_phase" == "play_delivery" ]]; then
  python3 "$ROOT_DIR/scripts/verify-play-submission.py" --root "$ROOT_DIR" --sealed \
    >"$OUTPUT_DIR/store-readiness.txt"
  printf '%s\n' \
    'Play-delivery mode validates the compiled manifest directly from the previously sealed AAB.' \
    >>"$OUTPUT_DIR/store-readiness.txt"
else
  "$ROOT_DIR/scripts/verify-store-readiness.sh" "${submission_readiness_args[@]}" \
    >"$OUTPUT_DIR/store-readiness.txt"
fi
submission_mode="technical_pending_owner_approval"
if [[ "$SEALED_SUBMISSION" -eq 1 ]]; then
  submission_mode="sealed_owner_approved"
  policy_upload_cert="$(read_submission_property \
    "$ROOT_DIR/store/play/privacy.properties" upload_certificate_sha256)"
  policy_upload_cert="$(normalize_cert_sha256 "$policy_upload_cert")"
  [[ "$policy_upload_cert" == "$EXPECTED_CERT_SHA256" ]] || {
    printf 'ERROR: expected upload certificate differs from the owner-approved submission policy.\n' >&2
    exit 1
  }
  policy_rc_id="$(read_submission_property \
    "$ROOT_DIR/store/play/privacy.properties" release_candidate_id)"
  [[ "$policy_rc_id" == "$EXPECTED_RC_ID" ]] || {
    printf 'ERROR: expected RC identifier differs from the owner-approved submission policy.\n' >&2
    exit 1
  }
fi
printf '%s\n' "$submission_mode" >"$OUTPUT_DIR/play-submission-mode.txt"
(
  cd "$ROOT_DIR"
  for submission_source in "${PLAY_SUBMISSION_SOURCES[@]}"; do
    shasum -a 256 "$submission_source"
  done
) >"$OUTPUT_DIR/play-submission-sources.sha256"
cp "$ROOT_DIR/store/play/privacy.properties" "$OUTPUT_DIR/play-submission.properties"
chmod 600 \
  "$OUTPUT_DIR/play-submission-mode.txt" \
  "$OUTPUT_DIR/play-submission-sources.sha256" \
  "$OUTPUT_DIR/play-submission.properties"

shasum -a 256 "$AAB_PATH" | tee "$OUTPUT_DIR/app-play-release.aab.sha256"
jarsigner -verify "$AAB_PATH" >"$OUTPUT_DIR/jarsigner-verify.txt" 2>&1
rg -q '^jar verified\.$' "$OUTPUT_DIR/jarsigner-verify.txt" || {
  printf 'ERROR: jarsigner did not verify the bundle.\n' >&2
  exit 1
}

cert_details="$(LC_ALL=C keytool -printcert -jarfile "$AAB_PATH")"
actual_cert_sha256="$(
  printf '%s\n' "$cert_details" \
    | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' \
    | head -n 1
)"
actual_cert_sha256="$(normalize_cert_sha256 "$actual_cert_sha256")"
if [[ ! "$actual_cert_sha256" =~ ^[0-9A-F]{64}$ ]]; then
  printf 'ERROR: could not extract a SHA-256 signer-certificate fingerprint from the AAB.\n' >&2
  exit 1
fi
printf 'SHA256 %s\n' "$actual_cert_sha256" >"$OUTPUT_DIR/signer-certificate.sha256"
if [[ -n "$EXPECTED_CERT_SHA256" && "$actual_cert_sha256" != "$EXPECTED_CERT_SHA256" ]]; then
  printf 'ERROR: AAB signer certificate does not match the expected Play upload certificate.\n' >&2
  exit 1
fi

unpack_dir="$OUTPUT_DIR/unpacked"
mkdir -p "$unpack_dir"
unzip -q "$AAB_PATH" -d "$unpack_dir"
protoc --decode_raw \
  <"$unpack_dir/base/manifest/AndroidManifest.xml" \
  >"$OUTPUT_DIR/manifest.decode-raw.txt"
protoc --decode_raw \
  <"$unpack_dir/BUNDLE-METADATA/com.android.tools.build.libraries/dependencies.pb" \
  >"$OUTPUT_DIR/dependencies.decode-raw.txt"

for excluded_dependency in \
  'androidx.health.connect' \
  'connect-client' \
  'play-services-location' \
  'collection-sensors' \
  'collection-interaction' \
  'collection-audio' \
  'collection-activity' \
  'collection-health'; do
  if rg -Fq "$excluded_dependency" "$OUTPUT_DIR/dependencies.decode-raw.txt"; then
    printf 'ERROR: excluded Play dependency is packaged in the bundle: %s\n' \
      "$excluded_dependency" >&2
    exit 1
  fi
done

manifest="$OUTPUT_DIR/manifest.decode-raw.txt"
for required_manifest_value in \
  '3: "com.bcm.chronicle"' \
  '3: "android.permission.PACKAGE_USAGE_STATS"' \
  '3: "android.permission.ACCESS_NETWORK_STATE"' \
  '3: "android.permission.POST_NOTIFICATIONS"' \
  '3: "android.permission.RECEIVE_BOOT_COMPLETED"' \
  '3: "android.permission.FOREGROUND_SERVICE"' \
  '3: "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"' \
  '3: "com.openlattice.chronicle.services.notifications.DeviceUnlockMonitoringService"' \
  '3: "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"'; do
  rg -Fq "$required_manifest_value" "$manifest" || {
    printf 'ERROR: required compiled-manifest value is absent: %s\n' "$required_manifest_value" >&2
    exit 1
  }
done

python3 - "$manifest" <<'PY'
import sys

text = open(sys.argv[1], encoding="utf-8").read()
lines = text.splitlines()
stack = []
blocks = []
for index, line in enumerate(lines):
    if line.rstrip().endswith("{"):
        stack.append(index)
    if line.strip() == "}":
        if not stack:
            raise SystemExit("ERROR: malformed decoded manifest brace structure")
        start = stack.pop()
        blocks.append("\n".join(lines[start:index + 1]))

def smallest_element(kind: str, marker: str) -> str:
    matches = [
        block for block in blocks
        if f'3: "{kind}"' in block and f'3: "{marker}"' in block
    ]
    if not matches:
        raise SystemExit(f"ERROR: compiled manifest omits {kind} {marker}")
    return min(matches, key=len)

service = smallest_element(
    "service",
    "com.openlattice.chronicle.services.notifications.DeviceUnlockMonitoringService",
)
required_service_values = (
    '2: "foregroundServiceType"',
    '3: "specialUse"',
    '3: "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"',
    '3: "Researchers need to prompt at every unlock to determine whether target user or other is using the device."',
)
for value in required_service_values:
    if value not in service:
        raise SystemExit(
            "ERROR: unlock service is missing its exact special-use foreground-service contract: " + value
        )

boot = smallest_element(
    "receiver",
    "com.openlattice.chronicle.receivers.lifecycle.StartOnBoot",
)
if '3: "android.intent.action.BOOT_COMPLETED"' not in boot:
    raise SystemExit("ERROR: StartOnBoot is not structurally bound to BOOT_COMPLETED")
PY

registry_source="$ROOT_DIR/app/src/play/assets/approved-module-registry.json"
[[ -f "$registry_source" ]] || {
  printf 'ERROR: approved Play module registry is absent from source.\n' >&2
  exit 1
}
registry_ids_csv="$(python3 - "$registry_source" <<'PY'
import json
import re
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    registry = json.load(source)
if registry.get("schemaVersion") != 1 or registry.get("distribution") != "PLAY":
    raise SystemExit("ERROR: approved Play registry has the wrong schema/distribution")
modules = registry.get("modules")
if not isinstance(modules, list) or not modules:
    raise SystemExit("ERROR: approved Play registry has no modules")
required = {"id", "fields", "permissions", "retention", "destinations", "upload", "deletion"}
expected_module_permissions = {
    "usage_events": {"android.permission.PACKAGE_USAGE_STATS"},
    "in_app_activity_class": {"android.permission.PACKAGE_USAGE_STATS"},
    "device_lifecycle": {"android.permission.RECEIVE_BOOT_COMPLETED"},
    "user_identification": {
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
        "android.permission.RECEIVE_BOOT_COMPLETED",
    },
    "upload_telemetry": set(),
    "battery_telemetry": set(),
    "connectivity_state": {"android.permission.ACCESS_NETWORK_STATE"},
    "device_settings": set(),
}
ids = []
for module in modules:
    if not isinstance(module, dict) or not required.issubset(module):
        raise SystemExit("ERROR: approved Play registry module is incomplete")
    module_id = module["id"]
    if not isinstance(module_id, str) or re.fullmatch(r"[a-z][a-z0-9_]*", module_id) is None:
        raise SystemExit("ERROR: approved Play registry contains an invalid module id")
    permissions = module["permissions"]
    if not isinstance(permissions, list) or not all(isinstance(value, str) for value in permissions):
        raise SystemExit("ERROR: approved Play registry contains invalid permissions")
    if set(permissions) != expected_module_permissions.get(module_id):
        raise SystemExit(
            "ERROR: approved Play registry permission contract drifted for " + module_id
        )
    ids.append(module_id)
if len(ids) != len(set(ids)):
    raise SystemExit("ERROR: approved Play registry contains duplicate module ids")
if set(ids) != set(expected_module_permissions):
    raise SystemExit("ERROR: approved Play registry permission map is incomplete")
print(",".join(ids))
PY
)"
registry_sha256="$(shasum -a 256 "$registry_source" | awk '{print $1}')"
printf '%s  approved-module-registry.json\n' "$registry_sha256" \
  >"$OUTPUT_DIR/approved-module-registry.sha256"
cp "$registry_source" "$OUTPUT_DIR/approved-module-registry.json"
packaged_registry="$unpack_dir/base/assets/approved-module-registry.json"
[[ -f "$packaged_registry" ]] || {
  printf 'ERROR: approved module registry is absent from the Play bundle.\n' >&2
  exit 1
}
packaged_registry_sha256="$(shasum -a 256 "$packaged_registry" | awk '{print $1}')"
[[ "$packaged_registry_sha256" == "$registry_sha256" ]] || {
  printf 'ERROR: bundled approved module registry differs from sealed source.\n' >&2
  exit 1
}

effective_rc_id="$EXPECTED_RC_ID"
if [[ "$ALLOW_UNSEALED_RC" -eq 1 ]]; then
  effective_rc_id="UNSEALED"
fi
for release_metadata in \
  'com.bcm.chronicle.RELEASE_CANDIDATE_ID' \
  'com.bcm.chronicle.APPROVED_MODULE_REGISTRY_SHA256' \
  "$effective_rc_id" \
  "$registry_sha256"; do
  rg -Fq "$release_metadata" "$manifest" || {
    printf 'ERROR: release metadata is absent from compiled manifest: %s\n' "$release_metadata" >&2
    exit 1
  }
done
if [[ "$effective_rc_id" == "UNSEALED" && "$ALLOW_UNSEALED_RC" -ne 1 ]]; then
  printf 'ERROR: an UNSEALED bundle cannot pass release verification.\n' >&2
  exit 1
fi
printf '%s\n' "$effective_rc_id" >"$OUTPUT_DIR/release-candidate-id.txt"
policy_version_code="$(read_submission_property \
  "$ROOT_DIR/store/play/privacy.properties" version_code)"
policy_version_name="$(read_submission_property \
  "$ROOT_DIR/store/play/privacy.properties" version_name)"
for required_manifest_field in \
  "versionCode=$policy_version_code" \
  "versionName=$policy_version_name" \
  'minSdkVersion=23' \
  'targetSdkVersion=36'; do
  manifest_field="${required_manifest_field%%=*}"
  manifest_value="${required_manifest_field#*=}"
  require_compiled_manifest_pair "$manifest" "$manifest_field" "$manifest_value" || {
    printf 'ERROR: required compiled-manifest field/value pair is absent: %s=%s\n' \
      "$manifest_field" "$manifest_value" >&2
    exit 1
  }
done
for forbidden_manifest_value in \
  'android.permission.health.' \
  'com.google.android.apps.healthdata' \
  'androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE' \
  'androidx.health.connect.action.SHOW_PERMISSIONS_RATIONALE' \
  'android.permission.ACTIVITY_RECOGNITION' \
  'com.google.android.gms.permission.ACTIVITY_RECOGNITION' \
  'android.permission.HIGH_SAMPLING_RATE_SENSORS' \
  'android.permission.BIND_ACCESSIBILITY_SERVICE' \
  'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE' \
  'HealthConnectRationaleActivity' \
  'HardwareSensorService' \
  'InteractionCollectionService' \
  'NotificationListener'; do
  if rg -Fq "$forbidden_manifest_value" "$manifest"; then
    printf 'ERROR: excluded Play capability remains in the compiled manifest: %s\n' \
      "$forbidden_manifest_value" >&2
    exit 1
  fi
done

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk_root" ]]; then
  for sdk_candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [[ -d "$sdk_candidate" ]]; then
      sdk_root="$sdk_candidate"
      break
    fi
  done
fi
[[ -n "$sdk_root" ]] || {
  printf 'ERROR: Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT.\n' >&2
  exit 2
}
dexdump_bin="$(find "$sdk_root/build-tools" -type f -name dexdump 2>/dev/null | sort -V | tail -n 1)"
[[ -x "$dexdump_bin" ]] || {
  printf 'ERROR: dexdump is unavailable below %s/build-tools.\n' "$sdk_root" >&2
  exit 2
}

build_config="$OUTPUT_DIR/build-config.dexdump.txt"
: >"$build_config"
for dex in "$unpack_dir"/base/dex/classes*.dex; do
  "$dexdump_bin" -d "$dex" \
    | rg -A 100 "Class descriptor  : 'Lcom/openlattice/chronicle/BuildConfig;'" \
    >>"$build_config" || true
done
[[ -s "$build_config" ]] || {
  printf 'ERROR: compiled BuildConfig was not found in the bundle.\n' >&2
  exit 1
}
assert_build_config_value() {
  local field_name="$1"
  local expected_value="$2"
  python3 - "$build_config" "$field_name" "$expected_value" <<'PY'
import re
import sys

source, field_name, expected = sys.argv[1:]
text = open(source, encoding="utf-8").read()
pattern = re.compile(
    r"^[ ]+name[ ]+: '" + re.escape(field_name) +
    r"'\n(?:^[ ]+(?:type|access)[ ]+:.*\n)*^[ ]+value[ ]+: (.+)$",
    re.MULTILINE,
)
match = pattern.search(text)
if match is None or match.group(1) != expected:
    actual = "missing" if match is None else match.group(1)
    raise SystemExit(
        f"ERROR: compiled BuildConfig {field_name} expected {expected}, found {actual}"
    )
PY
}

assert_build_config_value ALLOW_ANY_SERVER true
assert_build_config_value ALLOW_RESTRICTED_RESEARCH_PERMISSIONS false
assert_build_config_value DISTRIBUTION_CHANNEL '"PLAY"'
assert_build_config_value HAS_GOOGLE_SERVICES false
assert_build_config_value HAS_HEALTH_CONNECT false
assert_build_config_value HAS_APP_NETWORK_USAGE false
assert_build_config_value MOBILE_SIGNING_SECRET '""'
assert_build_config_value CHRONICLE_PRODUCTION_HOST '""'
assert_build_config_value RELEASE_CANDIDATE_ID "\"$effective_rc_id\""
assert_build_config_value APPROVED_MODULE_REGISTRY_SHA256 "\"$registry_sha256\""
assert_build_config_value PLAY_APPROVED_MODULE_IDS "\"$registry_ids_csv\""

dex_inventory="$OUTPUT_DIR/dex-descriptors.txt"
: >"$dex_inventory"
while IFS= read -r -d '' dex; do
  "$dexdump_bin" -d "$dex" \
    | LC_ALL=C sed -n "s/.*Class descriptor  : '\([^']*\)'.*/\1/p" \
    >>"$dex_inventory"
done < <(find "$unpack_dir" -type f -path '*/dex/*.dex' -print0)
sort -u "$dex_inventory" -o "$dex_inventory"
[[ -s "$dex_inventory" ]] || {
  printf 'ERROR: no DEX descriptors were inventoried from the bundle.\n' >&2
  exit 1
}

consent_trigger_dexdump="$OUTPUT_DIR/consent-trigger.dexdump.txt"
: >"$consent_trigger_dexdump"
while IFS= read -r -d '' dex; do
  LC_ALL=C "$dexdump_bin" -d "$dex" \
    | LC_ALL=C awk '
        /^Class #[0-9]+/ { capture = 0 }
        /Class descriptor  : '\''Lcom\/openlattice\/chronicle\/collection\/ConsentTrigger;'\''/ {
          capture = 1
        }
        capture { print }
      ' \
    >>"$consent_trigger_dexdump"
done < <(find "$unpack_dir" -type f -path '*/dex/*.dex' -print0)
rg -Fq "Class descriptor  : 'Lcom/openlattice/chronicle/collection/ConsentTrigger;'" \
  "$consent_trigger_dexdump" || {
  printf 'ERROR: reflection-bound consent enum is absent or obfuscated in delivered DEX.\n' >&2
  exit 1
}
for consent_trigger_constant in ENROLLMENT PARTICIPANT_TOGGLE SETTINGS_CHANGE WITHDRAWAL; do
  rg -Fq "name          : '$consent_trigger_constant'" "$consent_trigger_dexdump" || {
    printf 'ERROR: reflection-bound consent enum constant is absent or obfuscated in delivered DEX: %s\n' \
      "$consent_trigger_constant" >&2
    exit 1
  }
done

participant_form_kind_dexdump="$OUTPUT_DIR/participant-form-kind.dexdump.txt"
: >"$participant_form_kind_dexdump"
while IFS= read -r -d '' dex; do
  LC_ALL=C "$dexdump_bin" -d "$dex" \
    | LC_ALL=C awk '
        /^Class #[0-9]+/ { capture = 0 }
        /Class descriptor  : '\''Lcom\/openlattice\/chronicle\/participantaccess\/ParticipantFormKind;'\''/ {
          capture = 1
        }
        capture { print }
      ' \
    >>"$participant_form_kind_dexdump"
done < <(find "$unpack_dir" -type f -path '*/dex/*.dex' -print0)
rg -Fq "Class descriptor  : 'Lcom/openlattice/chronicle/participantaccess/ParticipantFormKind;'" \
  "$participant_form_kind_dexdump" || {
  printf 'ERROR: reflection-bound reminder enum is absent or obfuscated in delivered DEX.\n' >&2
  exit 1
}
for participant_form_kind_constant in ENROLLMENT APP_USAGE QUESTIONNAIRE TIME_USE_DIARY PORTAL; do
  rg -Fq "name          : '$participant_form_kind_constant'" \
    "$participant_form_kind_dexdump" || {
    printf 'ERROR: reflection-bound reminder enum constant is absent or obfuscated in delivered DEX: %s\n' \
      "$participant_form_kind_constant" >&2
    exit 1
  }
done

for forbidden_dex_descriptor in \
  'Lcom/openlattice/chronicle/api/RestrictedChronicleStudyApi;' \
  'Lcom/openlattice/chronicle/android/AndroidSensorSample;' \
  'Lcom/openlattice/chronicle/collection/AndroidActivityRecognitionEvent;' \
  'Lcom/openlattice/chronicle/collection/AndroidAudioActivityEvent;' \
  'Lcom/openlattice/chronicle/collection/AndroidAudioContentEvent;' \
  'Lcom/openlattice/chronicle/collection/AndroidHealthMetricEvent;' \
  'Lcom/openlattice/chronicle/collection/AndroidAppNetworkUsageEvent;' \
  'Lcom/openlattice/chronicle/collection/AndroidInteractionEvent;' \
  'Lcom/openlattice/chronicle/collection/AndroidNotificationActivityEvent;' \
  'Lcom/openlattice/chronicle/collection/AndroidSleepEvent;' \
  'Lcom/openlattice/chronicle/collection/audio/AudioCaptureController;' \
  'Lcom/openlattice/chronicle/collection/audio/AudioUploadWorker;' \
  'Lcom/openlattice/chronicle/collection/device/HealthConnectPermissions;' \
  'Lcom/openlattice/chronicle/collection/device/AppNetworkUsageCollectionModule;' \
  'Lcom/openlattice/chronicle/collection/device/AppNetworkUsageModuleHolder;' \
  'Lcom/openlattice/chronicle/collection/interaction/InteractionCollectionService;' \
  'Lcom/openlattice/chronicle/collection/interaction/InteractionUploadWorker;' \
  'Lcom/openlattice/chronicle/collection/notifications/QuestionnaireCollectionModule;' \
  'Lcom/openlattice/chronicle/collection/notifications/QuestionnaireModuleHolder;' \
  'Lcom/openlattice/chronicle/receivers/lifecycle/SurveyNotificationsReceiver;' \
  'Lcom/openlattice/chronicle/services/notifications/NotificationsWorker;' \
  'Lcom/openlattice/chronicle/collection/sensors/HardwareSensorService;' \
  'Lcom/openlattice/chronicle/collection/sensors/SensorUploadWorker;' \
  'Lcom/openlattice/chronicle/services/notifications/NotificationListener;' \
  'Lcom/openlattice/chronicle/collection/health/HealthConnect'; do
  if rg -Fq "$forbidden_dex_descriptor" "$dex_inventory"; then
    printf 'ERROR: excluded Play collector descriptor is packaged: %s\n' \
      "$forbidden_dex_descriptor" >&2
    exit 1
  fi
done

find "$unpack_dir" -type f -path '*/lib/*/*.so' \
  | sed "s#^$unpack_dir/##" \
  | LC_ALL=C sort \
  >"$OUTPUT_DIR/native-libraries.txt"
find "$unpack_dir" -type f \( -path '*/res/*' -o -path '*/assets/*' \) \
  | sed "s#^$unpack_dir/##" \
  | LC_ALL=C sort \
  >"$OUTPUT_DIR/resources-inventory.txt"
embedded_mapping="$unpack_dir/BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map"
[[ -f "$embedded_mapping" ]] || {
  printf 'ERROR: AAB does not contain its R8 original-name mapping.\n' >&2
  exit 1
}
if ! cmp -s "$MAPPING_PATH" "$embedded_mapping"; then
  printf 'ERROR: supplied R8 mapping does not byte-match the mapping embedded in the AAB.\n' >&2
  exit 1
fi
python3 - "$embedded_mapping" <<'PY'
import re
import sys

mapping = open(sys.argv[1], encoding="utf-8").read()
required = {
    "com.openlattice.chronicle.api.EnrollmentPreviewResponse": {
        "getManifest",
        "getManifestDigest",
    },
    "com.openlattice.chronicle.api.MobileEnrollmentManifest": {
        "getSchemaVersion",
        "getServerOrigin",
        "getStudyId",
        "getParticipantId",
        "getParticipantPolicy",
        "getCollectionSettings",
        "getSettingsVersion",
        "getIssuedAt",
        "getExpiresAt",
    },
    "com.openlattice.chronicle.participantaccess.MobileReminderConfiguration": {
        "getParticipationStatus",
        "getForms",
    },
    "com.openlattice.chronicle.participantaccess.MobileReminderForm": {
        "getFormKind",
        "getResourceId",
        "getTitle",
        "getRecurrenceRule",
        "getAccessCode",
        "getAccessCodeExpiresAt",
    },
}
for class_name, members in required.items():
    header = f"{class_name} -> {class_name}:"
    start = mapping.find(header)
    if start < 0:
        raise SystemExit(
            f"ERROR: reflection-bound enrollment DTO was renamed or removed by R8: {class_name}"
        )
    next_class = re.search(
        r"\n(?=[A-Za-z0-9_.$]+ -> [^:\n]+:)",
        mapping[start + len(header):],
    )
    end = len(mapping) if next_class is None else start + len(header) + next_class.start()
    block = mapping[start:end]
    if "void <init>(" not in block:
        raise SystemExit(
            f"ERROR: reflection-bound enrollment DTO constructor was removed by R8: {class_name}"
        )
    for member in members:
        if not re.search(rf"^[ ]+.*\b{re.escape(member)}\([^)]*\).* -> {re.escape(member)}$", block, re.MULTILINE):
            raise SystemExit(
                f"ERROR: reflection-bound enrollment DTO member was removed or renamed by R8: "
                f"{class_name}.{member}"
            )

required_enums = {
    "com.openlattice.chronicle.collection.ConsentTrigger": (
        "ENROLLMENT",
        "PARTICIPANT_TOGGLE",
        "SETTINGS_CHANGE",
        "WITHDRAWAL",
    ),
    "com.openlattice.chronicle.participantaccess.ParticipantFormKind": (
        "ENROLLMENT",
        "APP_USAGE",
        "QUESTIONNAIRE",
        "TIME_USE_DIARY",
        "PORTAL",
    ),
}
for enum_name, constants in required_enums.items():
    enum_header = f"{enum_name} -> {enum_name}:"
    enum_start = mapping.find(enum_header)
    if enum_start < 0:
        raise SystemExit(
            f"ERROR: reflection-bound enum was renamed or removed by R8: {enum_name}"
        )
    enum_next_class = re.search(
        r"\n(?=[A-Za-z0-9_.$]+ -> [^:\n]+:)",
        mapping[enum_start + len(enum_header):],
    )
    enum_end = (
        len(mapping)
        if enum_next_class is None
        else enum_start + len(enum_header) + enum_next_class.start()
    )
    enum_block = mapping[enum_start:enum_end]
    for constant in constants:
        renamed = re.search(
            rf"^[ ]+{re.escape(enum_name)} {constant} -> ([^ ]+)$",
            enum_block,
            re.MULTILINE,
        )
        if renamed is not None and renamed.group(1) != constant:
            raise SystemExit(
                f"ERROR: reflection-bound enum constant was removed or renamed by R8: "
                f"{enum_name}.{constant}"
            )
PY
cp "$embedded_mapping" "$OUTPUT_DIR/mapping.txt"
shasum -a 256 "$embedded_mapping" >"$OUTPUT_DIR/mapping.txt.sha256"
for forbidden_original_class in \
  'com.openlattice.chronicle.api.RestrictedChronicleStudyApi' \
  'com.openlattice.chronicle.collection.audio.AudioCaptureController' \
  'com.openlattice.chronicle.collection.audio.AudioUploadWorker' \
  'com.openlattice.chronicle.collection.device.HealthConnectPermissions' \
  'com.openlattice.chronicle.collection.device.AppNetworkUsageCollectionModule' \
  'com.openlattice.chronicle.collection.device.AppNetworkUsageModuleHolder' \
  'com.openlattice.chronicle.collection.device.HealthMetricCollectionModule' \
  'com.openlattice.chronicle.collection.device.HealthMetricModuleHolder' \
  'com.openlattice.chronicle.collection.activity.ActivityRecognitionModuleHolder' \
  'com.openlattice.chronicle.collection.activity.SleepModuleHolder' \
  'com.openlattice.chronicle.collection.directboot.DirectBootDrainWorker' \
  'com.openlattice.chronicle.collection.directboot.DirectBootSampleBuffer' \
  'com.openlattice.chronicle.collection.interaction.InteractionCollectionService' \
  'com.openlattice.chronicle.collection.interaction.InteractionUploadWorker' \
  'com.openlattice.chronicle.services.notifications.NotificationListener' \
  'com.openlattice.chronicle.services.sensors.HardwareSensorService' \
  'com.openlattice.chronicle.services.sensors.SensorSettingsRefreshWorker' \
  'com.openlattice.chronicle.services.sensors.SensorUploadWorker' \
  'com.openlattice.chronicle.services.sensors.SensorUploadWorkerDelegate' \
  'com.openlattice.chronicle.storage.AudioActivitySampleDao_Impl' \
  'com.openlattice.chronicle.storage.AudioContentSampleDao_Impl' \
  'com.openlattice.chronicle.storage.NotificationActivitySampleDao_Impl' \
  'com.openlattice.chronicle.storage.InteractionSampleDao_Impl' \
  'com.openlattice.chronicle.storage.SleepSampleDao_Impl' \
  'com.openlattice.chronicle.storage.ActivityRecognitionSampleDao_Impl' \
  'com.openlattice.chronicle.storage.HealthMetricSampleDao_Impl' \
  'com.openlattice.chronicle.storage.SensorSampleEntry'; do
  if rg -q "^${forbidden_original_class//./\\.} -> " "$embedded_mapping"; then
    printf 'ERROR: excluded Play implementation survived R8 under an obfuscated name: %s\n' \
      "$forbidden_original_class" >&2
    exit 1
  fi
done
if rg -q 'applyReviewedHealthConnectAcceptance' "$embedded_mapping"; then
  printf 'ERROR: excluded Health Connect consent implementation survived R8.\n' >&2
  exit 1
fi
if rg -q 'confirmAccessibilityDisclosure|showInteractionAccessibilityDisclosure' "$embedded_mapping"; then
  printf 'ERROR: excluded accessibility disclosure implementation survived R8.\n' >&2
  exit 1
fi
gitleaks dir "$unpack_dir" \
  --no-banner \
  --redact \
  --report-format sarif \
  --report-path "$OUTPUT_DIR/gitleaks.sarif"

if rg -a -q 'TestHookController|/datastore/' "$unpack_dir"; then
  printf 'ERROR: internal test/datastore route marker is present in the bundle.\n' >&2
  exit 1
fi

# These markers are forbidden unconditionally. BuildConfig flags are not a packaging boundary
# when release minification/resource shrinking is disabled, so inspect the complete extracted
# bundle rather than trusting an unreachable runtime branch.
for forbidden_release_marker in \
  'chronicle-testprod' \
  'AWS testprod' \
  'openlattice.com/chronicle/login' \
  'HealthConnectRationaleActivity' \
  'Review Health Connect access' \
  'This study will read only these approved Health Connect record types:' \
  'Health Connect scope. Review the updated scope before accepting.' \
  'Share Health Connect data?' \
  'This study requests read-only access to:' \
  'This study reads health and fitness summaries' \
  'Audio Status' \
  'Audio Metadata' \
  'Allow audio media-session access?' \
  'Allow interaction event access?' \
  'Chronicle is not an accessibility tool.' \
  'Which app is playing audio and whether audio is playing' \
  'The title, artist, and album of media you play' \
  '/android/audio-activity' \
  '/android/audio-content' \
  '/android/interaction' \
  '/android/notification-activity' \
  '/android/sleep' \
  '/android/activity-recognition' \
  '/android/health-connect' \
  '/android/app-network-usage' \
  'App Network Usage' \
  'per-app network byte counts' \
  'Withdraw from study' \
  'Withdrawal information' \
  'withdrawal-and-erasure request' \
  '/Users/u/' \
  '/home/opt/'; do
  if rg -a -Fq "$forbidden_release_marker" "$unpack_dir"; then
    printf 'ERROR: operator-specific or private release marker is present in the bundle: %s\n' \
      "$forbidden_release_marker" >&2
    exit 1
  fi
done

if [[ -n "$FORBIDDEN_LITERALS_FILE" ]]; then
  [[ -f "$FORBIDDEN_LITERALS_FILE" ]] || {
    printf 'ERROR: forbidden-literals file does not exist.\n' >&2
    exit 2
  }
  literal_number=0
  while IFS= read -r literal || [[ -n "$literal" ]]; do
    literal_number=$((literal_number + 1))
    [[ -n "$literal" ]] || continue
    if rg -a -Fq "$literal" "$unpack_dir"; then
      printf 'ERROR: forbidden private literal number %s is present in the bundle.\n' "$literal_number" >&2
      exit 1
    fi
  done <"$FORBIDDEN_LITERALS_FILE"
fi

run_runtime_egress_proof() {
  local origin="$1"
  local serial="$2"
  local package_name="$3"
  local duration="$4"
  local origin_metadata="$OUTPUT_DIR/runtime-egress-origin.txt"
  local ipv4_file="$OUTPUT_DIR/runtime-egress-ipv4.txt"
  local ipv6_file="$OUTPUT_DIR/runtime-egress-ipv6.txt"
  local origin_port package_uid package_uid_status chain_name launch_component
  local ipv4_rules=0 ipv6_rules=0
  local ipv4_accept ipv4_reject ipv6_accept ipv6_reject
  local egress_result runtime_receipt
  local ui_dump_remote="/data/local/tmp/chronicle-egress-origin.xml"
  local ui_dump_local="$OUTPUT_DIR/runtime-egress-origin-ui.xml"
  local nav_coordinates nav_x nav_y ui_action ui_action_kind enrollment_origin_proven=0

  EGRESS_SERIAL="$serial"
  EGRESS_UI_DUMP_LOCAL="$ui_dump_local"
  EGRESS_UI_DUMP_REMOTE="$ui_dump_remote"
  trap 'cleanup_runtime_egress; exit 129' HUP
  trap 'cleanup_runtime_egress; exit 130' INT
  trap 'cleanup_runtime_egress; exit 143' TERM
  trap cleanup_runtime_egress EXIT

  validate_runtime_ip_origin "$origin" "$origin_metadata" "$ipv4_file" "$ipv6_file"
  origin_port="$(sed -n 's/^port=//p' "$origin_metadata")"

  # Bind the operator-supplied endpoint to the active enrollment through the app's own decrypted
  # Settings UI. The release database is SQLCipher-encrypted, so this avoids adding an exported
  # diagnostic component or trying to read private state out-of-process.
  adb -s "$serial" shell am force-stop "$package_name"
  launch_component="$(
    adb -s "$serial" shell cmd package resolve-activity --brief \
      -a android.intent.action.MAIN \
      -c android.intent.category.LAUNCHER \
      "$package_name" \
      | tr -d '\r' \
      | tail -n 1
  )"
  [[ "$launch_component" == "$package_name/"* ]] || {
    printf 'ERROR: could not resolve Chronicle launcher activity for the egress proof.\n' >&2
    return 1
  }
  adb -s "$serial" shell am start -W -n "$launch_component" \
    >"$OUTPUT_DIR/runtime-egress-enrollment-launch.txt" 2>&1
  sleep 2
  nav_coordinates=""
  for _attempt in $(seq 1 15); do
    adb -s "$serial" shell uiautomator dump "$ui_dump_remote" >/dev/null || continue
    adb -s "$serial" pull "$ui_dump_remote" "$ui_dump_local" >/dev/null || continue
    ui_action="$(python3 - "$ui_dump_local" "$package_name:id/nav_settings" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
def center(node):
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
    if not match:
        raise SystemExit(1)
    left, top, right, bottom = map(int, match.groups())
    if right <= left or bottom <= top:
        raise SystemExit(1)
    return (left + right) // 2, (top + bottom) // 2

nav = [node for node in root.iter("node") if node.attrib.get("resource-id") == sys.argv[2]]
if len(nav) == 1:
    x, y = center(nav[0])
    print(f"NAV {x} {y}")
    raise SystemExit(0)

# First-run battery/hibernation education can cover the dashboard. Dismiss only explicit
# non-consent deferral controls; never accept a permission or system-setting change here.
dismiss = [
    node
    for node in root.iter("node")
    if node.attrib.get("resource-id") == "android:id/button2"
    and node.attrib.get("text", "").strip().upper() in {"CANCEL", "NOT NOW", "LATER"}
]
if len(dismiss) == 1:
    x, y = center(dismiss[0])
    print(f"DISMISS {x} {y}")
    raise SystemExit(0)
raise SystemExit(1)
PY
)" || true
    read -r ui_action_kind nav_x nav_y <<<"$ui_action"
    if [[ "$ui_action_kind" == "NAV" ]]; then
      nav_coordinates="$nav_x $nav_y"
      break
    fi
    if [[ "$ui_action_kind" == "DISMISS" ]]; then
      adb -s "$serial" shell input tap "$nav_x" "$nav_y"
      sleep 1
    fi
  done
  [[ -n "$nav_coordinates" ]] || {
    rm -f -- "$ui_dump_local"
    printf 'ERROR: could not navigate to Chronicle Settings to bind the enrolled origin.\n' >&2
    return 1
  }
  read -r nav_x nav_y <<<"$nav_coordinates"
  adb -s "$serial" shell input tap "$nav_x" "$nav_y"
  for _attempt in $(seq 1 15); do
    sleep 1
    adb -s "$serial" shell uiautomator dump "$ui_dump_remote" >/dev/null || continue
    adb -s "$serial" pull "$ui_dump_remote" "$ui_dump_local" >/dev/null || continue
    if active_enrollment_summary_matches "$ui_dump_local" "$origin"
    then
      enrollment_origin_proven=1
      break
    fi
  done
  remove_runtime_ui_dump || {
    printf 'ERROR: temporary runtime enrollment UI-dump deletion could not be proven.\n' >&2
    return 1
  }
  [[ "$enrollment_origin_proven" -eq 1 ]] || {
    printf 'ERROR: the supplied HTTPS IP origin is not the one active enrollment shown in Settings.\n' >&2
    return 1
  }
  printf 'active_enrollment_origin=%s\nsource=decrypted_settings_exact_origin_plus_endpoint_traffic\n' \
    "${origin%/}" >"$OUTPUT_DIR/runtime-egress-enrollment-origin-proof.txt"

  adb -s "$serial" root >"$OUTPUT_DIR/adb-root.txt" 2>&1 || true
  adb -s "$serial" wait-for-device
  if [[ "$(adb -s "$serial" shell id -u | tr -d '\r')" != "0" ]]; then
    printf 'ERROR: runtime egress proof requires a rootable disposable emulator.\n' >&2
    return 1
  fi
  {
    printf 'serial=%s\n' "$serial"
    printf 'sdk=%s\n' "$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
    printf 'fingerprint=%s\n' "$(adb -s "$serial" shell getprop ro.build.fingerprint | tr -d '\r')"
  } >"$OUTPUT_DIR/runtime-egress-device.txt"
  for firewall_tool in iptables ip6tables; do
    adb -s "$serial" shell "command -v $firewall_tool" >/dev/null || {
      printf 'ERROR: runtime egress proof requires %s on the disposable emulator.\n' \
        "$firewall_tool" >&2
      return 1
    }
  done

  adb -s "$serial" shell cmd package list packages -U \
    | tr -d '\r' >"$OUTPUT_DIR/runtime-egress-package-uids.txt"
  package_uid_status=0
  package_uid="$(resolve_exact_unshared_package_uid \
    "$OUTPUT_DIR/runtime-egress-package-uids.txt" "$package_name")" || package_uid_status=$?
  case "$package_uid_status" in
    0) ;;
    2)
      printf 'ERROR: Chronicle package UID is shared or ambiguous; refusing egress attribution.\n' >&2
      return 1 ;;
    *)
      printf 'ERROR: could not uniquely resolve the exact installed Chronicle package UID.\n' >&2
      return 1 ;;
  esac
  chain_name="CHRPL${package_uid}_$$"
  if (( ${#chain_name} > 28 )); then
    printf 'ERROR: generated runtime egress firewall chain name is too long.\n' >&2
    return 1
  fi

  EGRESS_PACKAGE_UID="$package_uid"
  EGRESS_CHAIN_NAME="$chain_name"
  EGRESS_CLEANUP_NEEDED=1
  EGRESS_CLEANUP_FAILED=0
  adb -s "$serial" shell "iptables -w 5 -t filter -N $chain_name"
  adb -s "$serial" shell "ip6tables -w 5 -t filter -N $chain_name"
  while IFS= read -r address; do
    [[ -n "$address" ]] || continue
    adb -s "$serial" shell \
      "iptables -w 5 -t filter -A $chain_name -p tcp -d $address --dport $origin_port -j ACCEPT"
    ipv4_rules=$((ipv4_rules + 1))
  done <"$ipv4_file"
  while IFS= read -r address; do
    [[ -n "$address" ]] || continue
    adb -s "$serial" shell \
      "ip6tables -w 5 -t filter -A $chain_name -p tcp -d $address --dport $origin_port -j ACCEPT"
    ipv6_rules=$((ipv6_rules + 1))
  done <"$ipv6_file"
  (( ipv4_rules + ipv6_rules > 0 )) || {
    printf 'ERROR: runtime egress origin produced no firewall rules.\n' >&2
    return 1
  }
  adb -s "$serial" shell "iptables -w 5 -t filter -A $chain_name -j REJECT"
  adb -s "$serial" shell "ip6tables -w 5 -t filter -A $chain_name -j REJECT"
  adb -s "$serial" shell \
    "iptables -w 5 -t filter -I OUTPUT 1 -m owner --uid-owner $package_uid -j $chain_name"
  adb -s "$serial" shell \
    "ip6tables -w 5 -t filter -I OUTPUT 1 -m owner --uid-owner $package_uid -j $chain_name"

  adb -s "$serial" shell am force-stop "$package_name"
  adb -s "$serial" shell am start -W -n "$launch_component" \
    >"$OUTPUT_DIR/runtime-egress-launch.txt" 2>&1
  sleep "$duration"
  # Stop the app while the deny rule is still in force, then take the final counters. No
  # unobserved Chronicle request can race between the snapshot and firewall cleanup.
  adb -s "$serial" shell am force-stop "$package_name"
  adb -s "$serial" shell "iptables -w 5 -t filter -L $chain_name -nvx" \
    | tr -d '\r' >"$OUTPUT_DIR/runtime-egress-ipv4-counters.txt"
  adb -s "$serial" shell "ip6tables -w 5 -t filter -L $chain_name -nvx" \
    | tr -d '\r' >"$OUTPUT_DIR/runtime-egress-ipv6-counters.txt"

  ipv4_accept="$(awk '$3 == "ACCEPT" { total += $1 } END { print total + 0 }' \
    "$OUTPUT_DIR/runtime-egress-ipv4-counters.txt")"
  ipv4_reject="$(awk '$3 == "REJECT" { total += $1 } END { print total + 0 }' \
    "$OUTPUT_DIR/runtime-egress-ipv4-counters.txt")"
  ipv6_accept="$(awk '$3 == "ACCEPT" { total += $1 } END { print total + 0 }' \
    "$OUTPUT_DIR/runtime-egress-ipv6-counters.txt")"
  ipv6_reject="$(awk '$3 == "REJECT" { total += $1 } END { print total + 0 }' \
    "$OUTPUT_DIR/runtime-egress-ipv6-counters.txt")"
  {
    printf 'package_uid=%s\n' "$package_uid"
    printf 'observation_seconds=%s\n' "$duration"
    printf 'allowed_packets=%s\n' "$((ipv4_accept + ipv6_accept))"
    printf 'blocked_packets=%s\n' "$((ipv4_reject + ipv6_reject))"
  } >"$OUTPUT_DIR/runtime-egress-summary.txt"

  cleanup_runtime_egress
  if [[ "$EGRESS_CLEANUP_FAILED" -ne 0 ]]; then
    printf 'ERROR: runtime egress firewall cleanup could not be proven.\n' >&2
    return 1
  fi
  trap - EXIT HUP INT TERM
  egress_result="passed"
  if (( ipv4_reject + ipv6_reject != 0 || ipv4_accept + ipv6_accept == 0 )); then
    egress_result="failed"
  fi
  runtime_receipt="$OUTPUT_DIR/runtime-egress-receipt.json"
  python3 - \
    "$runtime_receipt" \
    "$origin_metadata" \
    "$OUTPUT_DIR/app-play-release.aab.sha256" \
    "$OUTPUT_DIR/built-split-payload-identities.txt" \
    "$OUTPUT_DIR/installed-split-payload-identities.txt" \
    "$OUTPUT_DIR/signer-certificate.sha256" \
    "$OUTPUT_DIR/installed-signer-certificate.sha256" \
    "$OUTPUT_DIR/installed-verification-mode.txt" \
    "$OUTPUT_DIR/approved-module-registry.sha256" \
    "$OUTPUT_DIR/mapping.txt.sha256" \
    "$OUTPUT_DIR/runtime-egress-device.txt" \
    "$OUTPUT_DIR/release-candidate-id.txt" \
    "$ROOT_DIR/scripts/verify-play-aab.sh" \
    "$BUNDLETOOL_JAR" \
    "$OUTPUT_DIR/bundletool-version.txt" \
    "$OUTPUT_DIR/bundletool-device-spec.json" \
    "$package_name" \
    "$package_uid" \
    "$duration" \
    "$((ipv4_accept + ipv6_accept))" \
    "$((ipv4_reject + ipv6_reject))" \
    "$egress_result" <<'PY'
import hashlib
import json
from pathlib import Path
import sys

(
    receipt_path,
    origin_path,
    aab_hash_path,
    built_manifest_path,
    installed_manifest_path,
    aab_signer_path,
    installed_signer_path,
    installation_mode_path,
    registry_hash_path,
    mapping_hash_path,
    device_path,
    rc_path,
    verifier_path,
    bundletool_path,
    bundletool_version_path,
    bundletool_device_spec_path,
    package_name,
    package_uid,
    duration,
    allowed_packets,
    blocked_packets,
    result,
) = sys.argv[1:]

origin = dict(
    line.split("=", 1)
    for line in Path(origin_path).read_text(encoding="utf-8").splitlines()
    if "=" in line
)
device = dict(
    line.split("=", 1)
    for line in Path(device_path).read_text(encoding="utf-8").splitlines()
    if "=" in line
)

def first_token(path: str) -> str:
    return Path(path).read_text(encoding="utf-8").split()[0]

def last_token(path: str) -> str:
    return Path(path).read_text(encoding="utf-8").split()[-1]

def file_sha256(path: str) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

built_manifest_sha256 = file_sha256(built_manifest_path)
installed_manifest_sha256 = file_sha256(installed_manifest_path)
if built_manifest_sha256 != installed_manifest_sha256:
    raise SystemExit("built and installed split manifests diverged before receipt creation")

receipt = {
    "schemaVersion": 2,
    "result": result,
    "package": package_name,
    "packageUid": int(package_uid),
    "releaseCandidateId": Path(rc_path).read_text(encoding="utf-8").strip(),
    "verifierScriptSha256": file_sha256(verifier_path),
    "aabSha256": first_token(aab_hash_path),
    "aabUploadSignerCertificateSha256": last_token(aab_signer_path),
    "installedSignerCertificateSha256": last_token(installed_signer_path),
    "approvedModuleRegistrySha256": first_token(registry_hash_path),
    "mappingSha256": first_token(mapping_hash_path),
    "bundletoolJarSha256": file_sha256(bundletool_path),
    "bundletoolVersion": Path(bundletool_version_path).read_text(encoding="utf-8").strip(),
    "bundletoolDeviceSpecSha256": file_sha256(bundletool_device_spec_path),
    "installedSplitManifestSha256": installed_manifest_sha256,
    "installationMode": Path(installation_mode_path).read_text(encoding="utf-8").strip().split("=", 1)[1],
    "allowedOrigin": origin["origin"],
    "allowedHost": origin["host"],
    "allowedPort": int(origin["port"]),
    "observationSeconds": int(duration),
    "allowedPackets": int(allowed_packets),
    "blockedPackets": int(blocked_packets),
    "deviceSerial": device["serial"],
    "deviceSdk": int(device["sdk"]),
    "deviceBuildFingerprint": device["fingerprint"],
    "ipLiteralOriginRequired": True,
    "activeEnrollmentExactOriginUiProven": True,
    "activeEnrollmentHealthyUiProven": True,
    "temporaryUiDumpCleanupProven": True,
    "allowedOriginEndpointTrafficProven": True,
    "ipv4AndIpv6Enforced": True,
    "appForceStoppedBeforeFinalCounters": True,
    "firewallCleanupProven": True,
    "recordedAtUtc": __import__("datetime").datetime.now(__import__("datetime").timezone.utc).isoformat(),
}
Path(receipt_path).write_text(
    json.dumps(receipt, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
PY
  chmod 600 "$runtime_receipt"
  if (( ipv4_reject + ipv6_reject != 0 )); then
    printf 'ERROR: Chronicle attempted traffic outside the enrolled HTTPS origin.\n' >&2
    return 1
  fi
  if (( ipv4_accept + ipv6_accept == 0 )); then
    printf 'ERROR: no Chronicle traffic reached the enrolled HTTPS origin during the proof window.\n' >&2
    return 1
  fi
}

if [[ -n "$BUNDLETOOL_JAR" ]]; then
  adb -s "$DEVICE_SERIAL" get-state >/dev/null
  java -jar "$BUNDLETOOL_JAR" version \
    >"$OUTPUT_DIR/bundletool-version.txt"
  java -jar "$BUNDLETOOL_JAR" get-device-spec \
    --device-id="$DEVICE_SERIAL" \
    --output="$OUTPUT_DIR/bundletool-device-spec.json" \
    >"$OUTPUT_DIR/bundletool-device-spec.txt" 2>&1
  [[ -s "$OUTPUT_DIR/bundletool-version.txt" &&
     -s "$OUTPUT_DIR/bundletool-device-spec.json" ]] || {
    printf 'ERROR: bundletool identity or device specification evidence is incomplete.\n' >&2
    exit 1
  }
  installed_before="$OUTPUT_DIR/installed-package-before.txt"
  installed_after="$OUTPUT_DIR/installed-package-after.txt"
  adb -s "$DEVICE_SERIAL" shell pm path "$PACKAGE_NAME" 2>/dev/null \
    | tr -d '\r' >"$installed_before" || true
  if [[ "$VERIFY_INSTALLED" -eq 1 ]]; then
    if [[ ! -s "$installed_before" ]]; then
      printf 'ERROR: --verify-installed requires %s to already be installed.\n' \
        "$PACKAGE_NAME" >&2
      exit 1
    fi
    if [[ "$VERIFY_PLAY_DELIVERED" -eq 1 ]]; then
      printf 'installed_verification_mode=play_delivered_payload_identity_no_replacement\n' \
        >"$OUTPUT_DIR/installed-verification-mode.txt"
    else
      printf 'installed_verification_mode=existing_exact_aab_derived_splits_no_replacement\n' \
        >"$OUTPUT_DIR/installed-verification-mode.txt"
    fi
  else
    if [[ -s "$installed_before" ]]; then
      printf 'ERROR: package replacement is forbidden during exact-artifact verification: %s is already installed.\n' \
        "$PACKAGE_NAME" >&2
      exit 1
    fi
    printf 'installed_verification_mode=clean_install\n' \
      >"$OUTPUT_DIR/installed-verification-mode.txt"
  fi

  apks_path="$OUTPUT_DIR/app-play-release.apks"
  java -jar "$BUNDLETOOL_JAR" build-apks \
    --bundle="$AAB_PATH" \
    --output="$apks_path" \
    --connected-device \
    --device-id="$DEVICE_SERIAL" \
    --overwrite \
    >"$OUTPUT_DIR/bundletool-build-apks.txt" 2>&1
  built_splits="$OUTPUT_DIR/built-splits"
  mkdir -p "$built_splits"
  unzip -q "$apks_path" -d "$built_splits"
  built_count="$(find "$built_splits" -type f -name '*.apk' | wc -l | tr -d ' ')"
  [[ "$built_count" -gt 0 ]] || {
    printf 'ERROR: bundletool produced no device-targeted APK splits.\n' >&2
    exit 1
  }
  find "$built_splits" -type f -name '*.apk' -print0 \
    | LC_ALL=C sort -z \
    | xargs -0 shasum -a 256 \
    >"$OUTPUT_DIR/built-split-hashes.sha256"

  if [[ "$VERIFY_INSTALLED" -ne 1 ]]; then
    java -jar "$BUNDLETOOL_JAR" install-apks \
      --apks="$apks_path" \
      --device-id="$DEVICE_SERIAL" \
      >"$OUTPUT_DIR/bundletool-install-apks.txt" 2>&1
  fi
  adb -s "$DEVICE_SERIAL" shell pm path "$PACKAGE_NAME" \
    | tr -d '\r' >"$installed_after"
  [[ -s "$installed_after" ]] || {
    printf 'ERROR: bundletool reported success but the package has no installed split paths.\n' >&2
    exit 1
  }

  pulled_splits="$OUTPUT_DIR/installed-splits"
  mkdir -p "$pulled_splits"
  while IFS= read -r package_path; do
    package_path="${package_path#package:}"
    [[ -n "$package_path" ]] || continue
    split_name="$(basename "$package_path")"
    [[ ! -e "$pulled_splits/$split_name" ]] || {
      printf 'ERROR: installed split basenames collide: %s\n' "$split_name" >&2
      exit 1
    }
    adb -s "$DEVICE_SERIAL" pull "$package_path" "$pulled_splits/$split_name" \
      >>"$OUTPUT_DIR/adb-pull-splits.txt" 2>&1
  done <"$installed_after"
  find "$pulled_splits" -type f -name '*.apk' -print0 \
    | LC_ALL=C sort -z \
    | xargs -0 shasum -a 256 \
    >"$OUTPUT_DIR/split-hashes.sha256"
  installed_count="$(find "$pulled_splits" -type f -name '*.apk' | wc -l | tr -d ' ')"
  [[ "$built_count" == "$installed_count" ]] || {
    printf 'ERROR: installed split count differs from the exact bundletool output.\n' >&2
    exit 1
  }
  apksigner_command="${APKSIGNER:-}"
  if [[ -z "$apksigner_command" ]]; then
    apksigner_command="$(command -v apksigner 2>/dev/null || true)"
  fi
  if [[ -z "$apksigner_command" && -n "${ANDROID_HOME:-}" ]]; then
    apksigner_command="$(
      find "$ANDROID_HOME/build-tools" -type f -name apksigner -perm -111 2>/dev/null \
        | LC_ALL=C sort \
        | tail -n 1
    )"
  fi
  [[ -x "$apksigner_command" ]] || {
    printf 'ERROR: split verification requires apksigner (set APKSIGNER or ANDROID_HOME).\n' >&2
    exit 2
  }
  : >"$OUTPUT_DIR/installed-split-signers.txt"
  while IFS= read -r installed_apk; do
    if ! installed_digest="$(
      extract_single_current_apk_signer "$apksigner_command" "$installed_apk"
    )"; then
      printf 'ERROR: installed split must have exactly one valid current APK signer: %s\n' \
        "$(basename "$installed_apk")" >&2
      exit 1
    fi
    printf '%s  %s\n' "$installed_digest" "$(basename "$installed_apk")" \
      >>"$OUTPUT_DIR/installed-split-signers.txt"
  done < <(find "$pulled_splits" -type f -name '*.apk' | LC_ALL=C sort)
  installed_signer_count="$(awk '{ print $1 }' "$OUTPUT_DIR/installed-split-signers.txt" \
    | LC_ALL=C sort -u | wc -l | tr -d ' ')"
  [[ "$installed_signer_count" -eq 1 ]] || {
    printf 'ERROR: installed split set is not signed by exactly one certificate.\n' >&2
    exit 1
  }
  installed_cert_sha256="$(awk 'NR == 1 { print $1 }' \
    "$OUTPUT_DIR/installed-split-signers.txt")"
  printf 'SHA256 %s\n' "$installed_cert_sha256" \
    >"$OUTPUT_DIR/installed-signer-certificate.sha256"
  if [[ "$VERIFY_PLAY_DELIVERED" -eq 1 &&
        "$installed_cert_sha256" != "$EXPECTED_INSTALLED_CERT_SHA256" ]]; then
    printf 'ERROR: installed Play split signer does not match the pinned Play app-signing certificate.\n' >&2
    exit 1
  fi

  for split_kind in built installed; do
    if [[ "$split_kind" == "built" ]]; then
      split_root="$built_splits"
    else
      split_root="$pulled_splits"
    fi
    write_apk_payload_identities \
      "$split_root" "$OUTPUT_DIR/${split_kind}-split-payload-identities.txt"
  done
  cmp -s \
    "$OUTPUT_DIR/built-split-payload-identities.txt" \
    "$OUTPUT_DIR/installed-split-payload-identities.txt" || {
    printf 'ERROR: installed split payloads differ from the device-targeted AAB payloads.\n' >&2
    exit 1
  }
  awk '{print $1}' "$OUTPUT_DIR/built-split-hashes.sha256" | LC_ALL=C sort \
    >"$OUTPUT_DIR/built-split-content-hashes.txt"
  awk '{print $1}' "$OUTPUT_DIR/split-hashes.sha256" | LC_ALL=C sort \
    >"$OUTPUT_DIR/installed-split-content-hashes.txt"
  if [[ "$VERIFY_PLAY_DELIVERED" -ne 1 ]]; then
    cmp -s \
      "$OUTPUT_DIR/built-split-content-hashes.txt" \
      "$OUTPUT_DIR/installed-split-content-hashes.txt" || {
      printf 'ERROR: installed local split bytes differ from the exact bundletool output.\n' >&2
      exit 1
    }
  fi
  if [[ -n "$RUNTIME_EGRESS_ORIGIN" ]]; then
    run_runtime_egress_proof \
      "$RUNTIME_EGRESS_ORIGIN" \
      "$DEVICE_SERIAL" \
      "$PACKAGE_NAME" \
      "$RUNTIME_EGRESS_SECONDS"
  fi
fi

if [[ "$SEALED_SUBMISSION" -eq 1 ]]; then
  verify_sealed_source_checkout "$ROOT_DIR" "$SKIP_BUILD" "$verification_phase" || {
    printf 'ERROR: sealed source checkout changed during verification.\n' >&2
    exit 1
  }
fi

verification_receipt="$OUTPUT_DIR/play-aab-verification-receipt.json"
python3 - \
  "$verification_receipt" \
  "$OUTPUT_DIR/app-play-release.aab.sha256" \
  "$OUTPUT_DIR/signer-certificate.sha256" \
  "$OUTPUT_DIR/approved-module-registry.sha256" \
  "$OUTPUT_DIR/mapping.txt.sha256" \
  "$OUTPUT_DIR/release-candidate-id.txt" \
  "$ROOT_DIR/scripts/verify-play-aab.sh" \
  "$OUTPUT_DIR/play-submission-sources.sha256" \
  "$OUTPUT_DIR/play-submission.properties" \
  "$OUTPUT_DIR/play-submission-mode.txt" \
  "$OUTPUT_DIR/installed-verification-mode.txt" \
  "$OUTPUT_DIR/installed-signer-certificate.sha256" \
  "$OUTPUT_DIR/installed-split-payload-identities.txt" \
  "$verification_phase" \
  "$PRIOR_SEALED_RECEIPT" \
  "$RELEASE_AUTHORITY_SHA" \
  "$OUTPUT_DIR/prior-sealed-receipt-attestation.json" \
  "$BUNDLETOOL_JAR" \
  "$OUTPUT_DIR/bundletool-version.txt" \
  "$OUTPUT_DIR/bundletool-device-spec.json" \
  "$policy_version_code" \
  "$policy_version_name" \
  "$ROOT_DIR" <<'PY'
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import sys

(
    receipt_path,
    aab_hash_path,
    signer_path,
    registry_hash_path,
    mapping_hash_path,
    rc_path,
    verifier_path,
    submission_sources_path,
    submission_policy_path,
    submission_mode_path,
    installation_mode_path,
    installed_signer_path,
    installed_split_manifest_path,
    verification_phase,
    prior_sealed_receipt_path,
    release_authority_sha,
    prior_attestation_verification_path,
    bundletool_path,
    bundletool_version_path,
    bundletool_device_spec_path,
    version_code,
    version_name,
    root_path,
) = sys.argv[1:]

root = Path(root_path)

def first_token(path: str) -> str:
    return Path(path).read_text(encoding="utf-8").split()[0]

def last_token(path: str) -> str:
    return Path(path).read_text(encoding="utf-8").split()[-1]

def file_sha256(path: str) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def optional_file_sha256(path: str):
    return file_sha256(path) if path and Path(path).is_file() else None

def git_output(*args: str) -> str:
    return subprocess.check_output(
        ["git", "-C", str(root), *args],
        text=True,
    ).strip()

def source_digests(path: str) -> dict[str, str]:
    result = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        digest, relative = line.split(maxsplit=1)
        result[relative.strip()] = digest
    return result

tracked_clean = (
    subprocess.run(["git", "-C", str(root), "diff", "--quiet"], check=False).returncode == 0
    and subprocess.run(
        ["git", "-C", str(root), "diff", "--cached", "--quiet"],
        check=False,
    ).returncode == 0
)
source_checkout_clean = subprocess.check_output(
    [
        "git",
        "-C",
        str(root),
        "status",
        "--porcelain=v1",
        "--untracked-files=all",
        "--ignore-submodules=none",
    ],
    text=True,
).strip() == ""
detached = subprocess.run(
    ["git", "-C", str(root), "symbolic-ref", "-q", "HEAD"],
    stdout=subprocess.DEVNULL,
    stderr=subprocess.DEVNULL,
    check=False,
).returncode != 0
submission_mode = Path(submission_mode_path).read_text(encoding="utf-8").strip()
if submission_mode == "sealed_owner_approved" and (not source_checkout_clean or not detached):
    raise SystemExit("sealed verification receipt requires clean detached source")

installation_mode_file = Path(installation_mode_path)
installed_signer_file = Path(installed_signer_path)
installed_split_manifest_file = Path(installed_split_manifest_path)
installation_mode = "not_installed"
installed_signer = None
installed_split_manifest_sha256 = None
if installation_mode_file.is_file():
    installation_mode = installation_mode_file.read_text(encoding="utf-8").strip().split("=", 1)[-1]
    if not installed_signer_file.is_file() or not installed_split_manifest_file.is_file():
        raise SystemExit("installed verification evidence is incomplete")
    installed_signer = last_token(str(installed_signer_file))
    installed_split_manifest_sha256 = file_sha256(str(installed_split_manifest_file))
if verification_phase == "play_delivery":
    if installation_mode != "play_delivered_payload_identity_no_replacement":
        raise SystemExit("Play-delivery receipt requires Play-delivered installed verification")
    if not prior_sealed_receipt_path or installed_signer is None:
        raise SystemExit("Play-delivery receipt is missing its sealed parent or installed signer")
    if not Path(prior_attestation_verification_path).is_file():
        raise SystemExit("Play-delivery receipt is missing verified parent attestation evidence")
elif prior_sealed_receipt_path:
    raise SystemExit("only Play-delivery verification may consume a prior sealed receipt")

receipt = {
    "schemaVersion": 3,
    "result": "passed",
    "package": "com.bcm.chronicle",
    "versionCode": int(version_code),
    "versionName": version_name,
    "releaseCandidateId": Path(rc_path).read_text(encoding="utf-8").strip(),
    "aabSha256": first_token(aab_hash_path),
    "aabUploadSignerCertificateSha256": last_token(signer_path),
    "installedSignerCertificateSha256": installed_signer,
    "installedSplitPayloadManifestSha256": installed_split_manifest_sha256,
    "installationMode": installation_mode,
    "bundletoolJarSha256": optional_file_sha256(bundletool_path),
    "bundletoolVersion": (
        Path(bundletool_version_path).read_text(encoding="utf-8").strip()
        if Path(bundletool_version_path).is_file()
        else None
    ),
    "bundletoolDeviceSpecSha256": optional_file_sha256(bundletool_device_spec_path),
    "approvedModuleRegistrySha256": first_token(registry_hash_path),
    "mappingSha256": first_token(mapping_hash_path),
    "verifierScriptSha256": file_sha256(verifier_path),
    "playSubmissionSourcesSha256": file_sha256(submission_sources_path),
    "playSubmissionSourceDigests": source_digests(submission_sources_path),
    "playSubmissionPolicySha256": file_sha256(submission_policy_path),
    "submissionMode": submission_mode,
    "verificationPhase": verification_phase,
    "releaseAuthorityRepository": "uzaira0/methodic" if release_authority_sha else None,
    "releaseAuthorityCommit": release_authority_sha or None,
    "priorSealedReceiptSha256": (
        file_sha256(prior_sealed_receipt_path) if prior_sealed_receipt_path else None
    ),
    "priorSealedReceiptAttestationVerificationSha256": (
        file_sha256(prior_attestation_verification_path)
        if Path(prior_attestation_verification_path).is_file()
        else None
    ),
    "sourceCommit": git_output("rev-parse", "HEAD"),
    "sourceTree": git_output("rev-parse", "HEAD^{tree}"),
    "trackedSourceClean": tracked_clean,
    "sourceCheckoutClean": source_checkout_clean,
    "detachedSource": detached,
    "recordedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
}
Path(receipt_path).write_text(
    json.dumps(receipt, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
PY
chmod 600 "$verification_receipt"

printf 'Play AAB verification passed. Evidence: %s\n' "$OUTPUT_DIR"
