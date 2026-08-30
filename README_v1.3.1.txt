Scan2Cell Android v1.3.1 — Tier / Réf. OCR Fix

What this fixes
- v1.3.0 sometimes detected the first bottom number (Contract) but missed the second (Tier / Réf.).
- OCR often inserts spaces or reads digits as letters, e.g. 0000 666 5874 or G instead of 6.
- v1.3.1 accepts split numeric groups and more OCR digit confusions.
- It searches a wider area around Réf. / PID and the lower part of the receipt.

Expected review screen after scanning the example receipt:
Treasury : 0147UDAS
Contract : 00000234329
Tier/Réf.: 00006665874
Amount   : 487,00

IMPORTANT
Before pressing Send, look at the 5-field review screen. If Tier/Réf. is still blank or wrong, correct that field manually or retake the photo. Excel must compare what is physically on the receipt; it must never invent the scanned Tier from the database.

Build
Replace your GitHub project with this folder (or upload these changed files), push to main, and GitHub Actions will produce Scan2Cell-v1.3.1.apk.
The package/signing key is unchanged, so it installs over the current app.
