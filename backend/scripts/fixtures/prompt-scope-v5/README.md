# B97 complete prompt scope regression

`evidence.json` is the completed v4 prompt failure from the deployed v43 black-dog
job, label `idle-west`, input hash
`aed49d90ac05d7aa5902e4aa6b4eb5f5551c18289c567aa3a86b61d7fd324343`.
It retains the entire base prompt, all five active lessons, all three drafts and
all three semantic verdicts. No image was submitted for this failed attempt.

`live-false-positive.json` preserves the first local v5 initial-composition result
whose AI audit passed even though the restoration clause was missing. The manual
source audit rejected it. The regression test now rejects the same draft despite
the optimistic model response; the writer and verifier require source-derived
`Restore ...` clauses verbatim, alongside the existing identity/direction anchors.

`live-validation.json` contains actual Luna request bindings, completed responses,
and the independent audit after that guard was added. It covers both fresh
composition of the same full input and correction of the saved failed draft.
The final texts are 990 and 989 characters respectively; each retains all five
lessons within the original 1000-character budget. Each path used two completed
writer responses (one length correction) and one independent audit.

These are text-only provider checks. They neither purchase PixelLab images nor
change a deployed job, repair budget, source image, quality rule, or approval.
They do not prove final animation quality or app playback. Tests are
`StyledPromptScopeTest`, opt-in `StyledPromptScopeLiveTest`, and the prompt-resume
PostgreSQL integration tests. Live calls are disabled by default and replay saved
receipts instead of repeating a completed request; unknown outcomes are blocked.
