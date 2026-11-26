#!/usr/bin/env bash
set -euo pipefail
# Same dependency identity in both repositories. Only the public producer may build/publish it.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
MAVEN=third_party/ffmpegkitnext-maven
BASE="$MAVEN/com/arthenica/ffmpeg-kit-next/9.0.2/ffmpeg-kit-next-9.0.2"
CHANNEL=ffmpegkitnext-prebuilt
PREFIX=ffmpegkitnext/9.0.2/r27d-api29-arm
PUBLIC_RAW=https://raw.githubusercontent.com/YJ-Lazy/MusicConverter
series() {
    local value
    value="$(sed -n 's/^[[:space:]]*versionName[[:space:]]*"\([^"]*\)".*/\1/p' app/build.gradle | head -n 1)"
    [[ "$value" =~ ^([0-9]+)\.([0-9]+)(\.[0-9]+)?$ ]] || { echo "Invalid application version: $value" >&2; return 1; }
    echo "${BASH_REMATCH[1]}.${BASH_REMATCH[2]}"
}
validate_series() { [[ "$1" =~ ^[0-9]+\.[0-9]+$ ]] || { echo "Invalid series: $1" >&2; return 1; }; }
verify() {
    test -s "$BASE.aar"
    test -s "$BASE.pom"
    unzip -tq "$BASE.aar"
    local entries
    entries="$(unzip -Z1 "$BASE.aar")"
    grep -Fx 'jni/arm64-v8a/libffmpegkit.so' <<< "$entries" >/dev/null
    grep -Fx 'jni/armeabi-v7a/libffmpegkit.so' <<< "$entries" >/dev/null
}
fetch() {
    local version="$1" tmp status uri
    validate_series "$version"
    tmp="$(mktemp -d)"
    uri="$PUBLIC_RAW/$CHANNEL/$PREFIX/$version"
    # HTTP 404 is the only condition that permits bootstrap; outages never trigger a rebuild.
    status="$(curl --silent --show-error --location --retry 3 --output "$tmp/maven.tar.gz.sha256" --write-out '%{http_code}' "$uri/maven.tar.gz.sha256")" || { rm -rf "$tmp"; return 1; }
    if [[ "$status" == 404 ]]; then
        rm -rf "$tmp"; echo "No published FFmpegKitNext bundle for $version" >&2; return 44
    fi
    if [[ "$status" != 200 ]]; then rm -rf "$tmp"; echo "Dependency download HTTP $status" >&2; return 1; fi
    curl --fail --silent --show-error --location --retry 3 "$uri/maven.tar.gz" -o "$tmp/maven.tar.gz" || { rm -rf "$tmp"; return 1; }
    (
        cd "$tmp"
        # Reject unexpected checksum formats and paths.
        grep -Ex '[a-f0-9]{64}  maven\.tar\.gz' maven.tar.gz.sha256 >/dev/null
        sha256sum -c maven.tar.gz.sha256
        python3 - <<'PY'
import tarfile
from pathlib import PurePosixPath
with tarfile.open("maven.tar.gz", "r:gz") as archive:
    for item in archive.getmembers():
        path = PurePosixPath(item.name)
        if path.is_absolute() or ".." in path.parts or not (item.isdir() or item.isfile()):
            raise SystemExit("Invalid dependency archive member: " + item.name)
PY
    ) || { rm -rf "$tmp"; return 1; }
    rm -rf "$MAVEN"
    mkdir -p "$MAVEN"
    tar -xzf "$tmp/maven.tar.gz" -C "$MAVEN"
    rm -rf "$tmp"
    verify
}
case "${1:-}" in
    series) series ;;
    verify) verify ;;
    fetch) fetch "${2:?missing dependency series}" ;;
    *) echo "Usage: $0 series|verify|fetch MAJOR.MINOR" >&2; exit 2 ;;
esac
