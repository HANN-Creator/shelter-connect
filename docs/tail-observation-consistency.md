# B-96: consistent tail observations from exact shape evidence

Branch: `backend/b-96-tail-observation-consistency`, based on main `d46259e`.

Notion synchronization is pending: this session exposes no Notion connector or browser interaction tool. Existing B94 criteria and B95 results were read from the repository. This document records the new scoped work without claiming an external card update.

## Completion criteria

- Replay the actual black dog's two stored observations without generating replacement sprites.
- Reconcile only COMPLETE_CONNECTED vs SHORT_STUB with connected attachment, distinct contour and visible tip when both original side views have exactly mirrored alpha masks and their own intact edge/component/rear-branch evidence.
- Preserve raw observations, measured source hashes and decision provenance. A dissimilar, missing, detached, clipped, ambiguous or both-stub tail must not inherit the other view's pass.
- Keep the actual changed cream rear-tail motion rejected. Do not turn uncertainty into a negative training example.
- Validate production code with recorded observations, adversarial regressions, real Luna fixed inputs, local tests and CI before merging. Do not claim remote recheck, approval or deployment that was not executed.
- No image edits, extra PixelLab generations, DB migrations or repair-budget changes.

## Two distinct review errors

The deployed black dog's source photo and all four seed images are retained in the evidence chain. Both stored tail observations label one side COMPLETE_CONNECTED and the other SHORT_STUB, although the side alpha masks are exact mirrors. Each view independently has a connected body, clear borders and rear-branch support. The new resolver reconciles only this contradiction, preserves both raw labels, and records the source hashes and original decisions. It cannot supply missing tail pixels, resolve two short-stub labels, waive a general-review conflict, or turn uncertain observations into negative lessons. Approval replays the same proof and its bindings.

The first real v42 evaluation passed all five positive cases and blocked four of five negative controls. It incorrectly passed the cream rear IDLE: outer alpha is unchanged, but the internal tail contour changes from the approved seed to frame zero. That failed evaluation remains under `backend/scripts/fixtures/tail-observation-v42/live-v42`; it is not relabeled or replaced by a favorable reroll.

The v43 input correction measures internal, high-contrast source/frame-zero differences only when their full alpha masks align exactly. Four or more qualifying pixels add a nearest-neighbor detail of the same native region in both images. The full references and all nine actual frames remain present. This is evidence for the normal reviewer, not a numeric defect rule: shading is still allowed, and source-to-entry changes are not described as temporal tail wagging. No image, model, repair budget or approval threshold is changed. New generation and recheck routes share these inputs.

## Verification scope

- The fixed live evaluation has five positive and five negative cases, chosen before calls. Normal dogs span raised, straight, compact dark and light tails. Negative controls include a missing tail, two missing tails, clipping, a disconnected pixel and the actual changed cream rear-tail motion.
- The black dog uses the complete production base-review path with the exact previously deployed photo/seed hashes. Cream uses the production motion-review path. The other eight cases isolate tail anatomy, reuse archived photo observations, and never send a sprite as a photograph; their photo binding is a fixture sentinel, not publication authorization.
- Request intents, raw responses and final reports are retained. A completed failed evaluation is not rerun under the same inputs to obtain a desired pass. The v43 evaluation changes the documented comparison inputs and rules hash; v42 receipts remain intact.
- These labels are agent visual assessments, not independent human gold. A fixed-case pass does not establish universal model accuracy. Unsupported low-tail geometry and unrelated anatomy uncertainty remain conservative limitations.
- No remote recheck, server approval, publication or Render deployment is implied by local validation.

## Recorded actual outcomes

| Evaluation | Positive cases | Negative controls | Result |
| --- | --- | --- | --- |
| v42, original motion comparison | 5 passed | 4 blocked, cream false pass | 9/10; failure retained |
| v43, aligned entry comparison | 5 passed | 5 blocked | 10/10 |

The v43 cream review independently reports the same `referencePose` and `tail` failures twice, without movement/identity uncertainty. The actual missing-tail and clipped cases are confirmed defects. The disconnected-pixel case remains UNCERTAIN and cannot publish or become a negative lesson. Black passes the complete production base-review route with the exact original photo and four seeds; none were regenerated.

Current rule SHA-256: `eddc439488293d4286a5bbd56638398154c6df2c13b26711940872afda7093c3`.
Both live receipt sets and their hash manifests are retained in `backend/scripts/fixtures/tail-observation-v42/`. Offline replay and synthetic boundary checks remain distinct from these actual provider outcomes. The PR records the full Java/PostgreSQL/Python/package/CI results. No new environment variables or DB migration are needed.
