# B-91: direction-invariant tail measurements

- Notion: https://app.notion.com/p/3f65b2d1a55f8053828ddad04153c199
- Branch: `backend/b-91-tail-mirror-invariance`
- Prerequisite: B-89 / PR102 / main `e2865f9`; found during the B-90 deployed verification.

## Actual failure and cause

The v40 deployed job `e1e0b869-8914-4f9e-8865-4e38b6a04f5d` generated a new cream puppy from public-source dog `441553202603074`. Both real AI observations described complete, connected side tails with distinct contours and visible tips. All four general appearance views passed. The geometry detector nevertheless reported west=false/east=true, so BASE stayed `SEED_REVIEW / SEED_OBSERVATION_UNCERTAIN`; all twelve motions remained pending.

The exact stored west/east alpha masks are horizontal reflections. Zhang–Suen thinning uses two directional deletion phases: thinning mirrored pixels does not necessarily yield a mirrored skeleton. Rear-branch traversal consequently lost the short raised tail only in WEST. The previous fixture suite checked reflection symmetry for different silhouettes and missed this input. This is a measurement defect, not a reason to regenerate the dog or lengthen its tail.

`backend/scripts/fixtures/tail-mirror-v41` preserves the exact four PNGs, both original observations, the failed v40 report and SHA256 manifest. The run submitted one PixelLab character request (10 completed Generations) and zero repairs. It neither approved nor published the dog. Temporary development STAFF membership/login were removed; private evidence and prior B-87 approved images were preserved.

## Change

`rear-silhouette-branches-v3` normalizes a copied measurement mask to EAST-facing before thinning: WEST's own mask is reflected, EAST's is unchanged. Candidate coordinates are mapped back to the same original view. Each view supplies its own evidence; a good opposite tail cannot substitute for a missing tail.

Source PNGs, color, scale, art style and actual generated orientation are untouched. Edge contact and connected components are measured on the originals. Branch length, central-body connection, foot-band exclusions, categorical AI evidence and uncertain/defect gates retain their thresholds. This detector remains conservative corroboration, not semantic tail segmentation; an unsupported descending tail remains uncertain.

Shared server/CLI quality rules advance to v41, recording the regression and measurement version. Existing complete-tail generation guidance still applies to initial and corrective requests; it is not made more aggressive to compensate for a detector error. The server uses the corrected measurement for both newly generated and repaired/rechecked seeds. The local CLI retains its existing reviewed-input flow; it does not acquire an independent Java anatomy detector through this change.

Historical reviews are immutable and cannot authorize under the new rule hash. B-89 revalidates previously passing lessons under the new policy before reuse. No API, DB migration, environment setting or repair-budget change.

## Validation and remaining work

The new exact-pixel regression failed on v40 and passed after correction. Existing raised/curled/small-tail, missing-tail, clipped, detached, paw-confusion, spine-connection and unsupported-descending-tail regressions remain active. Reflection cases include the previously omitted clipped and detached fixtures; new checks validate original-coordinate paths, both real observations, unchanged hashes and refusal to borrow the other view's evidence.

Local offline Java tests: 602 passed, 24 opt-in tests skipped, zero failures; `bootJar` passed. Python pipeline/harness: 112 passed; motion renderer: 26 passed. CI covers disposable PostgreSQL integration, dependency security, offline deployment build and the 512 MB container; its final result is recorded in the PR and Notion before merge.

A fresh full production `StyledSeedQualityAgent.reviewRecovery` call on the exact stored photograph and four unchanged sprites passed: BASE PASS, tail PASS, issues empty. It uses current rules SHA256 `657da19999b7bb49cd099cf759b5d2de6597e3f3d0086185cde9ccd05068a4b0`, with source-photo and sprite input hashes checked. `v41-actual-review.json` preserves this actual new AI result separately from the failed v40 observations. No PixelLab generation, source edit, database approval or remote job mutation was involved in this local review. Replaying stored observations is not a new model review or a remote approval. B-90 remains in progress: after this fix is deployed, the same private job needs an authenticated recheck followed by the twelve motions, automatic approval and app playback. No direct DB approval or replacement animal is used to hide the failure.
