# Scan2Cell Local Final

This Android project pairs with the local Scan2Cell PC bridge over the same
Wi-Fi. It does not use ntfy or another public relay.

## Build on GitHub
Upload this complete project's contents to the root of the
`scan2cell-android` repository. The included GitHub Actions workflow builds:

`Scan2Cell-Final-v1.0.apk`

Download it from the green run under **Artifacts → Scan2Cell-Final-APK**.

## First installation
Uninstall the older relay-based Scan2Cell APK once, then install this APK.
This project includes a stable personal test signing key, so future APK builds
from this project can update the installed app directly.

## Scanner behavior
The camera capture is held in memory. ML Kit returns individual word-level
bounding boxes plus barcode/QR bounding boxes. Tap the exact box, edit the
selected value if needed, and send it to Excel.
