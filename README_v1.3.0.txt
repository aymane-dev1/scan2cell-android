Scan2Cell Android v1.3.0 — Fraud Check

WHAT CHANGED
- Receipt Mode scans 5 values FROM THE PAPER:
  1. N° Trésorerie (top boxed code)
  2. Nom & Prénom
  3. N° Contrat (left bottom number)
  4. Tiers / Réf. (right bottom number)
  5. Montant
- Contract and Tier are never replaced by Excel/database values.
- The review screen lets you correct all 5 values before sending.
- A Swap Contract / Tier button is available if OCR reverses the bottom pair.

COMPATIBILITY
No PC bridge or Excel add-in change is required.
The already-working Reçus add-in has only one Contract column, so the app sends:
  contract|tier
for that technical column. The new Excel workbook splits it automatically.

EXAMPLE
Paper:
  0147UDAS
  BAKHANE MALIKA
  00000234329 / 00006665874
  487,00

Sent to Reçus:
  Treasury: 0147UDAS
  Name: BAKHANE MALIKA
  Contract technical field: 00000234329|00006665874
  Amount: 487,00

BUILD
Upload/replace these project files in the same GitHub repository and push to main.
GitHub Actions will build the signed Scan2Cell-v1.3.0.apk.
The same embedded signing key is kept, so it can update the existing installed app.
