# B-69: a reported full tail must exist in the native pixels

These four exact BASE PNGs came from private B-68 canary job `f96d002e-1055-495e-b12f-3d44eb740c0a` (public source animal `447505202600916`). The job was cancelled and its source permission revoked after the false PASS; it was never published. Source-photo files, credentials and signed download URLs are excluded.

- `deployed-seed-review.json`: unchanged v16 general review, incorrectly PASS on all four views.
- `independent-observation.json`: independent actual Luna observation identifying the small/absent side tails. It is diagnostic evidence, not the v17 schema.
- `live-deployed-review.json`, `live-selected-review.json`: immutable actual `gpt-5.6-luna` replies under v17 with coordinate-grid observations. No expected result or prior verdict was sent to the model. The selected positive PNGs are the unchanged `../selected-seed-repair-v16/selected` assets.

Replay `StyledSeedTailEvidenceTest` against these exact PNGs and saved observations without a paid model call. A textual `COMPLETE_CONNECTED` is insufficient: validate the supplied path against native opaque pixels, including intermediate pixels between nearby waypoints. WEST/EAST on the negative fail; the known complete-tail positive passes. Raw `pixelAudit` on the saved positive used the original strict adjacent-point checker; current replay recomputes that audit from the immutable raw observation and exact PNGs, rather than editing the model reply.

The first text-only v17 observation still false-passed the negative. Its private evidence remains in the local verification output. A grid/path requirement was added after that failure. A model category can still be imprecise (the negative EAST says cropped although its border is clear); this test asserts a discernible tail, not agreement with every word in a model note.

Bindings: negative seed set `62a94cfecc4b52f82d8eabf9e16344b5d2ee03177231f7bbfb89f5858a747bf5`; positive set `617d4c6b0622cea4582d341772a4dbed673c6c79a0ecf4bbe83af14a3a7ea396`. The hosted v16 source was re-encoded; its photo digest differs from the original photograph used by the independent live replay. Neither digest is substituted for the other.

This is BASE-only evidence. It does not certify all 12 motions, final publication or frontend playback, and does not guarantee perfect semantic tail localization on every dog.
