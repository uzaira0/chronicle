#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

release_abis_only=0
if [[ "${1:-}" == "--release-abis-only" ]]; then
  release_abis_only=1
  shift
fi

find_zipalign() {
  if command -v zipalign >/dev/null 2>&1; then
    command -v zipalign
    return
  fi
  local sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  find "$sdk_root/build-tools" -type f -name zipalign 2>/dev/null | sort -V | tail -n 1
}

find_readelf() {
  if command -v llvm-readelf >/dev/null 2>&1; then
    command -v llvm-readelf
    return
  fi
  if command -v readelf >/dev/null 2>&1; then
    command -v readelf
    return
  fi
  find /opt/homebrew /usr/local -path '*/bin/llvm-readelf' \( -type f -o -type l \) 2>/dev/null | sort -V | tail -n 1
}

zipalign_bin="$(find_zipalign)"
readelf_bin="$(find_readelf)"

if [[ -z "${zipalign_bin:-}" || ! -x "$zipalign_bin" ]]; then
  echo "ERROR: zipalign not found. Install Android SDK build-tools or set ANDROID_HOME." >&2
  exit 2
fi

if [[ -z "${readelf_bin:-}" || ! -x "$readelf_bin" ]]; then
  echo "ERROR: llvm-readelf/readelf not found. Install LLVM or binutils." >&2
  exit 2
fi

declare -a artifacts
if [[ "$#" -gt 0 ]]; then
  artifacts=("$@")
else
  while IFS= read -r artifact; do
    artifacts+=("$artifact")
  done < <(find "$ROOT_DIR/app/build/outputs/apk" -type f -name '*.apk' 2>/dev/null | sort)
fi

if [[ "${#artifacts[@]}" -eq 0 ]]; then
  echo "ERROR: no APK artifacts supplied or found under app/build/outputs/apk." >&2
  exit 2
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
zipalign_log="$tmp_dir/zipalign-16kb.log"

failures=0

for artifact in "${artifacts[@]}"; do
  if [[ ! -f "$artifact" ]]; then
    echo "FAIL: missing artifact $artifact"
    failures=$((failures + 1))
    continue
  fi

  echo "== $artifact =="
  if "$zipalign_bin" -c -P 16 -v 4 "$artifact" >"$zipalign_log" 2>&1; then
    echo "zipalign: OK"
  else
    echo "zipalign: FAIL"
    cat "$zipalign_log"
    failures=$((failures + 1))
    continue
  fi

  mapfile -t native_entries < <(zipinfo -1 "$artifact" 'lib/*.so' 2>/dev/null || true)
  if [[ "${#native_entries[@]}" -eq 0 ]]; then
    echo "native-libs: none"
    continue
  fi

  for required_abi in arm64-v8a armeabi-v7a; do
    if ! printf '%s\n' "${native_entries[@]}" | grep -Eq "^lib/${required_abi}/"; then
      echo "ABI: FAIL missing required ${required_abi} native library"
      failures=$((failures + 1))
    else
      echo "ABI: OK ${required_abi}"
    fi
  done

  if [[ "$release_abis_only" -eq 1 ]]; then
    mapfile -t packaged_abis < <(
      printf '%s\n' "${native_entries[@]}" | awk -F/ '{print $2}' | sort -u
    )
    for packaged_abi in "${packaged_abis[@]}"; do
      case "$packaged_abi" in
        arm64-v8a|armeabi-v7a)
          ;;
        *)
          echo "ABI: FAIL unexpected release ABI ${packaged_abi}"
          failures=$((failures + 1))
          ;;
      esac
    done
  fi

  artifact_dir="$tmp_dir/$(basename "$artifact")"
  mkdir -p "$artifact_dir"
  unzip -q "$artifact" 'lib/*.so' -d "$artifact_dir"

  for entry in "${native_entries[@]}"; do
    so_path="$artifact_dir/$entry"
    # GNU readelf wraps program headers across two lines unless wide output is
    # requested; llvm-readelf accepts -W for GNU compatibility. Keeping each
    # LOAD record on one line makes $NF the p_align field on both platforms.
    mapfile -t alignments < <("$readelf_bin" -W -l "$so_path" | awk '/LOAD/ {print $NF}' | sort -u)
    if [[ "${#alignments[@]}" -eq 0 ]]; then
      echo "ELF: FAIL $entry no LOAD segments found"
      failures=$((failures + 1))
      continue
    fi

    bad=0
    for alignment in "${alignments[@]}"; do
      value=$((alignment))
      if (( value < 0x4000 || value % 0x4000 != 0 )); then
        bad=1
      fi
    done

    if [[ "$bad" -eq 0 ]]; then
      echo "ELF: OK $entry alignments=${alignments[*]}"
    else
      echo "ELF: FAIL $entry alignments=${alignments[*]}"
      failures=$((failures + 1))
    fi
  done
done

if [[ "$failures" -gt 0 ]]; then
  echo "16 KB native library verification failed: $failures failure(s)." >&2
  exit 1
fi

echo "16 KB native library verification passed."
