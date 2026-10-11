# Diagnostic label / formatter mismatch

The deployed v2 idle-north repair stopped before a PixelLab submission. Its three unmodified drafts were 1251, 1226 and 1289 characters, within the 2000-character budget. The only unsupported character was `_` in `ACTION_MISSING` / `IDLE_MOTION`, copied from the server-supplied findings. All three attempts incorrectly received generic "too long or malformed" feedback.

`evidence.json` preserves the actual input, all two learned rules, failure history and hashes. No credentials, signed photo URLs or images are included. The v3 regression accepts these labels as ordinary text for formatting only; semantic acceptance and image quality are separate checks. The writer and verifier treat issue codes as diagnostic metadata and must preserve actual visual corrections and frame scopes. Unsupported character feedback is explicit, with raw drafts retained rather than silently sanitized.

The opt-in `StyledPromptFormatLiveTest` replays this exact 2000-character repair input and the existing 1000-character first-generation fixture through the production writer + independent semantic verifier, retaining every request and response. It buys no images and makes no image-quality claim.
