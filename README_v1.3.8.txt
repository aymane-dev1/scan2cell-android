SCAN2CELL ANDROID v1.3.8 — THIRD RECEIPT FORMAT

Supported receipt types:

1) NORMAL PID
   Example:
     00000234329 / 00006665874
   -> scanned as two numeric codes.

2) PSD GROUP
   Example:
     J1ME0000303
   -> one PSD code is sent in BOTH Contract and Tier.

3) PSD INDIVIDUAL MEMBER
   Example:
     CWSI0000329 / 00007060554
   -> BOTH paper codes are preserved.
   -> the phone does NOT guess which one is Contract.
   -> Excel v3.9 checks both scanned codes against the matched base row and
      automatically selects the one that is really Num Contrat.

Important OCR improvement:
- Real alphabetic prefixes are preserved.
- CWSI0000329 stays CWSI0000329 (the I is NOT changed to 1).
- J1MEO000303 can still be corrected to J1ME0000303 when O is clearly inside
  the numeric tail.

Valider:
- unchanged; it copies the matched BASE_SIMPLE line.

Les annulés:
- unchanged; it routes by N° Trésorerie and BASE_FULL Statut Opération = N.

Version:
  versionCode = 138
  versionName = 1.3.8-psd-member-support

Install over the current APK; same package/signing key are preserved.
