# Google Play release checklist

Play ships the **open** variant (`bundleOpenRelease`, `play_variant=openRelease` in
`privacy.properties`) since v58. Sections below that name older version codes are historical
evidence only. Current qualification evidence: `privacy.properties`, `verify-play-submission.py` and
`scripts/play-upload.py check`.

This checklist covers Chronicle Internal Testing of the open variant: exact-server enrollment,
informed consent, the modules in `app/src/open/assets/approved-module-registry.json` (app usage
and in-app activity class, device lifecycle and settings, battery, connectivity and app network
usage, unlock-based user identification, 11 hardware sensors up to 200 Hz, upload diagnostics,
questionnaire reminders),
reliable upload, and in-app Withdraw from study (stops collection and sends a withdrawal request; the
study team keeps or erases earlier data according to the study's withdrawal policy). Interaction events, notification activity, audio, Health Connect, sleep, activity recognition and step counter are excluded;
a study enabling one is refused at enrollment.

## Merged source and diagnostic artifact

- [x] Participant-form/questionnaire runtime removed; enrollment monitoring is withdrawal-safe;
  upload diagnostics are mandatory, disclosed and minimized, and follow study retention or explicit
  erasure; unsupported legacy Firebase/Crashlytics claims removed. Artifact, reviewer and
  Play-delivery checks remain gates below.
- [x] The verifier binds the AAB's embedded R8 mapping, complete delivered split set, package,
  version, RC ID, approved-registry hash, signer, permissions, components, classes, resources,
  native libraries, and prohibited strings. Artifact tests reject package replacement.
- [x] Installed verification has two explicit modes: local exact-AAB-derived splits require
  byte-identical APKs, while Play-delivered splits require the pinned Play app-signing certificate
  and identical normalized payloads. Normalization excludes only signing artifacts and retains
  runtime-relevant `META-INF`, resources, code, manifests, and native libraries.
- [ ] Build one sealed final RC only after the version/signing, policy-copy, and Play-account
  inputs are resolved. A public reviewer environment is not an Internal Testing gate.

## Submission contract and sealed-RC gate

- [x] `store/play/privacy.properties` is the accountable owner-decision manifest. It records the
  Play-account/package/signing lineage, maximum prior version code, final version, target ages,
  Data Safety, legal/retention/research classification, support identity, release stage,
  upload certificate, Play app-signing certificate, and approval identity/timestamp.
- [x] The manifest binds the exact approved-module registry, listing, Data Safety, Health Apps,
  accessibility, foreground-service, and reviewer-instruction files by SHA-256. Copy changes after
  approval fail verification until the owner reviews and rebinds them.
- [x] Technical mode truthfully permits `pending_owner_approval` so source checks can run without
  inventing owner answers. For Internal Testing, reviewer-environment status must be explicitly
  `not_required_internal_testing`; sealed mode requires every applicable approval state to be `approved`, a safe
  non-`UNSEALED` RC ID, a final `versionCode` greater than the Console maximum, both certificate
  fingerprints, target ages, and the sharing determination. A public reviewer origin becomes a gate
  only when `release_stage=play_review`.
- [x] A fully pinned AAB verification additionally requires the policy RC ID and upload-certificate
  fingerprint to equal the verifier inputs, a detached source checkout with no modified, staged, or
  untracked source inputs, and a fresh build (sealed `--skip-build` is forbidden). Its mode-0600
  receipt binds the AAB, signer, RC, registry, R8 mapping, verifier, submission manifest, source
  commit/tree, and every approved copy/declaration hash.
- [x] Play-delivered verification must use the owner-approved Play app-signing certificate—not an
  independent caller-selected signer. It requires `--skip-build` plus the initial
  `--prior-sealed-receipt`, cryptographically verifies that receipt's GitHub artifact attestation
  against the fixed `uzaira0/chronicle-platform` signer workflow and release-authority source SHA, proves the
  supplied AAB/source tree/RC/policy/mapping/registry/upload signer still match, and then records the
  installed signer, installation mode, and normalized installed-split payload manifest in the
  delivery receipt.
- [x] Root PR #177 merged the fixed-authority sealing workflow at
  `e707983723688b1fe5dd5052a09f442db1de53f2`. Its Android build job is the sole sealed-AAB
  producer, and a separate least-privilege job attests the exact AAB and initial receipt. The full
  required pull-request matrix passed, including the 9m59s selfhost HTTP smoke job.
- [x] `scripts/test-verify-play-submission.sh` proves an approved synthetic decision set passes and
  that pending approval, source-copy drift, false upload-diagnostic claims, and module-list drift
  fail closed.
- [ ] The current manifest deliberately remains `pending_owner_approval`; a diagnostic verification
  may pass, but a sealed verification must fail until the exact owner inputs below are supplied.

## Android and selfhost interoperability proved

- [x] A clean API 33 emulator enrolled the exact post-merge AAB-derived split set into a fresh
  synthetic participant on the HTTPS selfhost environment.
- [x] Enrollment presented exactly four required consent steps: App Usage Events, Device
  Lifecycle, Unlock User Identification, and Battery Telemetry. No audio or Health Connect step
  appeared.
- [x] Settings synchronization and the immutable collection acknowledgment completed.
- [x] Usage polling persisted and uploaded synthetic app-usage records; battery telemetry uploaded;
  the reminder worker reconciled successfully with the fixed minified DTOs.
- [x] Enabling Identify device user started `DeviceUnlockMonitoringService`; a `USER_PRESENT`
  event opened the private prompt, and selecting the synthetic target attached the expected local
  label to subsequently uploaded usage.
- [x] Legacy/backend withdrawal compatibility changed the participant to `NOT_ENROLLED`, revoked the only active device key,
  created the immutable receipt and `WITHDRAW_AND_ERASE` deletion operation, and cleared queued
  usage.
- [x] A post-withdrawal probe produced no newly accepted usage or battery traffic, kept active keys
  at zero, and confirmed the unlock service was stopped.
- [x] The protected integration-workspace runtime receipt is
  `../build/operator-test-runs/android-selfhost/run.7JPszQ/runtime-proof-postmerge-005.json`
  (SHA-256 `2e1894114911b44be8b6e412139f0c4f0d9bd5e980ebe4e163f5c6a3334b4698`).
- [x] The final current-verifier qualification repeated exact split verification, enrollment into
  the disposable HTTPS selfhost stack, active/healthy Settings proof, and runtime egress on API 33.
  Receipt:
  `app/build/play-aab-evidence/20260822-final-current-egress-proof/runtime-egress-receipt.json`
  (SHA-256 `faccb8925358d713d23a33c1ac413dd29ab128dc4356da1282aa3e7ca8e73f1e`).
  It binds verifier SHA-256
  `8ecd9ead18a9a9aed280bcacb4564dc5ba474c8881e529a83f55b9821450b520`,
  exact origin `https://192.168.1.128:445`, 17 allowed-origin packets, 0 blocked-destination
  packets, exact origin/healthy UI state, and proven firewall/UI-dump cleanup.
- [x] Legacy/backend withdrawal then completed after relaunch reconciliation. A separate 20-second observation
  recorded 0 packets to the former study origin and 0 packets elsewhere, with firewall cleanup:
  `app/build/play-aab-evidence/20260822-post-withdrawal-no-traffic/summary.txt`
  (SHA-256 `43e5b7597bc1ee0178f7bd8bfe8be997ea18e36ee7fc4d5f2de2877cc23303ec`).

## Selfhost lifecycle proved

- [x] The simple selfhost path passed fresh install, doctor/functional smoke, update mechanics,
  backup, rollback/forward recovery, restore, teardown/recreation, restart-order checks, and
  containment/withdrawal continuity.
- [x] TDE key rotation, backend restart, backup/restore, and keyring continuity passed.
- [x] The protected integration-workspace lifecycle receipt is
  `../build/operator-test-runs/selfhost-release-smoke/run.OFCETv/result.txt`
  (SHA-256 `56e78cb22448afc07bce08d05783fe98a56570ec2108e8e5485bb675f054f2eb`).
- [x] Source configuration and a disposable local Caddy runtime historically proved an anonymous
  `/reviewer` entrypoint before dashboard authentication. This is
  compatibility evidence only: that route and any current 401 response are not Internal Testing
  gates. The Play privacy-policy URL is the BCM general privacy policy, https://www.bcm.edu/privacy (owner, 2026-11-04).
- [ ] No historically published selfhost predecessor exists. Do not claim old-binary upgrade
  compatibility until an actual released predecessor digest exists; the current proof covers the
  versioned bundle update/rollback mechanics.

## Inputs that require the owner or Play Console

- [x] The Google Play account/service-account access and existing `com.bcm.chronicle` package
  ownership were verified; `play_account_status` and `package_ownership_status` are approved.
- [x] `signing_lineage_status` is approved from the owner-confirmed Play app-signing certificate,
  the dedicated upload-key certificate derived outside the repository, and the matching release
  workflow configuration. The first Internal Testing upload must still confirm Play accepted that
  upload certificate; that is delivery evidence, not a prerequisite for sealing the upload itself.
- [x] `maximum_uploaded_version_code` in `privacy.properties` is the highest version code Play has
  accepted; `scripts/play-upload.py` raises it after each upload and refuses a version code at or
  below it.
- [ ] Whenever an accepted Play version first ships a `ChronicleDb` version, add that version's
  unchanged exported identity hash to `ChronicleDbMigrationTest.PUBLISHED_IDENTITY` with its Play
  version code. Run `:app:testOpenDebugUnitTest --tests '*ChronicleDbMigrationTest*'` and preserve
  already published schema files; an entity change needs a new database version and migration.
  Verify queued rows and enrollment also survive the released predecessor's in-place upgrade.
- [x] `scripts/play-upload.py upload` runs only with the hash-pinned Google client libraries in
  `scripts/play-upload.requirements.txt`, installed into a dedicated virtualenv
  (`uv pip sync --require-hashes`); it refuses before reading credentials when an installed version
  differs from the lock.
- [ ] Each version bump sets a `release_candidate_id` that names the new version code
  (`-v<versionCode>-`); `scripts/play-upload.py check` refuses one that does not.
- [x] `upload_certificate_sha256` records the dedicated production upload certificate and
  `play_app_signing_certificate_sha256` records Play's separate app-signing certificate
  fingerprint. The upload-key recovery credentials are stored outside the repository and the
  GitHub release workflow is configured with the same upload certificate.
- [x] The owner selected `uzairalam998@gmail.com` as the initial store/support contact, and
  `support_identity_status=approved` records that decision. This does not approve the separate
  privacy, retention, or legal-copy gates.
- [x] Target ages (`18_and_over`), human-subjects-research Health Apps classification, and support
  identity are recorded as owner-approved decisions.
- [x] The owner selected the Play store category `Parenting`; the accountable decision manifest
  records and validates that category so it cannot drift during final sealing.
- [ ] Export the current Console Data Safety CSV to `store/play/data-safety-console.csv`.
  The sealed-submission verifier compares its deletion-request answer to the UI-tested declaration.
- [ ] Approve the bundled policy in the declared Play AAB and record the SHA-256 of its compiled
  `platform_privacy_policy_full` text in `store/play/platform-policy-approved.sha256`.
  The bundle task verifies the exact AAB; a digest from another flavor or policy text fails.
- [ ] Update the Console Data Safety declaration against `data-safety.md` to include Crash logs:
  first-party app-crash, native-crash and app not responding counts with occurrence times.
  The earlier declaration omitted these reports. Reconfirm the answers against the final sealed AAB.
- [x] The owner approved the BCM compliance URL, uninstall wording, retention wording, and current
  Internal Testing legal copy; the decision manifest records the approver and approval time.
- [ ] Before a later Play review stage—not Internal Testing—deploy any required reviewer access,
  provision a durable synthetic reviewer study, and replace the reviewer-instruction marker.
- [ ] Refresh all bound hashes, set all
  remaining approval statuses plus `submission_status` to `approved`, and set
  `release_candidate_id`.
- [ ] Run `scripts/verify-store-readiness.sh play`; then build the `openRelease` AAB from a fresh
  detached checkout and run `scripts/play-upload.py check` on it. Keep `app-open-release.cdx.json`
  beside the AAB: the check refuses a bundle without the SBOM that names its SHA-256, and both are
  archived together.

## Final Play delivery and assets

- [x] Stale Health Connect screenshots were removed so they cannot be submitted accidentally.
- [x] The exact sealed version `55` AAB from GitHub Actions run `33107210111` was uploaded to
  Internal Testing without rebuilding it. Google Play returned the matching SHA-256
  `e8f613f969e146360c2d6f6ae790947d4e968d311cc4f579719831c9d644ccce`; a fresh API read-back
  confirmed completed release `2026.08.27-internal.2` containing only version code `55`.
- [ ] Install through Internal Testing and bind Play's artifact ID, track, installed split paths and
  hashes, signer, package/version, and embedded RC ID to the sealed receipt.
- [ ] Rerun the complete delivered-split verifier with `--skip-build`,
  `--verify-play-delivered`, `--prior-sealed-receipt`, and the original
  `--release-authority-sha` against the Play-installed package; then run the runtime egress proof.
  Never rebuild the AAB or accept an unattested parent receipt during this post-upload phase.
- [ ] Capture the required phone, 7-inch, and 10-inch screenshots from that same delivered candidate
  and synthetic test environment, following `assets/screenshots/README.md`.
- [x] Accessibility API declaration is not applicable: the submitted `openRelease` artifact contains no
  AccessibilityService. See `accessibility-declaration.md`.
- [ ] File the specialUse foreground-service declarations and demonstration videos for both
  `DeviceUnlockMonitoringService` and `HardwareSensorService`; follow `foreground-service-declaration.md`.
  Record each Console filing date and video URL here: pending owner filing.
- [ ] Submit the declarations/listing copy and mechanically compare them with the approved registry;
  the open artifact includes both foreground services and excludes Accessibility, the notification listener,
  audio collectors and Health Connect.
- [ ] Internal test and Play pre-launch report pass with every finding triaged before rollout.

## Qualification still to run last

- [x] A diagnostic exact-AAB-derived split matrix clean-installed and cold-launched AAB
  `6fd9787dbfdb76858d7e3e786f9299d4cfd6fa6f29ea743afda1f4f161103cba` on API 23,
  26, 30, 33, 34, 35, and 36 with byte-equal pulled splits. Receipt:
  `app/build/play-aab-evidence/20260822-api-matrix/matrix-receipt.json` (SHA-256
  `e9380d7d98f1e2203418f23e7484ad498f63db0bbd5e38b5bbf4bb357d73c826`).
- [x] A later current-source GitHub matrix passed API 23, 26, 33, 34, 35, 36 and Amazon AOSP API
  25/30. The API 30 Play job lost ADB while booting a cached AOSP image and the run was cancelled;
  no Chronicle flow ran in that job. The workflow cache key is bumped so the next committed run
  downloads a fresh image. This run is infrastructure evidence only, not final sealed evidence.
- [ ] Repeat that API 23/26/30/33/34/35/36 matrix against the one sealed final AAB; diagnostic
  evidence never substitutes for final artifact identity.
- [ ] Run the final sealed-artifact offline/retry, reboot, update-preservation, uninstall/reinstall,
  containment/reconnect, and recovery matrix against the authorized synthetic test environment.
- [ ] Run the disposable predecessor upgrade/restore matrix only after a real predecessor artifact
  exists.
- [ ] Obtain independent final source/security/data-flow, Android artifact/Play, and selfhost
  recovery reviews with no unresolved P0/P1 finding.

## Explicitly deferred

- [x] Health Connect and every nonrequired risky collector are excluded rather than left dormant.
- [x] Physical-device qualification is deferred until the owner supplies/authorizes the device.
- [x] Live production deployment and staged rollout are separately authorized future work, not part
  of this non-production qualification.
