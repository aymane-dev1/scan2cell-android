SCAN2CELL v1.2.1 — RECEIPT OCR FIX

Fixes:
1. Amount no longer has to be on the exact same OCR line as "Montant reçu".
   Scan2Cell scores all money-looking values and strongly prefers the value
   next to / immediately after the Montant label.
2. Common OCR mistakes such as 487,0O and 487.OO are normalized.
3. Name parsing is more tolerant of ML Kit splitting "Nom client" and the name.
4. The two long PID/reference codes are detected more strictly around PID/Réf.
5. Do NOT trust left/right as Trésorerie/Contrat by itself. PC/Excel v1.2.1
   tests BOTH orientations against BASE_FULL + BASE_SIMPLE and keeps the
   orientation that actually matches your database.

Install over the existing APK; do not uninstall.
