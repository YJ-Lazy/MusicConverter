"""酷我音乐 KWM 解密器（老版格式：1024 字节头 + 32 字节循环异或密钥）。"""
from __future__ import annotations

from pathlib import Path

from ..errors import FormatError
from ..model import DecodeResult, sniff_container
from .base import DecodeOptions, Decoder

HEADER_LEN = 1024
KEY_LEN = 32
MAX_SCAN_CHUNKS = 2048


def _try_key(data: bytes, key: bytes) -> str:
    """用候选密钥解密文件头若干字节并嗅探容器，返回容器名或 'bin'。"""
    probe = bytes(b ^ key[i & (KEY_LEN - 1)] for i, b in enumerate(data[HEADER_LEN : HEADER_LEN + 4096]))
    stripped = probe.lstrip(b"\x00")[:64]
    return sniff_container(stripped)


class KwmDecoder(Decoder):
    name = "酷我音乐 KWM"
    extensions = frozenset({".kwm"})

    def decode(self, path: Path, opts: DecodeOptions) -> DecodeResult:
        data = path.read_bytes()
        if len(data) < HEADER_LEN + KEY_LEN * 2:
            raise FormatError(f"{path.name}: 文件过短")

        body = data[HEADER_LEN:]
        candidates: list[bytes] = []
        prev = body[:KEY_LEN]
        for i in range(1, min(MAX_SCAN_CHUNKS, len(body) // KEY_LEN)):
            chunk = body[i * KEY_LEN : (i + 1) * KEY_LEN]
            if chunk == prev:
                candidates.append(chunk)
            prev = chunk
        tail = prev
        candidates.append(tail[KEY_LEN // 2 :] + tail[: KEY_LEN // 2])

        chosen: bytes | None = None
        for key in candidates:
            if _try_key(data, key) != "bin":
                chosen = key
                break
        if chosen is None:
            for i in range(min(64, len(body) // KEY_LEN)):
                key = body[i * KEY_LEN : (i + 1) * KEY_LEN]
                if _try_key(data, key) != "bin":
                    chosen = key
                    break
        if chosen is None:
            chosen = candidates[0]

        payload = bytes(
            b ^ chosen[i & (KEY_LEN - 1)] for i, b in enumerate(body)
        )
        container = sniff_container(payload.lstrip(b"\x00")[:64])
        return DecodeResult(payload=payload, container=container, source="kwm")
