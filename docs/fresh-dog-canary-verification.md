# B-88: fresh deployed dog canaries

- Card: https://app.notion.com/p/3f55b2d1a55f808aa435d181cc6623b0
- Branch: `backend/b-88-fresh-canary-evidence`
- Deployed baseline: B-87 / PR100 / expected main `a18fa2d16bbe778f5fa80f440fcbda924035acab`.
- User-reported deployment: `dep-db57csbrjlhs73cme8bg`. Exact Render revision was not independently inspected because the dashboard remains blocked by site permissions.
- Actual job policy: `sprite-regressions-2026-10-11-v38`, SHA-256 `18305e689493945343289c3ee52547b602d6ae0d0aee32b9e1a7e9f37ab2e8a9`.

## Scope and observations

Three fresh public-source photographs were visually inspected, with phenotype descriptions and face rectangles supplied through the current explicit styled-assets contract. This verifies automation **after reviewed feature input**, not an unattended public-data ingestion/photograph-analysis service. Each dog stays private in the development test shelter.

Each initial request selected IDLE, WALK and SIT in four directions. Duplicate requests returned the same job ID. The server, not a manual approval request, approved the brown puppy and brown adult's four BASE directions. All motions and bounded corrections completed through the original queued job. No prior dog, budget or approval was reset.

| Public animal ID | Input | Initial BASE result |
| --- | --- | --- |
| 441553202603075 | Brown puppy | SYSTEM seed approval |
| 426333202601103 | White small dog | FAILED / STYLED_ALPHA_INVALID |
| 442418202601131 | Brown curly adult | SYSTEM seed approval |

Both brown dogs' first IDLE/SOUTH results produced a moving appendage above the head. The server queued correction number 1, preserved the original reports and images, and independently passed both corrected clips without a manual repair or approval request. Both IDLE/NORTH clips also passed after one correction. Native frame contact sheets were visually inspected in addition to reading the AI verdicts.

The SOUTH corrections actually included the same newly activated TAIL_CARRIAGE lesson (`7961fea6-b4a0-47bf-a3b2-55581acbfd99`, candidate SHA-256 `7eff92e7e02df712d9e83eef0fea6039def9f7e49ce34498d065b7fd5133325a`). This proves automatic learning followed by reuse in corrective generation. Its validation still records `freshGenerationVerified:false`: this batch does not prove reuse of that new dynamic lesson in a later dog's first generation. Existing versioned prevention rules are supplied to first requests.

The puppy's WEST original added an appendage above its head; the first correction removed it but retained small tail-tip movement. Independent observations rejected that movement under the fixed-tail IDLE contract. This is distinct from missing anatomy or clipping, and its retry cost must remain visible rather than being described as another major anatomical defect. The run does not relax the contract or replace the provider output manually.

After all three WEST corrections were consumed, the server automatically produced and independently passed `approved-seed-idle-hold-v1` / `STATIC_IDLE`. All nine frames have the approved pose's identical hash. This is a stationary fallback, not a successfully repaired breathing/blinking animation, and is labeled separately in the comparison viewer.

Completed provider receipts, rather than pending zero-USD placeholders, are the usage source. The observed four-direction BASE requests each cost 10 Generations; an initial PixMiniMax clip costs 1 Generation; the observed animation edits cost 10 Generations each. All 43 completed receipts sum to 214 Generations: 30 for three BASE requests, 24 for initial clips, and 160 for 16 edits. These receipts do not measure Luna/OpenAI expenditure. First-attempt acceptance rate and correction count matter for a bulk rollout.

## Opaque provider output

The completed white-dog archive contained four valid-size 32×32 PNGs, each with all 1,024 pixels opaque. The original output visibly contains a white backdrop and an enclosing black rectangle. `no_background=true` and transparent-canvas instructions were already present in the request. Enabling that setting again is not a fix.

The exact four PNGs and hashes are preserved in `backend/scripts/fixtures/opaque-character-v38/`. The Java and Python codecs both reject them. A positive white-fur control confirms that white pixels themselves are not a reason for rejection. No color-keying, blur, redraw, forced approval, or new paid generation was used to make the fixture pass.

The production failure occurs in `StyledPixelLabClient.poll()` before `StyledAssetStore.checkpoint()` and AI quality review. Consequently this job has repairCount 0 and no AI report or lesson. **A bounded structural-recovery path is missing.** Retaining the regression does not implement that missing path or prove unattended completion.

## Regression verification

- Python: `python3 -m unittest test_styled_quality_regressions test_styled_dog_pipeline` — 55 tests passed.
- Java 21: `./gradlew --offline --no-daemon --max-workers=2 test --tests org.shelterconnect.api.asset.StyledSpriteCodecTest` — 8 tests passed.
- Registration revision: v39; only the regression catalogue changes. Generation/review prompts, thresholds and paid retry behavior are unchanged.
- Registration SHA-256: `35a6c945fbfedc3369df6df898ec38216cd2b4e926c8cddf52825a60b474ee96`.
- CI initially exposed three historic-fixture checks tied to v38. They now assert that archived QA is stale, then replay only an in-memory copy under current rules for decision testing. Original receipts and PNGs remain byte-identical; no production QA bypass was added.
- All three terminal jobs used deployed v38. No v39 deployment was performed.
- A later deployment of this catalogue revision would also change the runtime policy hash. Existing v38 learned rules and QA stay scoped to v38; this record-only change does not migrate or reactivate them for v39. No deployment is part of B-88. Rule compatibility/revalidation must be addressed with the structural-recovery change before a subsequent rollout.

## Final outcome

The three original jobs reached terminal states on 2026-10-11 KST. This is a completed verification run with a preserved provider failure, not proof that every input completes unattended.

| Animal | Final result | Motions | Correction attempts | PixelLab Generations |
| --- | --- | --- | --- | --- |
| 441553202603075 | APPROVED, BASE and final actor SYSTEM | 12/12, including WEST STATIC_IDLE | 10 | 122 |
| 426333202601103 | FAILED / STYLED_ALPHA_INVALID | No motions started | 0 | 10 |
| 442418202601131 | APPROVED, BASE and final actor SYSTEM | 12/12 | 6 | 82 |

- First motion review acceptance: 10/24 (41.7%). This tiny batch is not a population estimate. Repairs consume 160/214 observed Generations, so bulk rollout would still be premature.
- Both approved API manifests and all 24 downloaded sheets matched their stored SHA-256, 360×40 dimensions, nine 40×40 frames, and four direction mappings. Each dog was then rendered in the real React Native Skia iPhone 18 Pro/iOS 27 simulator: 108 frame slots, all four SIT final-frame holds, own-direction sheets, and no side mirroring. This used the authenticated private preview endpoint; public discovery was deliberately not enabled.
- The puppy's first app attempt used expired signed image URLs. Fetching a fresh preview with the same 12 hashes restored loading. No regeneration was involved. This verification harness refreshed the manifest manually; it does not independently verify the normal app's automatic URL refresh path.
- Both native runs used temporary instrumentation. All three existing frontend files were restored byte-for-byte; temporary signed manifests and the loopback frame collector were removed.
- Both dogs' clips were visually inspected separately from the AI verdicts. A stationary WEST IDLE is reported as such; it is not counted as a breathing/blinking animation.
- Final learning audit: 28 examples; 16 lesson candidates (12 ACTIVE, 3 REJECTED, 1 FAILED), with zero uncertain verdicts recorded as negative training examples. Actual corrective requests reused an active learned rule. No first generation in this batch recorded a newly learned dynamic rule; that later-input use remains unverified.
- Every dog remains private, anonymous reads return 404, and the previous B-87 approved job's aggregate hash is unchanged (`9f6e6d8d3098df9cb2fb2457f01bc75b711db7587dca3614590bfc5bf28af361`). Duplicate requests reused their original jobs. There was no manual approval, budget reset, replacement job, or bulk import.
- After termination, the exact temporary STAFF membership was revoked, the test app user disabled and test Auth login removed. The former token was rejected. Private images and failure evidence remain for inspection.
- Python 55 tests and the three affected Java classes' 18 tests pass. Full CI after the historic-fixture fix passes Java/Python/PostgreSQL checks, packaging and the 512 MB Docker smoke test; security CI also passes.

## Remaining implementation and verification

1. Implement bounded structural recovery for opaque BASE provider output before the AI checkpoint, preserving white fur and retaining failed originals. The new regression only establishes the current failure and valid controls.
2. Resolve rule-hash compatibility/revalidation before deploying v39. This record-only PR neither migrates v38 learned rules nor changes generation behavior; no redeployment is requested.
3. Wire unattended photo analysis into this explicit native32 contract, and verify a later dog's first generation actually receives a newly activated dynamic rule.
4. Reduce initial IDLE defects and edit cost. Verify normal app signed-URL refresh separately from this private preview harness.

Local evidence bundle: `output/b88-fresh-three-2026-10-11` in the task workspace, including final job/repair/provider receipts, usage summary, learning audit, downloaded manifests, hashes and native frame events. Credentials and signed manifests are excluded from the repository and final bundle.
