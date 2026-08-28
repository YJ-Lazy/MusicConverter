# Sync status

Current source baseline: `1.8.5.1-buildconfig-fix` (`versionCode 19`).

The GitHub build restores the current `MainActivity.kt` and `RemoteUpdateManager.kt` from `app/compressed-src/` before compilation. This avoids repository API size limitations while preserving the exact Kotlin source text.

Remote update manifest remains intentionally separate from source-version synchronization so APK download links are not advanced before a matching APK is uploaded.
