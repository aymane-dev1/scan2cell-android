Scan2Cell Android v1.3.3 - Multi-Pass Tier Fix

Why v1.3.2 could still miss Tier:
- Geometry can only organize text that ML Kit actually recognized.
- On some receipt photos the small RIGHT bottom number is omitted entirely by the full-page OCR pass.

What v1.3.3 changes:
1. Full-receipt OCR runs as before.
2. If Contract or Tier is missing, the app crops the lower receipt area and enlarges it 2.6x, then OCRs it again.
3. If one ID is still missing, the app OCRs the lower LEFT and RIGHT areas separately at 3x size.
4. The LEFT long ID is Contract; the RIGHT long ID is Tier / Ref.
5. Common OCR confusions O/0, I/1, S/5, G/6, B/8, etc. are normalized.
6. The review screen still lets you correct either field manually before Send.

Expected example:
Contract: 00000234329
Tier / Ref.: 00006665874

Excel, XML, and PC bridge do not need changes.
versionCode: 133
versionName: 1.3.3-multipass-tier-fix
