# Health Apps declaration

## Health Apps declaration

- Declared category: **Human subjects research — Research studies, clinical trials, and patient
  communities**. Chronicle is participant-facing software used by qualified researchers to collect
  consented research data under the applicable Institutional Review Board, Ethics Committee, or
  equivalent determination.
- This release does not collect Health Connect records, sleep, or physical-activity recognition and
  requests none of their permissions. It can collect motion and device sensor samples when a study
  requests them. It does not diagnose, treat, or give medical advice.
- Every study must maintain its applicable review-board determination and participant consent
  covering the research nature, purpose, duration, procedures, foreseeable risks, expected
  benefits, confidentiality and sharing, participant contact, and withdrawal process. Proof must be
  available to Google Play on request.
- Store listing disclaimer: use the non-medical-device disclaimer in `listing.md` exactly.

## Health Connect record declarations

Declare none. `scripts/play-upload.py check` refuses an AAB that requests any Health Connect
permission not listed in `privacy.properties`, and this release lists none.
