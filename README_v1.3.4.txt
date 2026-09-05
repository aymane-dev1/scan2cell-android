Scan2Cell Android v1.3.4 — Multi-PC Pairing Fix

WHY THIS FIX EXISTS
-------------------
On a home network with one Scan2Cell PC, old automatic discovery worked fine.

In an office / coworker setup, several PCs can answer the same UDP discovery
request. Older Android versions used the FIRST reply they received. If the
six-digit code came from PC-B but the phone happened to discover PC-A first,
PC-A returned:

    Wrong or expired pairing code.

v1.3.4 fixes this.

NEW BEHAVIOR
------------
1. The phone discovers ALL Scan2Cell PCs on the current Wi-Fi.
2. It tries the entered six-digit code against each PC.
3. It connects to the PC that actually accepts that code.
4. A previously saved PC is only a candidate; it no longer hijacks pairing.
5. Manual IP still forces one exact PC when needed.

INSTALL
-------
Replace the contents of your existing Android GitHub project with this project,
push to main, then install the generated APK over the current app.

Version:
  versionCode 134
  versionName 1.3.4-multi-pc-pairing-fix

The existing signing key/package name are preserved, so no uninstall should
be required.

ABOUT "Scan2Cell Local"
-----------------------
"Scan2Cell Local" is only the DisplayName of the current Excel add-in manifest.
It is not an error and does not affect pairing.
