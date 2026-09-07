"""QQ 音乐 QMC 解密器（v1 静态密钥 / v2 内嵌或外部 EKey）。"""
from __future__ import annotations

import base64
import sqlite3
from pathlib import Path

from ..ciphers import derive_master_key, make_qmc2_stream, qmc1_transform
from ..errors import ExternalKeyRequiredError, FormatError, KeyError_, UnsupportedError
from ..model import DecodeResult, sniff_container
from .base import DecodeOptions, Decoder

# 公开的 v1 静态密钥（128 字节）
V1_STATIC_KEY = bytes(
    [
        0xC3, 0x4A, 0xD6, 0xCA, 0x90, 0x67, 0xF7, 0x52, 0xD8, 0xA1, 0x66, 0x62, 0x9F, 0x5B, 0x09, 0x00,
        0xC3, 0x5E, 0x95, 0x23, 0x9F, 0x13, 0x11, 0x7E, 0xD8, 0x92, 0x3F, 0xBC, 0x90, 0xBB, 0x74, 0x0E,
        0xC3, 0x47, 0x74, 0x3D, 0x90, 0xAA, 0x3F, 0x51, 0xD8, 0xF4, 0x11, 0x84, 0x9F, 0xDE, 0x95, 0x1D,
        0xC3, 0xC6, 0x09, 0xD5, 0x9F, 0xFA, 0x66, 0xF9, 0xD8, 0xF0, 0xF7, 0xA0, 0x90, 0xA1, 0xD6, 0xF3,
        0xC3, 0xF3, 0xD6, 0xA1, 0x90, 0xA0, 0xF7, 0xF0, 0xD8, 0xF9, 0x66, 0xFA, 0x9F, 0xD5, 0x09, 0xC6,
        0xC3, 0x1D, 0x95, 0xDE, 0x9F, 0x84, 0x11, 0xF4, 0xD8, 0x51, 0x3F, 0xAA, 0x90, 0x3D, 0x74, 0x47,
        0xC3, 0x0E, 0x74, 0xBB, 0x90, 0xBC, 0x3F, 0x92, 0xD8, 0x7E, 0x11, 0x13, 0x9F, 0x23, 0x95, 0x5E,
        0xC3, 0x00, 0x09, 0x5B, 0x9F, 0x62, 0x66, 0xA1, 0xD8, 0x52, 0xF7, 0x67, 0x90, 0xCA, 0xD6, 0x4A,
    ]
)

V1_EXTS = {
    # QMCDecode (MIT) 将 qmc0/qmc2/qmc3 归为旧式静态流密码。
    ".qmc0", ".qmc2", ".qmc3",
    ".tkm", ".bkcmp3", ".bkcm4a", ".bkcflac", ".bkcwav", ".bkcape", ".bkcogg", ".bkcwma",
    ".666c6163", ".6d7033", ".6f6767", ".6d3461", ".776176",  # 十六进制扩展名
}
V2_EXTS = {
    ".mflac", ".mflac0", ".mflach", ".mgg", ".mgg0", ".mgg1", ".mggl", ".mmp4",
    ".qmflac", ".qmcflac", ".qmcogg", ".qmc4", ".qmc6", ".qmc8",
}
MAX_EKEY_LEN = 0x500
MUSICEX_BLOCK = 0xC0
MUSICEX_VERSION = 1
MUSICEX_MAGIC = b"musicex\x00"
AUDIO_WRAPPER_EXTS = {".mp3", ".flac", ".m4a", ".aac", ".wav", ".ogg", ".opus"}


def effective_qmc_extension(path: Path) -> str:
    """Return the QMC marker for normal and double-suffix filenames.

    Newer QQ Music downloads can look like `title.mgg0.flac`. The final `.flac`
    is only a filename wrapper: the encrypted format is still `.mgg0`.
    """
    lower = path.name.lower()
    direct = path.suffix.lower()
    if direct in V1_EXTS or direct in V2_EXTS:
        return direct
    if direct in AUDIO_WRAPPER_EXTS:
        inner = Path(path.stem).suffix.lower()
        if inner in V1_EXTS or inner in V2_EXTS:
            return inner
    return direct


def is_qmc_filename(path: Path) -> bool:
    return effective_qmc_extension(path) in V1_EXTS or effective_qmc_extension(path) in V2_EXTS


class Footer:
    """QMC v2 文件尾部的元数据/EKey 包。"""

    __slots__ = ("size", "ekey", "kind", "resource_id", "mid", "media_filename")

    def __init__(self, size: int, ekey: str | None, kind: str, **extra):
        self.size = size
        self.ekey = ekey
        self.kind = kind
        self.resource_id = extra.get("resource_id")
        self.mid = extra.get("mid")
        self.media_filename = extra.get("media_filename")


def _is_base64_text(s: bytes) -> bool:
    for c in s:
        if c not in b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=":
            return False
    return bool(s)


def _read_utf16le(data: bytes, field_name: str = "字段") -> str:
    """读取定长 UTF-16LE NUL 结尾字段，支持中文等非 ASCII 文件名。"""
    if len(data) % 2:
        raise FormatError(f"{field_name} UTF-16LE 长度非法")
    end = None
    for i in range(0, len(data), 2):
        if data[i : i + 2] == b"\x00\x00":
            end = i
            break
    if end is None:
        raise FormatError(f"{field_name} 未找到 NUL 结束符")
    if any(data[end + 2 :]):
        raise FormatError(f"{field_name} 结束符后存在非零数据")
    try:
        return data[:end].decode("utf-16le")
    except UnicodeDecodeError as exc:
        raise FormatError(f"{field_name} UTF-16LE 非法") from exc


def _validate_musicex_resource_name(name: str) -> None:
    if not name or "/" in name or "\\" in name or name in {".", ".."}:
        raise FormatError("MusicEx 资源文件名非法")
    lower = name.lower()
    supported = (".mflac", ".mflac0", ".mflach", ".mgg", ".mgg0", ".mgg1", ".mggl")
    if not lower.endswith(supported):
        raise FormatError("MusicEx 资源文件扩展名不受支持")


def parse_footer(tail: bytes) -> Footer | None:
    """解析文件末尾片段。调用方应至少提供 MAX_EKEY_LEN + 32 字节尾部。"""
    if len(tail) < 8:
        return None

    # Android STag：无 EKey，只有资源元数据（结构: [csv][4B 大端长度][STag]）
    if tail.endswith(b"STag"):
        body = tail[:-4]
        payload, size_bytes = body[:-4], body[-4:]
        payload_len = int.from_bytes(size_bytes, "big")
        if len(payload) < payload_len:
            raise FormatError("STag 长度不一致")
        parts = payload[len(payload) - payload_len :].decode("utf-8", "replace").split(",")
        if len(parts) != 3 or parts[1] != "2" or not parts[0].isdigit():
            raise FormatError("STag 内容非法")
        return Footer(payload_len + 8, None, "STag", resource_id=int(parts[0]), mid=parts[2])

    # Android QTag：含内嵌 EKey（结构: [csv][4B 大端长度][QTag]）
    if tail.endswith(b"QTag"):
        body = tail[:-4]
        payload, size_bytes = body[:-4], body[-4:]
        payload_len = int.from_bytes(size_bytes, "big")
        if len(payload) < payload_len:
            raise FormatError("QTag 长度不一致")
        parts = payload[len(payload) - payload_len :].decode("utf-8", "replace").split(",")
        if len(parts) != 3 or parts[2] != "2" or not parts[1].isdigit():
            raise FormatError("QTag 内容非法")
        ekey = parts[0]
        if not _is_base64_text(ekey.encode("latin-1")):
            raise FormatError("QTag EKey 非法")
        return Footer(payload_len + 8, ekey, "QTag", resource_id=int(parts[1]))

    # PC 新版 MusicEx：无 EKey。
    # 参考 Kugo-Music-Converter 对公开格式的验证结果：
    # footer 总长固定 0xC0；末 16B = [4B footerSize LE][4B version LE][8B musicex\0]。
    # 这里只移植结构识别，不移植其 Windows 会话/进程内存读取逻辑。
    if tail.endswith(MUSICEX_MAGIC):
        if len(tail) < 16:
            raise FormatError("MusicEx 尾部过短")
        trailer = tail[-16:]
        footer_size = int.from_bytes(trailer[0:4], "little")
        version = int.from_bytes(trailer[4:8], "little")
        if footer_size != MUSICEX_BLOCK:
            raise UnsupportedError(f"MusicEx 尾部长度不支持 0x{footer_size:X}")
        if version != MUSICEX_VERSION:
            raise UnsupportedError(f"MusicEx 版本不支持 {version}")
        if len(tail) < footer_size:
            raise FormatError("MusicEx 尾部数据不完整")

        footer = tail[-footer_size:]
        # 二次验证 trailer，避免尾部切片/长度误判。
        if footer[-16:-8] != trailer[:8] or footer[-8:] != MUSICEX_MAGIC:
            raise FormatError("MusicEx 尾部校验失败")

        resource_id = int.from_bytes(footer[0x00:0x04], "little")
        mid = _read_utf16le(footer[0x0C:0x48], "media_mid")
        media_filename = _read_utf16le(footer[0x48:0x8C], "resource filename")
        if not mid:
            raise FormatError("MusicEx media_mid 为空")
        if len(mid) > 60 or any(not (c.isascii() and (c.isalnum() or c in "_-")) for c in mid):
            raise FormatError("MusicEx media_mid 非法")
        _validate_musicex_resource_name(media_filename)
        return Footer(
            footer_size,
            None,
            "MusicEx",
            resource_id=resource_id,
            mid=mid,
            media_filename=media_filename,
        )

    # PC 经典 PcV1Legacy：小端长度 + base64 EKey
    payload, size_bytes = tail[:-4], tail[-4:]
    payload_len = int.from_bytes(size_bytes, "little")
    if payload_len > MAX_EKEY_LEN:
        return None  # 大概率不是 QMC 文件
    if len(payload) < payload_len:
        raise FormatError("PcV1Legacy 长度不一致")
    ekey_bytes = payload[len(payload) - payload_len :]
    zero = ekey_bytes.find(b"\x00")
    if zero != -1:
        ekey_bytes = ekey_bytes[:zero]
    if not _is_base64_text(ekey_bytes):
        raise FormatError("PcV1Legacy EKey 非法")
    return Footer(payload_len + 4, ekey_bytes.decode("latin-1"), "PcV1Legacy")


# ---------------------------------------------------------------------------
# 本地密钥库（QQ 音乐安卓端 player_process_db，SQLite）
# ---------------------------------------------------------------------------

_DB_TABLES = (
    ("audio_file_ekey_table", "file_path", "ekey"),
    ("EKeyFileInfo", "filePath", "eKey"),
    ("p2p_cache_info_table", "file_id", "ekey"),
)


def list_ekeys(db_path: Path, find: str | None = None) -> list[tuple[str, str]]:
    """列出密钥库中所有 (文件名, EKey)。"""
    out: list[tuple[str, str]] = []
    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        for table, path_col, key_col in _DB_TABLES:
            if table not in tables:
                continue
            for path, ekey in conn.execute(f"SELECT {path_col}, {key_col} FROM {table}"):
                if path is None or ekey is None:
                    continue
                base = Path(str(path)).name
                if find and find not in base and find not in str(path):
                    continue
                out.append((base, str(ekey).strip()))
    finally:
        conn.close()
    return out


def lookup_ekey(db_path: Path, *names: str | None) -> str | None:
    """按文件名/资源 id 在密钥库中查找 EKey。"""
    candidates = [Path(str(n)).name for n in names if n] if names else []
    candidates += [str(n) for n in names if n]
    try:
        conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    except sqlite3.Error:
        return None
    try:
        tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        for table, path_col, key_col in _DB_TABLES:
            if table not in tables:
                continue
            for path, ekey in conn.execute(f"SELECT {path_col}, {key_col} FROM {table}"):
                if path is None or ekey is None:
                    continue
                path_s = str(path)
                if Path(path_s).name in candidates:
                    return str(ekey).strip()
                for cand in candidates:
                    if len(cand) >= 8 and cand in path_s:
                        return str(ekey).strip()
    finally:
        conn.close()
    return None


# ---------------------------------------------------------------------------
# 解码器
# ---------------------------------------------------------------------------


class QmcDecoder(Decoder):
    name = "QQ 音乐 QMC"
    extensions = frozenset(V1_EXTS | V2_EXTS)

    def decode(self, path: Path, opts: DecodeOptions) -> DecodeResult:
        data = path.read_bytes()
        ext = effective_qmc_extension(path)

        if ext in V1_EXTS:
            payload = qmc1_transform(data, V1_STATIC_KEY)
            return DecodeResult(payload=payload, container=sniff_container(payload[:64]), source="qmc-v1")

        # v2：先解析文件尾包
        footer = None
        try:
            footer = parse_footer(data[-4096:])
        except FormatError:
            footer = None

        if footer is None:
            # 可能是被改名的 v1 文件：用静态密钥试解后嗅探
            head = qmc1_transform(data[:64], V1_STATIC_KEY)
            if sniff_container(head) != "bin":
                payload = qmc1_transform(data, V1_STATIC_KEY)
                return DecodeResult(payload=payload, container=sniff_container(payload[:64]), source="qmc-v1")
            raise FormatError(f"{path.name}: 未找到 QMC 尾包或静态密钥特征")

        ekey = footer.ekey
        if not ekey:
            ekey = opts.ekey
        if not ekey and opts.ekey_db is not None:
            ekey = lookup_ekey(
                opts.ekey_db,
                path.name,
                footer.mid,
                footer.media_filename,
                str(footer.resource_id) if footer.resource_id else None,
            )
        if not ekey:
            if footer.kind in ("MusicEx", "STag"):
                raise ExternalKeyRequiredError(
                    f"检测到 QQ 音乐 {footer.kind} 类型，文件中未包含 APP 端可用的 EKey。"
                    "该类型/歌曲无法仅通过 MusicConverter APP 本地完成解密。"
                    "建议使用 PC 端 QMC 解密工具处理该歌曲。",
                    format_name=footer.kind,
                    provider="QQ音乐",
                )
            raise KeyError_(
                f"{path.name}: {footer.kind} 类型无内嵌密钥。"
                "需要对应歌曲 EKey，或用密钥库匹配后才能解密。"
            )

        master = derive_master_key(ekey.encode("latin-1"))
        stream = make_qmc2_stream(master)
        payload = stream.decrypt(data[: len(data) - footer.size])
        return DecodeResult(payload=payload, container=sniff_container(payload[:64]), source=f"qmc-v2:{footer.kind}")
