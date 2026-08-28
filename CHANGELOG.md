# Changelog

## 1.7.0 - User-selectable ignored formats

- Added a batch-scan setting for choosing which formats to ignore.
- MP3 remains ignored by default to avoid redundant lossy MP3-to-MP3 transcoding.
- Supports ignoring MP3, FLAC, M4A/AAC, WAV, OGG/OPUS, NCM, QQ encrypted formats, KGM/KGMA/VPR, and KWM.
- Ignore preferences persist across launches and are enforced by both scanning and the background conversion service.
- Single-file conversion and audio editing remain unaffected.

## 1.6.1 - Graceful pause

- Added “pause after current tasks finish”.
- Running workers complete naturally; no new tasks are dispatched until resumed.
- Pause/resume controls are available in both the app and the foreground notification.

## 1.6.0 - Parallel batch conversion

- Added 1–4 parallel workers for batch conversion.
- Added independent temp files and FFmpeg sessions per worker.
- Notification reports active workers and batch progress.

## 1.5.x - Background conversion

- Added Android foreground service, progress notification, wake lock, stop action, and battery-optimization entry point.

## 1.4.0 - Workspace UI

- Reorganized the UI into Home, Batch/Tools, and About pages.

## 1.3.x - Batch scan and source replacement

- Added recursive SAF directory scanning and one-tap batch conversion.
- Added optional source-file replacement after successful conversion.
- Added pause/resume behavior for editor preview.
