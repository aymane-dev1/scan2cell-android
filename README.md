# Scan2Cell v1.1.0 — Receipt Mode

## New receipt workflow
Take **one photo of the full receipt**. The app looks for:

1. N° Trésorerie — first long numeric reference on the PID/reference line
2. Nom & Prénom — value after `Nom client`
3. N° Contrat — second long numeric reference on the PID/reference line
4. Montant — value after `Montant reçu`

The four fields are always shown on a review screen before sending. Missing fields stay blank; the app does not invent values. There is also a **Swap IDs** button in case the two long references need the opposite business meaning.

Receipt mode sends to the Excel **Reçus** register automatically. Single-value mode still sends to the selected cell.

## Build
Upload the full project to GitHub. Actions builds `Scan2Cell-v1.1.0.apk` using the same included signing key as v1.0.x, so it can update the installed local version directly.
