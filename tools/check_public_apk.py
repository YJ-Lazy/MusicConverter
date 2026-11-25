"""Reject Xposed module metadata in a public MusicConverter APK."""
import sys
from zipfile import ZipFile

with ZipFile(sys.argv[1]) as apk:
    markers = sorted(
        name for name in apk.namelist()
        if name.startswith("META-INF/xposed/") or name == "assets/xposed_init"
    )
if markers:
    raise SystemExit("Unexpected Xposed module files: " + ", ".join(markers))
print("No Xposed module files found in the APK.")
