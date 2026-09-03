# Minimal Google Play release checklist

Current qualification status and exact evidence are recorded in the
[current handoff](../../../docs/handoff/play-selfhost-release-handoff-2026-08-26.md).
Older diagnostic hashes below are retained as historical evidence; they do not identify the final
candidate and must not be uploaded.

This checklist currently covers Chronicle Internal Testing: exact-server enrollment, informed
consent, app usage/basic device telemetry, unlock-based user identification, reliable upload,
and uninstall-based withdrawal from future collection. Health Connect, audio, interaction/accessibility capture, notification content,
activity/sleep expansion, location, and optional high-rate sensors are not part of this release.

## Merged source and diagnostic artifact

- [x] The August 26 independent Android/Play review found no P0 issue. Its implementable P1
  findings are addressed in the current `develop` change: participant-form/questionnaire runtime
  removal, withdrawal-safe enrollment monitoring, mandatory disclosed upload diagnostics,
  structured diagnostic minimization, physical 30-day pruning, and removal of unsupported legacy
  Firebase/Crashlytics claims. Final artifact/reviewer/Play-delivery findings remain gates below.
- [x] Android `develop` contains the reviewed minimal-public-artifact and sealed-submission work
  through `99c6d4f94b8dcb364ffbd7b98f5b5cb461e74822` (PRs #46 through #50).
- [x] The permanent package ID is `com.bcm.chronicle`; the current diagnostic candidate is
  version code `54`, target SDK 36. The final Play version/name still requires the Console
  ownership and maximum-version-code checks below.
- [x] A post-merge Play AAB was built from that exact source and independently verified:
  SHA-256 `6fd9787dbfdb76858d7e3e786f9299d4cfd6fa6f29ea743afda1f4f161103cba`,
  RC ID `minimal-play-merged-diagnostic-20260822c`, evidence directory
  `app/build/play-aab-evidence/20260822-post-merge-reminder-fixed-installed`.
- [x] A later pre-commit diagnostic AAB includes the final exact-origin Settings disclosure and
  verifier hardening: SHA-256
  `912453246e595f1081f21efd5fc02a6d9ffa197efcdaabda45c6e2f799902b9a`, RC ID
  `minimal-play-final-diagnostic-20260822d`, upload-certificate SHA-256
  `77A05B33A4BAA8D51137A491919556E4E836E5972227486E0AA574D2DEC2D76D`. It is
  diagnostic evidence only and must not be promoted in place of the sealed post-merge RC.
- [x] The current post-review diagnostic AAB rebuilt all Android source changes and passed unit,
  Play-release lint, bundle, and complete AAB verification. SHA-256:
  `8906a8c2340746313cdf3d7fad0dac68a32083764511efbe0b4997bd7c3eec9e`; receipt SHA-256:
  `60fad7d1870664c8a565814875a55c44719e8e4f45505b44842ffc439f5e8bb9`; evidence:
  `app/build/play-aab-evidence/local-post-review-final-20260826`. It is `UNSEALED`, diagnostic
  only, and must never be uploaded.
- [x] The verified AAB-derived split set was clean-installed on an API 33 emulator. Built and
  installed split-content manifests both hash to
  `b71ca03b2650134bca1c8e6a2f6c943c50a3da8f7986112589094fa748059d97`.
- [x] The verifier binds the AAB's embedded R8 mapping, complete delivered split set, package,
  version, RC ID, approved-registry hash, signer, permissions, components, classes, resources,
  native libraries, and prohibited strings. Artifact tests reject package replacement.
- [x] Installed verification has two explicit modes: local exact-AAB-derived splits require
  byte-identical APKs, while Play-delivered splits require the pinned Play app-signing certificate
  and identical normalized payloads. Normalization excludes only signing artifacts and retains
  runtime-relevant `META-INF`, resources, code, manifests, and native libraries.
- [ ] Do not promote the diagnostic AAB above. Build one sealed final RC only after the
  version/signing, policy-copy, and Play-account inputs are resolved. A public reviewer environment
  is not an Internal Testing gate.

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
  against the fixed `uzaira0/methodic` signer workflow and release-authority source SHA, proves the
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

## Minimal public artifact

- [x] Operational audio capture is absent from Play and Amazon: no audio collector, DAO,
  service, permission, consent/UI, persistence/upload path, delivered DEX class, native library,
  resource, or participant-facing audio copy ships in the verified artifact.
- [x] Health Connect, activity/sleep expansion, interaction/accessibility capture, notification
  listener/content capture, screen content, precise location, and optional high-rate sensors are
  absent from the public implementation and are rejected by artifact verification.
- [x] The approved public modules are exactly app-usage events, in-app activity class, device
  lifecycle, unlock user identification, battery telemetry, connectivity state, and device
  settings. Per-app network usage is excluded. Upload diagnostics use bounded encrypted local
  persistence, authenticated delivery to the exact enrolled study server, full acknowledgment
  before local deletion and 30-day retention. Uninstall removes app-local data but cannot notify the
  server or request server erasure. Their payload is limited to a
  closed issue code, count, times, optional HTTP status, and bounded exception class; it contains
  no free-form error text, server URL, credentials, or stack trace. Participant/device identity is
  supplied by the authenticated request and the receiving server is inherent in the connection.
- [x] The approved-module registry is the machine-readable authority for fields, permissions,
  retention, destination, upload behavior, and deletion behavior; tests bind it to runtime module
  permissions and the packaged registry hash.
- [x] Public Settings/Data Sharing hides excluded notification-listener, accessibility, and sensor
  controls rather than showing nonfunctional high-risk options.
- [x] Credentialed HTTP redirects are disabled, and enrollment accepts only a validated public
  HTTPS Chronicle origin with no URL credentials, query, or fragment.
- [x] Public Play/Open/Amazon builds contain no global mobile signing secret. A one-time invitation
  issues a scoped per-device credential used for subsequent authenticated calls.
- [x] R8 preserves the three reminder response types required by Retrofit/Moshi; the verifier and
  minified runtime proof cover their constructors, getters, and enum constants.
- [x] Notification permission denial/recovery, current accepted settings, local participant choice,
  reboot, enrollment state, and the minimal-artifact boundary all gate unlock monitoring. Uninstall
  removes the app and therefore stops the service.

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
- [x] Source configuration and a disposable local Caddy runtime historically proved anonymous
  `/privacy`, `/withdrawal`, and `/reviewer` entrypoints before dashboard authentication. This is
  compatibility evidence only: those routes and any current 401 response are not Internal Testing
  gates. The Play privacy-policy URL is the BCM compliance page.
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
- [x] Version code `54` was uploaded to Internal Testing on 2026-08-27, so the authoritative
  `maximum_uploaded_version_code` is `54`.
- [x] The owner approved the optimized replacement as version code `55`, version name
  `2026.08.27-internal.2`, and RC identifier `chronicle-play-v55-internal-20260827.2` for Internal
  Testing on 2026-08-27.
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
- [x] The Console Data Safety declaration was completed against `data-safety.md`, without Crash
  logs or legacy Firebase/Crashlytics installation/interaction data. The final sealed AAB audit
  must mechanically reconfirm these answers before submission.
- [x] The owner approved the BCM compliance URL, uninstall wording, retention wording, and current
  Internal Testing legal copy; the decision manifest records the approver and approval time.
- [ ] Before a later Play review stage—not Internal Testing—deploy any required reviewer access,
  provision a durable synthetic reviewer study, and replace the reviewer-instruction marker.
- [ ] Refresh all bound hashes, set all
  remaining approval statuses plus `submission_status` to `approved`, and set
  `release_candidate_id`.
- [ ] Run `scripts/verify-store-readiness.sh play --sealed`; then export the same approved upload
  certificate SHA-256 as `PLAY_UPLOAD_CERT_SHA256`, build the sealed RC from a fresh detached
  checkout, and rerun `scripts/verify-play-aab.sh` without `--allow-unpinned-cert`.

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
- [ ] Record the single `DeviceUnlockMonitoringService` foreground-service demonstration video if
  required by the Console declaration.
- [ ] Submit the declarations/listing copy and mechanically compare them with the approved registry;
  no Health Connect or AccessibilityService declaration should be present.
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
