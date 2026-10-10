# B-88: fresh deployed dog canaries

- Card: https://app.notion.com/p/3f55b2d1a55f808aa435d181cc6623b0
- Branch: `backend/b-88-fresh-canary-evidence`
- Deployed baseline: B-87 / PR100 / expected main `a18fa2d16bbe778f5fa80f440fcbda924035acab`.
- User-reported deployment: `dep-db57csbrjlhs73cme8bg`. Exact Render revision was not independently inspected because the dashboard remains blocked by site permissions.
- Actual job policy: `sprite-regressions-2026-10-11-v38`, SHA-256 `18305e689493945343289c3ee52547b602d6ae0d0aee32b9e1a7e9f37ab2e8a9`.

## Scope and observations

Three fresh public-source photographs were visually inspected, with phenotype descriptions and face rectangles supplied through the current explicit styled-assets contract. This verifies automation **after reviewed feature input**, not an unattended public-data ingestion/photograph-analysis service. Each dog stays private in the development test shelter.

Each initial request selected IDLE, WALK and SIT in four directions. Duplicate requests returned the same job ID. The server, not a manual approval request, approved the brown puppy and brown adult's four BASE directions. New motions and any bounded corrections continue through the original queued job. No prior dog, budget or approval was reset.

| Public animal ID | Input | Initial BASE result |
| --- | --- | --- |
| 441553202603075 | Brown puppy | SYSTEM seed approval |
| 426333202601103 | White small dog | FAILED / STYLED_ALPHA_INVALID |
| 442418202601131 | Brown curly adult | SYSTEM seed approval |

The puppy's first IDLE/SOUTH result produced a moving appendage above the head. Two independent observations confirmed action/stillness/tail defects and the server queued correction number 1 without a manual repair request. This is an observed automatic transition, not evidence that the final correction passed.

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
- The live jobs remain pinned to deployed v38. Do not deploy another rules hash while they run.

## Final outcome

Pending the original three jobs reaching terminal states. Successful packs require real downloaded manifest/hash/frame validation and playback; failures retain their raw evidence and specific outstanding work. Temporary STAFF membership and login are removed after all jobs stop. These operations are not yet claimed complete here.
