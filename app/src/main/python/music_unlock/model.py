"""解码结果与输出容器识别。"""
from __future__ import annotations

from dataclasses import dataclass, field

_SNIFFERS = (
    (b"fLaC", "flac"),
    (b"OggS", "ogg"),
    (b"ID3", "mp3"),
    (b"RIFF", "wav"),
)


@dataclass(slots=True)
class DecodeResult:
    payload: bytes
    container: str
    tags: dict[str, str] = field(default_factory=dict)
    cover: bytes | None = None
    source: str = ""

    @property
    def suggested_extension(self) -> str:
        if self.container == "m4a":
            return "m4a"
        if self.container == "ogg":
            return "ogg"
        return self.container


def sniff_container(head: bytes) -> str:
    for magic, name in _SNIFFERS:
        if head.startswith(magic):
            if magic == b"RIFF" and len(head) >= 12 and head[8:12] != b"WAVE":
                continue
            return name
    if len(head) >= 8 and head[4:8] == b"ftyp":
        return "m4a"
    if len(head) >= 2 and head[0] == 0xFF and (head[1] & 0xE0) == 0xE0:
        return "mp3"
    return "bin"
