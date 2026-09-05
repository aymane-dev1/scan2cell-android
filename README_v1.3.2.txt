Scan2Cell Android v1.3.2 - Geometry Tier Fix

Fixes the remaining Tier / Ref OCR issue.

What changed:
- Contract/Tier extraction now uses ML Kit bounding boxes (actual position on the receipt), not only OCR text order.
- The left bottom long ID is Contract.
- The right bottom long ID is Tier / Ref.
- Handles IDs returned on one line, separate lines, separate elements, or split by spaces/dashes.
- Existing review fields remain editable before Send.
- Existing Excel / PC bridge do NOT need changes.

Expected review for the sample:
Treasury: 0147UDAS
Contract: 00000234329
Tier / Ref.: 00006665874
Amount: 487,00

Install the generated APK over the current app. Same package/signing key; versionCode 132.
