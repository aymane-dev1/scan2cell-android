SCAN2CELL v1.1.2 — STABLE STARTUP ROLLBACK

Why:
v1.1.1 proved that saved pairing was NOT causing the launch crash.
This version restores the exact v1.0.1 main-screen structure that was already
working on the same phone, then adds only one Receipt Mode button.

What remains:
- Existing local pairing / bridge behavior
- Single smart scan
- Receipt OCR / review screen
- Automatic 4-field receipt send to Excel

What changed:
- Removed the experimental v1.1.x main-screen redesign
- Restored the proven v1.0.1 startup layout
- Added only “Scan receipt • 4 fields”
- Network verification starts after the UI is already visible
- Compact startup error remains only as a fallback

Install over the existing app. Do NOT uninstall it.
