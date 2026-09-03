# Health Apps declaration

## Health Apps declaration

- Declared category: **Human subjects research — Research studies, clinical trials, and patient
  communities**. Chronicle is participant-facing software used by qualified researchers to collect
  consented research data under the applicable Institutional Review Board, Ethics Committee, or
  equivalent determination.
- The Play artifact does **not** collect Health Connect records, other health or fitness records,
  physical-activity or sleep recognition, or body-sensor data. Human-subjects classification
  describes the app's research purpose; it does not imply that these excluded collectors ship.
- The Internal Testing study remains limited to app usage, basic device telemetry, and unlock user
  identification. Bounded upload diagnostics are sent to the exact enrolled server after a failed
  research-data upload recovers. Do not configure
  or describe medical diagnosis, treatment, activity/fitness, sleep, or Health Connect functionality
  for this release.
- Every health-related study must maintain its applicable review-board determination and participant
  consent covering the research nature, purpose, duration, procedures, foreseeable risks, expected
  benefits, confidentiality and sharing, participant contact, and withdrawal process. Proof must be
  available to Google Play on request.
- Store listing disclaimer: use the non-medical-device disclaimer in `listing.md` exactly.

## Health Connect record declarations

Declare none. The artifact verifier must fail if the compiled Play manifest contains a Health
Connect permission, provider query, or rationale activity. Health Connect remains available only in
separate controlled research/open builds and is outside this Play submission.
