Scan2Cell Android v1.3.5 — PSD Group Receipt Support

SUPPORTED RECEIPT TYPES
=======================
1) Standard / PID receipt
   - Treasury: top boxed N°
   - Contract: left bottom ID
   - Tier: right bottom ID
   - Amount
   - Name when present

2) Group / PSD receipt
   Example paper:
     N° 0141V6RZ
     Montant reçu: 2 119,00
     PSD  J1ME0000303 /

   The app automatically treats the ONE PSD code as:
     Contract = J1ME0000303
     Tier     = J1ME0000303

   The client-name field is optional/blank for PSD receipts.

EXCEL / WORKFLOW COMPATIBILITY
==============================
No Excel formula change is required for the current v3.7 workflow:
- RECU_SCAN receives B7 as: J1ME0000303|J1ME0000303
- The visible Contract check uses the left value and compares it with BASE_SIMPLE.
- VALIDER still copies the matching full BASE_SIMPLE row into Travaux.
- LES ANNULÉS AUTO still classifies by Treasury and copies the full BASE_FULL row
  when Treasury is absent from BASE_SIMPLE and Statut Opération = N.

INSTALL
=======
Replace your existing Android GitHub project with this project and push to main.
GitHub Actions builds Scan2Cell-v1.3.5.apk.
Install it over the current app; package/signing key are unchanged.
