#!/usr/bin/env bash
# Fails when R8 renamed a class or field that Moshi or Gson serializes by reflection.
#
# Usage: scripts/verify-wire-dto-names.sh <release.aab | mapping.txt>
#
# Every Retrofit @Body type in the study API interfaces is serialized by Moshi's reflective
# Kotlin adapter. If R8 renames it (and drops its kotlin.Metadata), the JSON keys become "a".."j"
# and the server cannot bind them. A class absent from the mapping (e.g. restricted collectors in
# the play flavor) is fine; a class present under another name is not. NotificationDetails is
# round-tripped through PendingIntent extras by Gson, so its field names and NotificationType constants must also survive.
set -euo pipefail

input="${1:?usage: $0 <release.aab | mapping.txt>}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
api_files=(
  "$repo_root/app/src/main/java/com/openlattice/chronicle/api/ChronicleStudyApi.kt"
  "$repo_root/app/src/googleServices/java/com/openlattice/chronicle/api/RestrictedChronicleStudyApi.kt"
)
gson_classes=(
  com.openlattice.chronicle.services.notifications.NotificationDetails
  com.openlattice.chronicle.constants.NotificationType
)
persisted_classes=(
  'com.openlattice.chronicle.collection.directboot.DirectBootSampleBuffer$Batch'
  'com.openlattice.chronicle.collection.directboot.DirectBootDiagnosticsJournal$Owner'
  'com.openlattice.chronicle.collection.directboot.DirectBootDiagnosticsJournal$Event'
  'com.openlattice.chronicle.collection.directboot.DirectBootDiagnosticsJournal$State'
  com.openlattice.chronicle.services.upload.LocalUploadIssueBucket
  com.openlattice.chronicle.preferences.InteractionPolicySnapshot
  com.openlattice.chronicle.collection.InteractionPolicy
  com.openlattice.chronicle.collection.state.PendingCollectionAckRecord
  com.openlattice.chronicle.services.crypto.SealedEnvelopeEntry
  com.openlattice.chronicle.study.StudyEncryptionSetting
  com.openlattice.chronicle.api.MobileEnrollmentManifest
  com.openlattice.chronicle.models.ExtractedUsageEvent
  com.openlattice.chronicle.models.ExtractedActivities
  com.openlattice.chronicle.models.ExtractUsageStat
)
while IFS= read -r source; do
  persisted_classes+=("com.openlattice.chronicle.storage.$(basename "$source" .kt)")
done < <(rg --files "$repo_root/collection-base/src/main/java/com/openlattice/chronicle/storage" | rg 'SampleEntry\.kt$')

mkdir -p "$repo_root/build"
work="$(mktemp -d "$repo_root/build/verify-wire-dto.XXXXXX")"
trap 'rm -rf "$work"' EXIT
if [[ "$input" == *.aab ]]; then
  unzip -p "$input" BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map >"$work/map" || {
    printf 'ERROR: %s has no embedded R8 mapping\n' "$input" >&2
    exit 1
  }
  mapping="$work/map"
else
  mapping="$input"
fi
[[ -s "$mapping" ]] || { printf 'ERROR: empty mapping %s\n' "$mapping" >&2; exit 1; }

# @Body parameter types, resolved to fully qualified names through each file's imports.
dtos=()
for api in "${api_files[@]}"; do
  [[ -f "$api" ]] || continue
  package="$(sed -n 's/^package \(.*\)$/\1/p' "$api")"
  while IFS= read -r simple; do
    fqn="$(sed -n "s/^import \(.*\.$simple\)$/\1/p" "$api" | head -n1)"
    dtos+=("${fqn:-$package.$simple}")
  done < <(rg -o '@Body [A-Za-z_]+: [A-Za-z<>]+' "$api" \
    | sed -E 's/.*: //; s/^(List|Set)<//; s/>$//' | sort -u)
done
(( ${#dtos[@]} > 0 )) || { printf 'ERROR: found no @Body types\n' >&2; exit 1; }

failures=0
for cls in "${dtos[@]}" "${gson_classes[@]}" "${persisted_classes[@]}"; do
  line="$(awk -v cls="$cls -> " 'index($0, cls) == 1 { print; exit }' "$mapping")"
  [[ -z "$line" ]] && continue # shrunk out of this flavor
  mapped="${line#* -> }"
  mapped="${mapped%:}"
  if [[ "$mapped" != "$cls" ]]; then
    printf 'ERROR: wire class renamed by R8: %s -> %s\n' "$cls" "$mapped" >&2
    failures=$((failures + 1))
  fi
done
for cls in "${dtos[@]}" "${gson_classes[@]}" "${persisted_classes[@]}"; do
  # Field lines follow the class line and are indented: "    type name -> obfuscated".
  renamed="$(awk -v cls="$cls -> " '
      index($0, cls) == 1 { inside = 1; next }
      /^[^ #]/ { inside = 0 }
      inside && $0 !~ /\(/ && $0 ~ / -> / { split($0, p, " -> "); n = split(p[1], a, " "); if (a[n] != p[2]) print a[n] " -> " p[2] }
    ' "$mapping")"
  if [[ -n "$renamed" ]]; then
    printf 'ERROR: reflective JSON field names renamed in %s:\n%s\n' "$cls" "$renamed" >&2
    failures=$((failures + 1))
  fi
done

# Moshi's Kotlin adapter resolves each property type by its source name through kotlin.Metadata,
# so a kept class with a field of a renamed type (an enum, typically) fails at runtime with
# ClassNotFoundException. Static fields (companions, constants, synthetic $ entries) are not
# serialized and are skipped. Generic element types are erased in the mapping and not covered here.
checked="$(printf '%s\n' "${dtos[@]}" "${gson_classes[@]}" "${persisted_classes[@]}")"
renamed_types="$(awk -v checked="$checked" '
    BEGIN { n = split(checked, c, "\n"); for (i = 1; i <= n; i++) want[c[i]] = 1 }
    /^[^ #]/ { split($0, p, " -> "); cls = p[1]; sub(/:$/, "", p[2]); if (p[1] != p[2]) renamed[p[1]] = p[2]; next }
    want[cls] && $0 !~ /\(/ && $0 ~ / -> / { split($0, p, " -> "); n = split(p[1], a, " "); if (a[n] == "Companion" || a[n] ~ /^[$]/ || a[n] ~ /^[A-Z0-9_]+$/) next; t = a[1]; sub(/(\[\])+$/, "", t); fields[cls " " t] = 1 }
    END { for (k in fields) { split(k, f, " "); if (f[2] in renamed) print f[1] ": " f[2] " -> " renamed[f[2]] } }
  ' "$mapping" | sort)"
if [[ -n "$renamed_types" ]]; then
  printf 'ERROR: reflective wire classes reference types renamed by R8:\n%s\n' "$renamed_types" >&2
  failures=$((failures + 1))
fi

if (( failures > 0 )); then
  exit 1
fi
printf 'OK: %d reflective wire/persisted classes keep class and field names in %s\n' \
  "$(( ${#dtos[@]} + ${#gson_classes[@]} + ${#persisted_classes[@]} ))" "$input"
