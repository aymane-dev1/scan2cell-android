Scan2Cell Android v1.3.6 — PSD OCR + Faster Receipt Scan

Changes
-------
- PSD/group receipt: Nom & Prénom field is hidden completely.
- PSD Contract and Tier / Réf. are BOTH editable. Editing either mirrors the value to the other.
- PSD OCR fixes common O/0, Q/0, I/1, L/1 etc. mistakes ONLY in the numeric suffix.
  Example: J1MEO000303 -> J1ME0000303.
- Treasury OCR also corrects digit-like mistakes in the first numeric prefix.
- Faster receipt pipeline: Treasury + Contract + Amount are the required fraud-check fields.
  The app no longer spends extra OCR passes chasing the optional Tier on normal PID receipts.
- Maximum OCR path reduced to full-page + bottom pass + one contract fallback (no right-side Tier pass).
- Successful send message is now simply: Receipt sent to Excel.
  It no longer displays Excel's address/result such as INTROUVABLE in the Android bottom message.
- Existing Valider and Les annulés logic remains compatible.

PSD send format
---------------
If the group code is J1ME0000303, Android sends:
  Contract = J1ME0000303
  Tier     = J1ME0000303

Build
-----
Replace the existing GitHub Android project with this project and push to main.
GitHub Actions produces Scan2Cell-v1.3.6.apk.
The package name and signing key are unchanged, so it installs over the current app.
