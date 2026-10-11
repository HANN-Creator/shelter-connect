# Complete prompt compaction regression

`evidence.json` retains the deployed B90 first-generation and failed repair inputs, all four learned rules, last saved draft/verdict, and input hashes. It contains no credentials, private photo URLs or images.

The first additive prompt fit (753 base characters, 247 remaining including the separator). The correction grew to 907 base characters; subtracting the 10-character separator left 83 for four rules. Its saved verdict rejected loss of the qualifier `approved`; the next format attempts failed before a paid correction was reserved. The old implementation did not save those invalid final drafts, so this fixture does not invent them.

`StyledLessonPromptAgentTest` reproduces the 83-character failure and requires a full 1,000-character replacement budget. Independent validation must preserve the base and every rule. Literal eyes/coat/direction/canvas anchors prevent an overly optimistic model verdict from dropping those terms. Invalid draft diagnostics and prior prompt rows remain recorded.

`StyledFullPromptLiveTest` is opt-in (`FULL_PROMPT_LIVE_APPROVED=true`, `FULL_PROMPT_LIVE_OUTPUT`, `OPENAI_API_KEY`), uses the production Luna agent/composer, and saves request intent before each text call. Unknown outcomes cannot be silently repeated. It never generates images, edits a deployed job, or asserts image quality.
