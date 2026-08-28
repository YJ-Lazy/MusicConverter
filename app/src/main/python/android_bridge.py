"""Android bridge for the upstream music_unlock engine.

The bridge intentionally only performs local decoding. FFmpeg transcoding is handled
by the Android FFmpegKit layer so the Python code never needs an ffmpeg executable.
"""
from __future__ import annotations
import json
from pathlib import Path

from music_unlock.formats import pick_decoder
from music_unlock.formats.base import DecodeOptions
from music_unlock.tags import embed


def _unique_path(directory: Path, stem: str, ext: str) -> Path:
    candidate = directory / f"{stem}.{ext}"
    if not candidate.exists():
        return candidate
    for i in range(1, 1000):
        candidate = directory / f"{stem} ({i}).{ext}"
        if not candidate.exists():
            return candidate
    raise RuntimeError("无法创建唯一输出文件名")


def unlock_file(input_path: str, output_dir: str) -> str:
    try:
        src = Path(input_path)
        out_dir = Path(output_dir)
        out_dir.mkdir(parents=True, exist_ok=True)
        decoder = pick_decoder(src)
        result = decoder.decode(src, DecodeOptions())
        if not result.payload:
            raise RuntimeError("解密结果为空")

        ext = result.suggested_extension or result.container or "bin"
        out = _unique_path(out_dir, src.stem, ext)
        out.write_bytes(result.payload)

        if result.tags or result.cover:
            try:
                embed(ext if ext in ("mp3", "flac", "m4a") else result.container,
                      out, result.tags, result.cover)
            except Exception:
                pass

        return json.dumps({
            "ok": True,
            "output": str(out),
            "container": result.container,
            "source": result.source,
        }, ensure_ascii=False)
    except Exception as exc:
        return json.dumps({
            "ok": False,
            "error": f"{type(exc).__name__}: {exc}",
        }, ensure_ascii=False)
