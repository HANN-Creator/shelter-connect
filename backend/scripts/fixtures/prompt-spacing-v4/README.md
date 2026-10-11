# Completed prompt length failure (B95)

The deployed brown-dog `idle-north` writer returned three complete responses:
1010, 1013 and 1041 characters for a 1000-character budget. All three failed
only `TOO_LONG`; PixelLab had not received this motion request.

`evidence.json` preserves the input, all learned rules, each original draft and
its precise format failure. Removing only ASCII spaces after punctuation and
before a letter produces 970, 974 and 1002 characters. The newest fitting draft
is attempt 2 (974). Attempt 3 must still fail; deleting its final two characters
is not an allowed recovery.

This fixture establishes format recovery, not image quality or semantic
acceptance. The independent Luna review must still cover the original base and
every rule before a description can be stored as READY or sent to PixelLab.
Original responses, selected attempt, transformation version, lengths and
before/after SHA-256 values remain in the prompt history. No visual quality
rule, approved image, generation budget or migration is changed.

Regression entry points:
- `StyledPromptSpacingRecoveryTest`: exact completed drafts, unchanged content,
  unsupported inputs, initial composition and semantic rewrite orchestration.
- `StyledAssetPostgresTest.promptSpacingHistoryAndAllRuleReviewPrecedeAnyProviderPurchase`:
  persistent audit, semantic rejection, no purchase before review and cache.
- `StyledAssetPostgresTest.promptResumePreservesImageAttemptHistoryApprovalRulesAndBudget`:
  older v3 pre-purchase failure can use the existing authorized resume contract.
- `StyledPromptSpacingLiveTest`: explicit opt-in text-only real Luna check,
  intent/response receipts, no repeat after an unknown API outcome.

`semantic-feedback.json` retains the first real Luna run after spacing recovery.
The gate rejected missing palette, then fixed head position, then a missing
explicit `normal` qualifier on blinking. These rejections remain blocking. The
writer now requests targeted edits while retaining verified clauses and source
qualifiers; the reviewer is instructed to list all omissions in one audit.
A passing bounded follow-up run must be recorded separately, not substituted for
this failed run or described as image-quality approval.
