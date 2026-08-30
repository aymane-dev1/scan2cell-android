SCAN2CELL v1.1.1 — LAUNCH CRASH FIX

This update targets the crash that happens immediately after tapping the app
icon, before the camera or receipt scanner starts.

Changes:
- Replaced the main screen's Material Card/TextInput inflation with stable
  standard Android widgets.
- Kept the clean Scan2Cell design using lightweight drawable backgrounds.
- Delayed the non-essential PC connection check until after the first UI frame.
- Added a startup crash fallback: if startup throws again, Scan2Cell stays open
  and prints the exact exception on screen instead of disappearing.
- Same package name and same signing key. Install over v1.1.0; do not uninstall.

GitHub:
Upload all files from the PATCH ZIP, replace existing files, commit, wait for
Actions to turn green, then download Scan2Cell-v1.1.1.apk.
