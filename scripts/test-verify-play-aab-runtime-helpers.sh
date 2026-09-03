#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFIER="$SCRIPT_DIR/verify-play-aab.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/chronicle-play-verifier.XXXXXX")"
trap 'rm -rf -- "$TEST_ROOT"' EXIT HUP INT TERM

CHRONICLE_PLAY_AAB_HELPERS_ONLY=1
export CHRONICLE_PLAY_AAB_HELPERS_ONLY
# Path is resolved from this script's canonical directory.
# shellcheck disable=SC1090
source "$VERIFIER"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

if CHRONICLE_PLAY_AAB_HELPERS_ONLY=1 \
  bash "$VERIFIER" >"$TEST_ROOT/direct-helper.out" 2>&1; then
  fail "helpers-only environment bypassed direct verifier execution"
fi
grep -Fq 'valid only when sourcing the verifier' "$TEST_ROOT/direct-helper.out" ||
  fail "direct helpers-only rejection was not explicit"

GH_ATTESTATION_COMMAND="$TEST_ROOT/caller-controlled-fake-gh"
export GH_ATTESTATION_COMMAND
[[ "$(resolve_gh_attestation_command)" == gh ]] ||
  fail "ambient GH_ATTESTATION_COMMAND replaced the production GitHub CLI"
unset GH_ATTESTATION_COMMAND

cat >"$TEST_ROOT/manifest.txt" <<'MANIFEST'
2: "versionCode"
3: "72"
2: "versionName"
3: "2026.08-owner.1"
MANIFEST
require_compiled_manifest_pair "$TEST_ROOT/manifest.txt" versionCode 72 ||
  fail "owner-selected versionCode was not accepted"
require_compiled_manifest_pair "$TEST_ROOT/manifest.txt" versionName '2026.08-owner.1' ||
  fail "owner-selected versionName was not accepted"
if require_compiled_manifest_pair "$TEST_ROOT/manifest.txt" versionCode 54; then
  fail "stale hard-coded versionCode was accepted"
fi

approved_play_signer='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
verify_play_delivered_policy_signer \
  'AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA' \
  "$approved_play_signer" || fail "owner-approved Play signer was not normalized"
if verify_play_delivered_policy_signer \
  'BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB' \
  "$approved_play_signer"; then
  fail "non-owner-approved Play signer was accepted"
fi

sealed_repo="$TEST_ROOT/sealed-repo"
mkdir "$sealed_repo"
git -C "$sealed_repo" init -q
git -C "$sealed_repo" config user.name 'Chronicle verifier fixture'
git -C "$sealed_repo" config user.email 'verifier-fixture@example.invalid'
printf 'sealed source\n' >"$sealed_repo/tracked.txt"
git -C "$sealed_repo" add tracked.txt
git -C "$sealed_repo" commit -qm 'Fixture source'
git -C "$sealed_repo" switch --detach -q HEAD
verify_sealed_source_checkout "$sealed_repo" 0 initial_seal ||
  fail "clean detached source checkout was rejected"
if verify_sealed_source_checkout "$sealed_repo" 1 initial_seal >/dev/null 2>&1; then
  fail "sealed skip-build could bind an old artifact to the current source"
fi
verify_sealed_source_checkout "$sealed_repo" 1 play_delivery ||
  fail "Play-delivered verification could not reuse the exact sealed AAB"
if verify_sealed_source_checkout "$sealed_repo" 0 play_delivery >/dev/null 2>&1; then
  fail "Play-delivered verification was allowed to rebuild the uploaded AAB"
fi
printf 'unclassified input\n' >"$sealed_repo/untracked-source.kt"
if verify_sealed_source_checkout "$sealed_repo" 0 initial_seal >/dev/null 2>&1; then
  fail "untracked source input was accepted by the sealed checkout gate"
fi
mv "$sealed_repo/untracked-source.kt" "$TEST_ROOT/removed-untracked-source.kt"
verify_sealed_source_checkout "$sealed_repo" 0 initial_seal ||
  fail "sealed checkout did not recover after untracked input removal"

git -C "$sealed_repo" switch main -q
authority_repo="$TEST_ROOT/authority-repo"
git -C "$TEST_ROOT" init -q "$authority_repo"
git -C "$authority_repo" config user.name 'Chronicle authority fixture'
git -C "$authority_repo" config user.email 'authority-fixture@example.invalid'
git -C "$authority_repo" -c protocol.file.allow=always submodule add -q "$sealed_repo" chronicle
git -C "$authority_repo" commit -qam 'Bind Android source'
git -C "$authority_repo/chronicle" switch --detach -q HEAD
authority_sha="$(git -C "$authority_repo" rev-parse HEAD)"
verify_release_authority_binding "$authority_repo/chronicle" "$authority_sha" ||
  fail "matching release-authority gitlink was rejected"
if verify_release_authority_binding \
  "$authority_repo/chronicle" '0000000000000000000000000000000000000000' >/dev/null 2>&1; then
  fail "wrong release-authority SHA was accepted"
fi
git -C "$authority_repo/chronicle" config user.name 'Chronicle unbound fixture'
git -C "$authority_repo/chronicle" config user.email 'unbound-fixture@example.invalid'
printf 'unbound source\n' >>"$authority_repo/chronicle/tracked.txt"
git -C "$authority_repo/chronicle" commit -qam 'Unbound Android source'
if verify_release_authority_binding "$authority_repo/chronicle" "$authority_sha" >/dev/null 2>&1; then
  fail "Android source outside the authority gitlink was accepted"
fi

receipt_repo="$TEST_ROOT/receipt-repo"
mkdir "$receipt_repo"
git -C "$receipt_repo" init -q
git -C "$receipt_repo" config user.name 'Chronicle receipt fixture'
git -C "$receipt_repo" config user.email 'receipt-fixture@example.invalid'
for relative_source in "${PLAY_SUBMISSION_SOURCES[@]}"; do
  mkdir -p "$receipt_repo/$(dirname "$relative_source")"
  printf 'fixture source: %s\n' "$relative_source" >"$receipt_repo/$relative_source"
done
printf 'version_code=72\nversion_name=1.2.3\n' \
  >"$receipt_repo/store/play/privacy.properties"
mkdir -p "$receipt_repo/scripts"
printf 'fixture verifier\n' >"$receipt_repo/scripts/verify-play-aab.sh"
git -C "$receipt_repo" add .
git -C "$receipt_repo" commit -qm 'Receipt fixture source'
git -C "$receipt_repo" switch --detach -q HEAD
printf 'sealed AAB bytes\n' >"$receipt_repo/sealed.aab"
printf 'sealed mapping bytes\n' >"$receipt_repo/mapping.txt"
python3 - \
  "$receipt_repo" \
  "$receipt_repo/prior-sealed-receipt.json" \
  "$approved_play_signer" \
  "${PLAY_SUBMISSION_SOURCES[@]}" <<'PY'
from pathlib import Path
import hashlib
import json
import subprocess
import sys

root = Path(sys.argv[1])
receipt_path = Path(sys.argv[2])
signer = sys.argv[3]
sources = sys.argv[4:]
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
receipt = {
    "schemaVersion": 3,
    "result": "passed",
    "versionCode": 72,
    "versionName": "1.2.3",
    "submissionMode": "sealed_owner_approved",
    "verificationPhase": "initial_seal",
    "releaseCandidateId": "fixture-rc-1",
    "aabSha256": digest(root / "sealed.aab"),
    "aabUploadSignerCertificateSha256": signer,
    "approvedModuleRegistrySha256": digest(
        root / "app/src/play/assets/approved-module-registry.json"
    ),
    "mappingSha256": digest(root / "mapping.txt"),
    "verifierScriptSha256": digest(root / "scripts/verify-play-aab.sh"),
    "playSubmissionPolicySha256": digest(root / "store/play/privacy.properties"),
    "sourceCommit": subprocess.check_output(
        ["git", "-C", str(root), "rev-parse", "HEAD"], text=True
    ).strip(),
    "sourceTree": subprocess.check_output(
        ["git", "-C", str(root), "rev-parse", "HEAD^{tree}"], text=True
    ).strip(),
    "sourceCheckoutClean": True,
    "detachedSource": True,
    "releaseAuthorityRepository": "uzaira0/methodic",
    "releaseAuthorityCommit": "1111111111111111111111111111111111111111",
    "playSubmissionSourceDigests": {
        source: digest(root / source)
        for source in sources
    },
}
receipt_path.write_text(json.dumps(receipt), encoding="utf-8")
PY
cat >"$TEST_ROOT/fake-gh" <<'FAKE_GH'
#!/usr/bin/env bash
set -euo pipefail
[[ "${FAKE_GH_FAIL:-0}" != 1 ]]
[[ "$1" == attestation && "$2" == verify ]]
[[ "$3" == "$EXPECTED_ATTESTATION_RECEIPT" ]]
[[ " $* " == *' --repo uzaira0/methodic '* ]]
[[ " $* " == *' --signer-workflow github.com/uzaira0/methodic/.github/workflows/build-android-apk.yml '* ]]
[[ " $* " == *' --source-digest 1111111111111111111111111111111111111111 '* ]]
[[ " $* " == *' --deny-self-hosted-runners '* ]]
case "${FAKE_GH_OUTPUT_MODE:-valid}" in
  valid)
    printf '%s\n' '[{"verificationResult":{"statement":{"subject":[]}}}]'
    ;;
  empty)
    ;;
  empty-array)
    printf '%s\n' '[]'
    ;;
  malformed)
    printf '%s\n' '{not-json'
    ;;
  *)
    exit 64
    ;;
esac
FAKE_GH
chmod 700 "$TEST_ROOT/fake-gh"
# Test-only substitution is reachable solely through the sourced helpers-only seam.
# shellcheck disable=SC2034
CHRONICLE_TEST_GH_ATTESTATION_COMMAND="$TEST_ROOT/fake-gh"
export EXPECTED_ATTESTATION_RECEIPT="$receipt_repo/prior-sealed-receipt.json"
verify_prior_sealed_receipt \
  "$receipt_repo/prior-sealed-receipt.json" \
  "$receipt_repo/sealed.aab" \
  "$receipt_repo/mapping.txt" \
  "$receipt_repo/app/src/play/assets/approved-module-registry.json" \
  "$receipt_repo/store/play/privacy.properties" \
  "$receipt_repo/scripts/verify-play-aab.sh" \
  "$receipt_repo" \
  fixture-rc-1 \
  "$approved_play_signer" \
  1111111111111111111111111111111111111111 \
  "$receipt_repo/verified-attestation.json" ||
  fail "authenticated matching prior sealed receipt was rejected"

snapshot_dir="$receipt_repo/input-snapshots"
mkdir "$snapshot_dir"
snapshot_stable_input \
  "$receipt_repo/prior-sealed-receipt.json" \
  "$snapshot_dir/prior-sealed-receipt.json" \
  'fixture sealed receipt' || fail "stable receipt snapshot failed"
snapshot_stable_input \
  "$receipt_repo/sealed.aab" \
  "$snapshot_dir/sealed.aab" \
  'fixture sealed AAB' || fail "stable AAB snapshot failed"
snapshot_stable_input \
  "$receipt_repo/mapping.txt" \
  "$snapshot_dir/mapping.txt" \
  'fixture sealed mapping' || fail "stable mapping snapshot failed"
printf 'post-snapshot receipt mutation\n' >>"$receipt_repo/prior-sealed-receipt.json"
printf 'post-snapshot AAB mutation\n' >>"$receipt_repo/sealed.aab"
printf 'post-snapshot mapping mutation\n' >>"$receipt_repo/mapping.txt"
export EXPECTED_ATTESTATION_RECEIPT="$snapshot_dir/prior-sealed-receipt.json"
verify_prior_sealed_receipt \
  "$snapshot_dir/prior-sealed-receipt.json" \
  "$snapshot_dir/sealed.aab" \
  "$snapshot_dir/mapping.txt" \
  "$receipt_repo/app/src/play/assets/approved-module-registry.json" \
  "$receipt_repo/store/play/privacy.properties" \
  "$receipt_repo/scripts/verify-play-aab.sh" \
  "$receipt_repo" \
  fixture-rc-1 \
  "$approved_play_signer" \
  1111111111111111111111111111111111111111 \
  "$receipt_repo/snapshot-attestation.json" ||
  fail "post-snapshot source replacement changed authenticated inputs"
cp -- "$snapshot_dir/prior-sealed-receipt.json" "$receipt_repo/prior-sealed-receipt.json"
cp -- "$snapshot_dir/sealed.aab" "$receipt_repo/sealed.aab"
cp -- "$snapshot_dir/mapping.txt" "$receipt_repo/mapping.txt"
export EXPECTED_ATTESTATION_RECEIPT="$receipt_repo/prior-sealed-receipt.json"

FAKE_GH_FAIL=1
export FAKE_GH_FAIL
if verify_prior_sealed_receipt \
  "$receipt_repo/prior-sealed-receipt.json" \
  "$receipt_repo/sealed.aab" \
  "$receipt_repo/mapping.txt" \
  "$receipt_repo/app/src/play/assets/approved-module-registry.json" \
  "$receipt_repo/store/play/privacy.properties" \
  "$receipt_repo/scripts/verify-play-aab.sh" \
  "$receipt_repo" \
  fixture-rc-1 \
  "$approved_play_signer" \
  1111111111111111111111111111111111111111 \
  "$receipt_repo/rejected-attestation.json" >/dev/null 2>&1; then
  fail "unattested prior sealed receipt was accepted"
fi
FAKE_GH_FAIL=0
export FAKE_GH_FAIL
for invalid_mode in empty empty-array malformed; do
  FAKE_GH_OUTPUT_MODE="$invalid_mode"
  export FAKE_GH_OUTPUT_MODE
  if verify_prior_sealed_receipt \
    "$receipt_repo/prior-sealed-receipt.json" \
    "$receipt_repo/sealed.aab" \
    "$receipt_repo/mapping.txt" \
    "$receipt_repo/app/src/play/assets/approved-module-registry.json" \
    "$receipt_repo/store/play/privacy.properties" \
    "$receipt_repo/scripts/verify-play-aab.sh" \
    "$receipt_repo" \
    fixture-rc-1 \
    "$approved_play_signer" \
    1111111111111111111111111111111111111111 \
    "$receipt_repo/rejected-$invalid_mode-attestation.json" >/dev/null 2>&1; then
    fail "GitHub CLI $invalid_mode attestation output was accepted"
  fi
done
FAKE_GH_OUTPUT_MODE=valid
export FAKE_GH_OUTPUT_MODE
printf 'different AAB bytes\n' >"$receipt_repo/sealed.aab"
if verify_prior_sealed_receipt \
  "$receipt_repo/prior-sealed-receipt.json" \
  "$receipt_repo/sealed.aab" \
  "$receipt_repo/mapping.txt" \
  "$receipt_repo/app/src/play/assets/approved-module-registry.json" \
  "$receipt_repo/store/play/privacy.properties" \
  "$receipt_repo/scripts/verify-play-aab.sh" \
  "$receipt_repo" \
  fixture-rc-1 \
  "$approved_play_signer" \
  1111111111111111111111111111111111111111 \
  "$receipt_repo/altered-aab-attestation.json" >/dev/null 2>&1; then
  fail "Play-delivery verification accepted an AAB outside the prior sealed receipt"
fi
unset EXPECTED_ATTESTATION_RECEIPT FAKE_GH_FAIL

precreated_output="$TEST_ROOT/precreated-evidence"
mkdir "$precreated_output"
if create_evidence_output_dir "$precreated_output" >/dev/null 2>&1; then
  fail "pre-existing evidence directory was reused"
fi
concurrent_output="$TEST_ROOT/concurrent-parent/evidence"
winner_log="$TEST_ROOT/concurrent-winners.txt"
(
  if create_evidence_output_dir "$concurrent_output" >/dev/null 2>&1; then
    printf 'winner\n' >>"$winner_log"
  fi
) &
first_creator=$!
(
  if create_evidence_output_dir "$concurrent_output" >/dev/null 2>&1; then
    printf 'winner\n' >>"$winner_log"
  fi
) &
second_creator=$!
wait "$first_creator" "$second_creator"
[[ "$(wc -l <"$winner_log" | tr -d ' ')" -eq 1 ]] ||
  fail "concurrent evidence directory creation did not have exactly one winner"
validate_evidence_output_path "$TEST_ROOT/allowed/evidence" "$TEST_ROOT/allowed" ||
  fail "project-scoped evidence child was rejected"
if validate_evidence_output_path "$TEST_ROOT/outside" "$TEST_ROOT/allowed" >/dev/null 2>&1; then
  fail "evidence path outside its project root was accepted"
fi

cat >"$TEST_ROOT/fake-apksigner" <<'FAKE_APKSIGNER'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' 'Signer #1 certificate SHA-256 digest: AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA'
if [[ "${FAKE_APKSIGNER_MODE:-single}" == "multiple" ]]; then
  printf '%s\n' 'Signer #2 certificate SHA-256 digest: BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB'
fi
FAKE_APKSIGNER
chmod 700 "$TEST_ROOT/fake-apksigner"
[[ "$(extract_single_current_apk_signer "$TEST_ROOT/fake-apksigner" ignored.apk)" == \
  'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' ]] ||
  fail "single current APK signer was not normalized"
if FAKE_APKSIGNER_MODE=multiple \
  extract_single_current_apk_signer "$TEST_ROOT/fake-apksigner" ignored.apk >/dev/null; then
  fail "multiple current APK signers were accepted"
fi

uid_rows="$TEST_ROOT/uids.txt"
cat >"$uid_rows" <<'ROWS'
package:com.bcm.chronicle.debug uid:10101
package:com.bcm.chronicle uid:10202
package:com.example.other uid:10303
ROWS
[[ "$(resolve_exact_unshared_package_uid "$uid_rows" com.bcm.chronicle)" == "10202" ]] ||
  fail "exact package UID was not selected"
printf 'package:com.example.shared uid:10202\n' >>"$uid_rows"
uid_status=0
resolve_exact_unshared_package_uid "$uid_rows" com.bcm.chronicle >/dev/null || uid_status=$?
[[ "$uid_status" -eq 2 ]] || fail "shared UID was not rejected"
printf 'package:com.bcm.chronicle uid:10202\n' >>"$uid_rows"
uid_status=0
resolve_exact_unshared_package_uid "$uid_rows" com.bcm.chronicle >/dev/null || uid_status=$?
[[ "$uid_status" -eq 1 ]] || fail "duplicate exact package rows were not rejected"

validate_runtime_ip_origin \
  'https://192.0.2.10:8443' \
  "$TEST_ROOT/origin.txt" "$TEST_ROOT/ipv4.txt" "$TEST_ROOT/ipv6.txt"
grep -Fxq 'origin=https://192.0.2.10:8443' "$TEST_ROOT/origin.txt" ||
  fail "accepted origin was not canonicalized"
grep -Fxq '192.0.2.10' "$TEST_ROOT/ipv4.txt" || fail "IPv4 origin was not emitted"
if validate_runtime_ip_origin \
  'https://shared.example:8443' \
  "$TEST_ROOT/bad-origin.txt" "$TEST_ROOT/bad-v4.txt" "$TEST_ROOT/bad-v6.txt" \
  >/dev/null 2>&1; then
  fail "shared virtual-host origin was accepted"
fi
if validate_runtime_ip_origin \
  'https://192.0.2.10:8443/path' \
  "$TEST_ROOT/bad-path.txt" "$TEST_ROOT/bad-path-v4.txt" "$TEST_ROOT/bad-path-v6.txt" \
  >/dev/null 2>&1; then
  fail "non-root origin was accepted"
fi

cat >"$TEST_ROOT/enrollment.xml" <<'XML'
<hierarchy>
  <node text="Active study&#10;Server origin: https://192.0.2.10:8443&#10;Uploads: active&#10;Connection: Healthy" />
</hierarchy>
XML
active_enrollment_summary_matches "$TEST_ROOT/enrollment.xml" 'https://192.0.2.10:8443' ||
  fail "exact active enrollment origin was not accepted"
for invalid_origin in \
  'https://192.0.2.10:8443/path' \
  'https://192.0.2.10' \
  'https://192.0.2.1:8443'; do
  if active_enrollment_summary_matches "$TEST_ROOT/enrollment.xml" "$invalid_origin"; then
    fail "non-exact active enrollment origin was accepted: $invalid_origin"
  fi
done
cat >"$TEST_ROOT/enrollment-unhealthy.xml" <<'XML'
<hierarchy>
  <node text="Active study&#10;Server origin: https://192.0.2.10:8443&#10;Uploads: active&#10;Connection: Offline" />
</hierarchy>
XML
if active_enrollment_summary_matches \
  "$TEST_ROOT/enrollment-unhealthy.xml" 'https://192.0.2.10:8443'; then
  fail "unhealthy enrollment summary was accepted"
fi

mkdir -p "$TEST_ROOT/built" "$TEST_ROOT/installed"
python3 - "$TEST_ROOT" <<'PY'
from pathlib import Path
import sys
import zipfile

root = Path(sys.argv[1])
with zipfile.ZipFile(root / "built" / "split-a.apk", "w") as archive:
    archive.writestr("AndroidManifest.xml", b"manifest")
    archive.writestr("classes.dex", b"payload")
    archive.writestr("META-INF/services/example.Provider", b"provider.One")
    archive.writestr("META-INF/DEBUG.SF", b"debug-signature")
with zipfile.ZipFile(root / "installed" / "base.apk", "w") as archive:
    archive.writestr("AndroidManifest.xml", b"manifest")
    archive.writestr("classes.dex", b"payload")
    archive.writestr("META-INF/services/example.Provider", b"provider.One")
    archive.writestr("META-INF/PLAY.SF", b"play-signature")
    archive.writestr("stamp-cert-sha256", b"play-stamp")
PY
write_apk_payload_identities "$TEST_ROOT/built" "$TEST_ROOT/built.identities"
write_apk_payload_identities "$TEST_ROOT/installed" "$TEST_ROOT/installed.identities"
cmp -s "$TEST_ROOT/built.identities" "$TEST_ROOT/installed.identities" ||
  fail "signing-only APK differences changed normalized payload identity"
python3 - "$TEST_ROOT/installed/base.apk" <<'PY'
from pathlib import Path
import sys
import zipfile

path = Path(sys.argv[1])
with zipfile.ZipFile(path, "w") as archive:
    archive.writestr("AndroidManifest.xml", b"manifest")
    archive.writestr("classes.dex", b"payload")
    archive.writestr("META-INF/services/example.Provider", b"provider.Two")
    archive.writestr("META-INF/PLAY.SF", b"play-signature")
PY
write_apk_payload_identities "$TEST_ROOT/installed" "$TEST_ROOT/changed.identities"
if cmp -s "$TEST_ROOT/built.identities" "$TEST_ROOT/changed.identities"; then
  fail "changed META-INF service provider retained the normalized identity"
fi
python3 - "$TEST_ROOT/installed/base.apk" <<'PY'
from pathlib import Path
import sys
import zipfile

path = Path(sys.argv[1])
with zipfile.ZipFile(path, "w") as archive:
    archive.writestr("AndroidManifest.xml", b"manifest")
    archive.writestr("classes.dex", b"changed-payload")
    archive.writestr("META-INF/services/example.Provider", b"provider.One")
    archive.writestr("META-INF/PLAY.SF", b"play-signature")
PY
write_apk_payload_identities "$TEST_ROOT/installed" "$TEST_ROOT/changed.identities"
if cmp -s "$TEST_ROOT/built.identities" "$TEST_ROOT/changed.identities"; then
  fail "changed APK payload retained the normalized identity"
fi

FAKE_ADB_MODE=success
adb() {
  local command="${*: -1}"
  case "$FAKE_ADB_MODE" in
    offline)
      printf 'error: device offline\n' >&2
      return 1 ;;
    present)
      if [[ "$command" == *'-S OUTPUT'* ]]; then
        printf '%s\n' "-A OUTPUT -j $EGRESS_CHAIN_NAME"
      elif [[ "$command" == *'-S '*"$EGRESS_CHAIN_NAME"* ]]; then
        printf '%s\n' "-N $EGRESS_CHAIN_NAME"
      fi
      return 0 ;;
    success)
      if [[ "$command" == *'-S OUTPUT'* ]]; then
        printf '%s\n' '-P OUTPUT ACCEPT'
        return 0
      fi
      if [[ "$command" == *'-S '*"$EGRESS_CHAIN_NAME"* ]]; then
        printf 'iptables: No chain/target/match by that name.\n' >&2
        return 1
      fi
      return 0 ;;
  esac
}

# Consumed dynamically by the sourced cleanup helper.
# shellcheck disable=SC2034
EGRESS_SERIAL=fixture-serial
# Consumed dynamically by the sourced cleanup helper.
# shellcheck disable=SC2034
EGRESS_PACKAGE_UID=10202
EGRESS_CHAIN_NAME=CHRPL10202_fixture
EGRESS_UI_DUMP_LOCAL="$TEST_ROOT/sensitive-ui.xml"
# Consumed dynamically by the sourced cleanup helper.
# shellcheck disable=SC2034
EGRESS_UI_DUMP_REMOTE=/data/local/tmp/sensitive-ui.xml
printf 'synthetic participant reference\n' >"$EGRESS_UI_DUMP_LOCAL"
EGRESS_CLEANUP_NEEDED=0
cleanup_runtime_egress
[[ ! -e "$TEST_ROOT/sensitive-ui.xml" ]] || fail "temporary enrollment UI was retained"
EGRESS_CLEANUP_NEEDED=1
cleanup_runtime_egress
[[ "$EGRESS_CLEANUP_FAILED" -eq 0 && "$EGRESS_CLEANUP_NEEDED" -eq 0 ]] ||
  fail "confirmed firewall deletion did not pass"

for FAKE_ADB_MODE in offline present; do
  EGRESS_CLEANUP_NEEDED=1
  cleanup_runtime_egress
  [[ "$EGRESS_CLEANUP_FAILED" -eq 1 && "$EGRESS_CLEANUP_NEEDED" -eq 1 ]] ||
    fail "$FAKE_ADB_MODE firewall verification false-greened"
done

arm_line="$(grep -n '^  EGRESS_CLEANUP_NEEDED=1$' "$VERIFIER" | tail -n 1 | cut -d: -f1)"
mutation_line="$(grep -n 'shell "iptables .* -N \$chain_name"' "$VERIFIER" | cut -d: -f1)"
[[ -n "$arm_line" && -n "$mutation_line" && "$arm_line" -lt "$mutation_line" ]] ||
  fail "cleanup state is not armed before the first firewall mutation"

printf 'Play AAB runtime helper tests passed.\n'
