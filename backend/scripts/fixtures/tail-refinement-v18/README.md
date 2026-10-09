# B-70: localization uncertainty must not erase an independent defect

These unchanged four PNGs and `deployed-review.json` came from private v17 canary
`cfeed2f7-e904-4f72-a5ff-672c9b9b2e4a`, public animal `447505202600916`.
The original photo, credentials and signed storage URLs are excluded.

The server automatically edited EAST after the first review. SOUTH/NORTH/WEST
were byte-preserved. The next general review correctly reported TAIL_CARRIAGE
on WEST and EAST, both confidence 0.94: WEST has a small horizontal nub while
EAST has a raised curved tail. Independent tail observation claimed a complete
EAST tail but supplied coordinates crossing transparent pixels. v17 merged that
localization error into the general verdict and lowered EAST confidence to 0.74,
which incorrectly stopped the confirmed mismatch repair as UNCERTAIN_VERDICT.

v18 replays the immutable raw `generalPropertyReview` and tail `observation`
against these native pixels. It requests at most one additional observation on
the same images with measured alpha geometry, preserves both replies, and does
not equate bad coordinates with a missing tail. A confident independent defect
remains eligible for selected repair. Unresolved tail evidence still prevents
approval; every repaired image needs a fresh full review.

Binding: `3794b6e44555a50f392725845b78c4fd2243da98dfbae3f9f9e978f25fc814e1`.
The two paid PixelLab attempts and original QA remain private in the deployment
check output. Twelve motion steps were not started. This fixture does not
certify motion quality, final approval, or frontend playback.

`live-refinement-review.json` preserves one actual Luna refinement of the stored
bad observation. EAST's path becomes completely opaque and connected, but its
confidence is 0.72, so approval correctly remains blocked. The independent
0.94 TAIL_CARRIAGE findings remain repairable. The call used the approved local
original photograph (digest `c415eabbd5f425a59c805a494d435175ee4eb569ee7a2032d42fb1c1b4661a16`),
not the server re-encoded photo; neither digest is substituted for the other.
The saved draft v18 rules digest remains unchanged. Subsequent edits only to
generation/repair prevention text changed the complete rules hash, not the
tail observation request. This historical response is replayed for regression,
never used to approve a new production job under different rules.
