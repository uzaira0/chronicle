#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
selected_channel="${1:-all}"
sealed_submission=0
if [[ $# -gt 0 ]]; then
  shift
fi
while [[ $# -gt 0 ]]; do
  case "$1" in
    --sealed)
      sealed_submission=1
      ;;
    *)
      echo "Usage: $0 [play|amazon|research|all] [--sealed]" >&2
      exit 2
      ;;
  esac
  shift
done
if [[ "$sealed_submission" -eq 1 && "$selected_channel" != play && "$selected_channel" != all ]]; then
  echo "ERROR: --sealed is valid only for the Play submission contract" >&2
  exit 2
fi

verify_channel() {
  local channel="$1"
  local variant="${channel}Release"
  local first_character
  first_character="$(printf '%s' "${channel:0:1}" | tr '[:lower:]' '[:upper:]')"
  local task_variant="${first_character}${channel:1}Release"
  # process<Variant>MainManifest is the task this verifier tells callers to run. Prefer its
  # output so an older packaged/merged manifest from a prior build cannot mask current source.
  local manifest="$root/app/build/intermediates/merged_manifest/$variant/process${task_variant}MainManifest/AndroidManifest.xml"
  if [[ ! -f "$manifest" ]]; then
    manifest="$root/app/build/intermediates/merged_manifests/$variant/process${task_variant}Manifest/AndroidManifest.xml"
  fi
  if [[ ! -f "$manifest" ]]; then
    variant="${channel}Debug"
    task_variant="${first_character}${channel:1}Debug"
    manifest="$root/app/build/intermediates/merged_manifests/$variant/process${task_variant}Manifest/AndroidManifest.xml"
  fi
  if [[ ! -f "$manifest" ]]; then
    echo "ERROR: merged $channel manifest is missing; run :app:process${task_variant}MainManifest" >&2
    return 1
  fi
  java "$root/scripts/StoreReadinessVerifier.java" \
    "$channel" "$manifest" "$root/store/$channel/privacy.properties"
}

verify_play_submission_files() {
  local required=(
    listing.md
    privacy.properties
    reviewer-instructions.md
    release-checklist.md
    data-safety.md
    health-apps-declaration.md
    accessibility-declaration.md
    foreground-service-declaration.md
    assets.md
    discovery-report.md
  )
  local file
  for file in "${required[@]}"; do
    [[ -s "$root/store/play/$file" ]] || {
      echo "ERROR: missing Play submission source: store/play/$file" >&2
      return 1
    }
  done
  rg -q '^App name \(30 characters maximum\):' "$root/store/play/listing.md" \
    || { echo "ERROR: Play listing is missing its app-name field" >&2; return 1; }
  rg -q '^Short description \(80 characters maximum\):' "$root/store/play/listing.md" \
    || { echo "ERROR: Play listing is missing its short-description field" >&2; return 1; }
  rg -q 'not a medical device' "$root/store/play/listing.md" \
    || { echo "ERROR: Play listing is missing the health disclaimer" >&2; return 1; }
  local submission_args=(--root "$root")
  if [[ "$sealed_submission" -eq 1 ]]; then
    submission_args+=(--sealed)
  fi
  python3 "$root/scripts/verify-play-submission.py" "${submission_args[@]}"
}

case "$selected_channel" in
  play|amazon)
    verify_channel "$selected_channel"
    [[ "$selected_channel" != play ]] || verify_play_submission_files
    ;;
  research)
    # The research distribution is not submitted to a public app store, so it
    # has no store privacy manifest to verify. Its assembled artifact is still
    # checked by the workflow's signing/alignment steps.
    ;;
  all)
    verify_channel play
    verify_play_submission_files
    verify_channel amazon
    ;;
  *)
    echo "Usage: $0 [play|amazon|research|all] [--sealed]" >&2
    exit 2
    ;;
esac

if rg -n 'amazonImplementation.*(play-services|health-connect)' "$root/app/build.gradle"; then
  echo "ERROR: Amazon dependency configuration references a Google runtime dependency." >&2
  exit 1
fi

echo "Local store-readiness checks passed for $selected_channel. Console declarations and physical-device gates remain external."
