# Google Play package inventory

Captured: `2026-08-26T15:32:20Z`

## Scope and method

The protected Chronicle Play service-account credential authenticated to Android Publisher API v3
for package `com.bcm.chronicle`. The check created one temporary unpublished edit, queried its
track, APK, and Android App Bundle inventories, and deleted the edit in the same guarded operation.
It did not upload an artifact, change a track, commit an edit, or publish anything. No credential,
access token, edit identifier, or private-key material was retained in this receipt.

## Sanitized result

- Tracks containing version codes: none.
- Uploaded APK version codes: none.
- Uploaded Android App Bundle version codes: none.
- Maximum uploaded version code: `0`.

## Release consequence

The first Chronicle upload may use any positive `versionCode`; the current diagnostic value `54`
is numerically greater than the authoritative maximum. This inventory does not approve `54` as the
final release version, approve the current version name, or confirm the upload-certificate lineage.
Those remain explicit owner/signing decisions before the release can be sealed.
