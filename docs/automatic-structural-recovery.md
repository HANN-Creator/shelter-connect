# B-89: structural recovery, quiet IDLE and policy revalidation

> **과거 변경·검증 기록.** 현재 일반 작업은 [서버 에셋 기준](asset-pipeline-current.md)의 경고 정책을 따른다. 아래 차단·보완 설명은 당시 정책 또는 기존 작업의 호환 경로에 해당한다.

- Card: https://app.notion.com/p/3f65b2d1a55f80c2b4cfc3b3b907c782
- Branch: `backend/b-89-automatic-structural-recovery`
- Baseline: B-88 / PR101 / `663441d728af6e870b22ddecca497133c6fbcf3c`.

## Behavior

An opaque or empty 32×32 BASE used to throw `STYLED_ALPHA_INVALID` inside provider polling, before its result reached the checkpoint or repair worker. Bounded PNG decoding now permits quarantine only. The worker checkpoints the completed provider receipt, stores exact PNGs under `quarantined-seeds/<attempt>/`, and records per-direction transparent/visible pixel counts and the failure. This result cannot pass normal native validation or automatic approval.

New jobs with `base-alpha-restart-v1` regenerate from the original photo/concept and unchanged approved style using a different seed. No white color-key, blur, crop or background-erasing heuristic is applied. Recovery shares the original maximum three BASE repairs; it does not reset the budget or create a replacement job. Every new result must pass structural checks and a fresh full visual review before motion generation. Exhaustion retains the last quarantined result and fails privately. An uncertain paid submission stays `OUTCOME_UNKNOWN`; it is not automatically resubmitted. Existing ordinary audits remain byte-preserving.

First-generation and corrective IDLE prompts ask for tiny chest breathing, optional blinking, a fixed head/outline/paws/coat/tail, and no body bob or wag. On the first confirmed excessive-motion failure, a narrow recipe can restart PixMiniMax once from the exact approved same-direction seed instead of editing the defective strip. This includes agreeing confirmed unwanted tail motion, but does not adjudicate uncertain tail, identity, eyes, palette, facing, limbs or loop findings. The failed nine frames, observations and repair count remain in history. The new nine frames are independently reviewed. Further repairs, other defects and the separately reviewed static-IDLE fallback retain their existing gates and budgets.

## Learned rules and database

V24 adds a nullable `revalidated_from_id` self-reference and index to `styled_quality_lessons`. No existing example, candidate, asset, review or approval is rewritten by the migration.

On a policy change the learner queues a new candidate only from a previously independently activated rule. The prior rule remains stale. The new candidate retains its parent and source examples, checks current source permissions and immutable image hashes, then performs a blinded replay against the current immutable rules. Only a passing candidate becomes ACTIVE for the current policy and may enter **first generation as well as corrective** prompts. Historic source policy and current validation policy are recorded separately; `freshGenerationVerified` remains false because replay does not prove future generation quality.

One candidate per source/scope/current-policy prevents repeated purchases. Disabled sources are not revived. Invalid evidence, failed replay, interrupted model calls and uncertain outcomes never activate a rule or trigger automatic duplicate calls. Asset and lesson authorization checks still run immediately before transmission and persistence.

## Verification boundary

Actual B-88 opaque PNGs and initial IDLE observations are regression inputs. Local PostgreSQL tests use mocked providers/reviewers to verify receipt retention, paid-call bounds, restart/approval ordering, stale-rule revalidation, first-generation binding and permission revocation. They do not establish that a new real PixelLab image has acceptable quality or that costs have fallen.

No approved pack is changed and no bulk generation is started. Production HTTP schemas and environment variables are unchanged; the operations lesson response adds provenance fields. Apply V24 to the verified development database before deploying, since Render has `DB_MIGRATE=false`. Post-deployment actual generation, automatic correction/approval and app playback remain a separate verification step.

## Verified on 2026-10-11

- Shared policy: `sprite-regressions-2026-10-11-v40`; SHA-256 `1c939eb780e42879dfed531dd2f27bb9424e7bbeb1458741a8b068241a861243`.
- Local targeted Java regression: 33 passed, one optional test skipped. Disposable PostgreSQL regression and schema checks: 41 passed, zero skipped. Python script tests: 111 passed.
- The first full CI run caught the V1 bootstrap upgrade test's old V23/count expectation. It now expects the additional V24 migration, and the isolated bootstrap/upgrade/reapplication test passes locally. No migration or runtime behavior was weakened to satisfy the test.
- The real opaque fixture remains rejected by normal validation; recovery preserves it unchanged. The real IDLE fixture exercises the narrow restart recipe after an explicit in-memory policy rebinding; its historical AI result is not relabeled as a fresh review.
- Boundary cases include exhausted repair budgets, interrupted submissions, uncertain or unrelated IDLE defects, rejected policy replays, disabled parent lessons and revoked source permissions. The next new job uses a lesson only after its current-policy replay passes.
- Full GitHub CI and the development V24 application are recorded with the PR. No paid generation, deployment or app playback is claimed by these local tests.
- Development V24 was applied on 2026-10-11 with Flyway validation. Existing jobs, steps, lessons and examples retained identical content hashes (excluding the new nullable column); lesson RLS and runtime/public permissions were preserved.
