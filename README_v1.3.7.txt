Scan2Cell Android v1.3.7 — Treasury Reliability Fix

WHY THIS VERSION EXISTS
-----------------------
v1.3.6 added an aggressive character correction to the N° Trésorerie parser.
That was a regression: valid treasury IDs could be changed while OCR was being
"corrected".

v1.3.7 removes that behavior completely.

TREASURY OCR NOW
----------------
1. Full receipt OCR uses the older, safer treasury parser.
2. A small high-resolution OCR pass reads ONLY the top receipt header / N° box.
3. Full-page and top-box results are compared.
4. No S/G/T/B/etc substitutions are applied to treasury IDs.
5. The only special tie-breaker is when the two OCR passes disagree only on a
   leading O/Q versus 0; the version actually read as numeric 0 wins.
6. The dedicated top-header result is preferred over unrelated full-page codes.

PSD / GROUP RECEIPTS
--------------------
PSD-specific zero correction is still kept ONLY for the PSD code, where the
numeric tail is expected. It is no longer allowed to affect N° Trésorerie.

KEPT FROM v1.3.6
----------------
- PSD/group receipt support.
- One PSD code -> Contract + Tier.
- Contract/Tier editable for PSD.
- PSD name field hidden.
- Faster Contract + Amount path.
- Phone send message no longer says INTROUVABLE.
- Existing Excel Valider / Les annulés workflows remain compatible.

VERSION
-------
versionCode 137
versionName 1.3.7-treasury-reliability-fix

INSTALL
-------
Replace the contents of the existing Android GitHub project with this project,
push to main, build the APK, then install it over the current Scan2Cell app.
The existing package/signing key are preserved.
