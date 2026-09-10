#!/usr/bin/env python3
"""MusicConverter Telegram bot (standard-library only)."""

from __future__ import annotations

import html
import json
import logging
import os
import time
import urllib.error
import urllib.parse
import urllib.request


BOT_TOKEN = os.environ.get("TG_BOT_TOKEN", "").strip()
REPOSITORY = os.environ.get("GITHUB_REPOSITORY", "YJ-Lazy/MusicConverter").strip()
COMMUNITY_URL = os.environ.get(
    "TG_COMMUNITY_URL", "https://t.me/MusicConverter_YJ_Lazy"
).strip()
GITHUB_URL = f"https://github.com/{REPOSITORY}"
API_URL = f"https://api.telegram.org/bot{BOT_TOKEN}"

logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)s %(message)s",
)


def request_json(url: str, data: dict | None = None, timeout: int = 35) -> dict:
    encoded = None
    if data is not None:
        encoded = urllib.parse.urlencode(
            {key: json.dumps(value) if isinstance(value, (dict, list)) else value
             for key, value in data.items()}
        ).encode()
    request = urllib.request.Request(
        url,
        data=encoded,
        headers={"User-Agent": "MusicConverter-Telegram-Bot"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


def telegram(method: str, **data) -> dict:
    result = request_json(f"{API_URL}/{method}", data)
    if not result.get("ok"):
        raise RuntimeError(f"Telegram API error: {result}")
    return result["result"]


def github_api(path: str) -> dict:
    return request_json(f"https://api.github.com/repos/{REPOSITORY}{path}")


def main_keyboard() -> dict:
    return {
        "inline_keyboard": [
            [
                {"text": "📥 下载最新版", "callback_data": "latest"},
                {"text": "🆕 更新内容", "callback_data": "changelog"},
            ],
            [
                {"text": "🎵 支持格式", "callback_data": "formats"},
                {"text": "📖 使用教程", "callback_data": "guide"},
            ],
            [
                {"text": "🛠 构建状态", "callback_data": "status"},
                {"text": "❓ 常见问题", "callback_data": "faq"},
            ],
            [
                {"text": "💻 GitHub 项目", "url": GITHUB_URL},
                {"text": "👥 交流群", "url": COMMUNITY_URL},
            ],
            [
                {"text": "📝 问题反馈", "url": f"{GITHUB_URL}/issues/new"},
                {"text": "ℹ️ 关于项目", "callback_data": "about"},
            ],
        ]
    }


def send(chat_id: int | str, text: str, keyboard: dict | None = None) -> None:
    data = {
        "chat_id": chat_id,
        "text": text,
        "parse_mode": "HTML",
        "disable_web_page_preview": False,
    }
    if keyboard:
        data["reply_markup"] = keyboard
    telegram("sendMessage", **data)


def latest_release() -> dict:
    return github_api("/releases/latest")


def latest_text() -> tuple[str, dict]:
    try:
        release = latest_release()
        assets = release.get("assets", [])
        apk = next(
            (asset for asset in assets if asset.get("name", "").lower().endswith(".apk")),
            None,
        )
        download_url = apk["browser_download_url"] if apk else release["html_url"]
        size = f"{apk['size'] / 1024 / 1024:.1f} MB" if apk else "请在 Release 页面查看"
        text = (
            "<b>🎵 MusicConverter 最新正式版</b>\n\n"
            f"📦 版本：<b>{html.escape(release['tag_name'])}</b>\n"
            f"📅 发布：{html.escape(release.get('published_at', '')[:10])}\n"
            f"💾 大小：{size}\n"
            "📱 系统要求：Android 10 及以上"
        )
        keyboard = {
            "inline_keyboard": [
                [{"text": "📥 下载正式版 APK", "url": download_url}],
                [{"text": "📋 Release 页面", "url": release["html_url"]}],
                [{"text": "⬅️ 返回主菜单", "callback_data": "menu"}],
            ]
        }
        return text, keyboard
    except urllib.error.HTTPError as error:
        logging.warning("Unable to load latest release: %s", error)
        return (
            "暂时无法读取最新 Release，请前往 GitHub 查看。",
            {"inline_keyboard": [[{"text": "打开 Releases", "url": f"{GITHUB_URL}/releases"}]]},
        )


def changelog_text() -> str:
    try:
        release = latest_release()
        body = (release.get("body") or "本次发布暂未填写更新说明。").strip()
        if len(body) > 3000:
            body = body[:3000] + "…"
        return (
            f"<b>🆕 {html.escape(release['tag_name'])} 更新内容</b>\n\n"
            f"{html.escape(body)}"
        )
    except urllib.error.HTTPError:
        return "暂时无法读取更新日志，请稍后重试。"


def status_text() -> tuple[str, dict]:
    try:
        data = github_api("/actions/workflows/android-apk.yml/runs?per_page=1")
        run = data["workflow_runs"][0]
        conclusion = run.get("conclusion") or run.get("status") or "unknown"
        icons = {"success": "✅", "failure": "❌", "cancelled": "⚪", "in_progress": "⏳"}
        text = (
            "<b>🛠 最近一次构建</b>\n\n"
            f"{icons.get(conclusion, 'ℹ️')} 状态：{html.escape(conclusion)}\n"
            f"🌿 分支：{html.escape(run.get('head_branch') or '-')}\n"
            f"📝 提交：{html.escape(run.get('head_sha', '')[:7])}"
        )
        return text, {"inline_keyboard": [[{"text": "查看构建详情", "url": run["html_url"]}]]}
    except (urllib.error.HTTPError, IndexError, KeyError):
        return "暂时无法读取构建状态。", None


STATIC_TEXT = {
    "formats": (
        "<b>🎵 支持格式</b>\n\n"
        "常规音频：MP3、FLAC、M4A、AAC、WAV、OGG、OPUS\n\n"
        "加密音乐：NCM、QMC、MFLAC、KGM、KGMA、VPR、KWM\n\n"
        "⚠️ 部分新版加密格式可能受密钥或上游解析能力限制。"
    ),
    "guide": (
        "<b>📖 使用提示</b>\n\n"
        "1. 首次使用时授予音乐和文件访问权限。\n"
        "2. 在“转码”页选择单个文件或目录批量处理。\n"
        "3. 大量文件建议先用少量样本确认输出结果。\n"
        "4. 批量转换建议使用 2 路并行。\n"
        "5. 默认输出目录：<code>Music/MusicConverter/</code>"
    ),
    "faq": (
        "<b>❓ 常见问题</b>\n\n"
        "• 转换中断：请允许后台运行并关闭电池优化。\n"
        "• 找不到文件：检查媒体与所有文件访问权限。\n"
        "• 转换失败：先确认格式受支持，并用少量文件测试。\n"
        "• 在线音乐不可用：该功能不维护，第三方接口可能失效。\n"
        "• 本地转换结果默认位于 <code>Music/MusicConverter/</code>。"
    ),
    "about": (
        "<b>ℹ️ MusicConverter</b>\n\n"
        "面向 Android 的本地音乐管理、格式转换与音频处理工具。\n"
        "最低系统：Android 10（API 29）\n\n"
        "项目主要用于管理和处理用户自己拥有并有权处理的音频文件。"
    ),
}


def show_menu(chat_id: int | str) -> None:
    send(
        chat_id,
        "<b>🎵 MusicConverter 助手</b>\n\n请选择需要的功能：",
        main_keyboard(),
    )


def handle_action(chat_id: int | str, action: str) -> None:
    if action in {"start", "menu"}:
        show_menu(chat_id)
    elif action == "latest":
        text, keyboard = latest_text()
        send(chat_id, text, keyboard)
    elif action == "changelog":
        send(chat_id, changelog_text())
    elif action == "status":
        text, keyboard = status_text()
        send(chat_id, text, keyboard)
    elif action in STATIC_TEXT:
        send(chat_id, STATIC_TEXT[action], {"inline_keyboard": [[{"text": "⬅️ 返回主菜单", "callback_data": "menu"}]]})
    elif action == "github":
        send(chat_id, f"💻 {GITHUB_URL}")
    elif action == "group":
        send(chat_id, f"👥 {COMMUNITY_URL}")
    elif action == "feedback":
        send(chat_id, f"📝 {GITHUB_URL}/issues/new")
    else:
        send(chat_id, "未识别的命令，请发送 /start 打开菜单。")


def handle_update(update: dict) -> None:
    if "callback_query" in update:
        query = update["callback_query"]
        telegram("answerCallbackQuery", callback_query_id=query["id"])
        message = query.get("message", {})
        if "chat" in message:
            handle_action(message["chat"]["id"], query.get("data", "menu"))
        return

    message = update.get("message") or update.get("channel_post")
    if not message or "text" not in message:
        return
    text = message["text"].strip()
    if not text.startswith("/"):
        return
    command = text.split(maxsplit=1)[0][1:].split("@", maxsplit=1)[0].lower()
    handle_action(message["chat"]["id"], command)


def configure_commands() -> None:
    commands = [
        {"command": "start", "description": "打开机器人主菜单"},
        {"command": "latest", "description": "获取最新正式版"},
        {"command": "changelog", "description": "查看最新版本更新内容"},
        {"command": "formats", "description": "查看支持的音频格式"},
        {"command": "guide", "description": "查看转换与剪辑教程"},
        {"command": "status", "description": "查看 GitHub 构建状态"},
        {"command": "faq", "description": "查看常见问题"},
        {"command": "github", "description": "打开 GitHub 项目"},
        {"command": "group", "description": "加入 Telegram 交流群"},
        {"command": "feedback", "description": "提交问题或建议"},
        {"command": "about", "description": "关于 MusicConverter"},
    ]
    telegram("setMyCommands", commands=commands)


def main() -> None:
    if not BOT_TOKEN:
        raise SystemExit("Missing required environment variable: TG_BOT_TOKEN")
    configure_commands()
    logging.info("MusicConverter bot started for %s", REPOSITORY)
    offset = 0
    while True:
        try:
            updates = telegram("getUpdates", offset=offset, timeout=30)
            for update in updates:
                offset = update["update_id"] + 1
                handle_update(update)
        except (urllib.error.URLError, TimeoutError, RuntimeError) as error:
            logging.warning("Polling failed: %s", error)
            time.sleep(5)


if __name__ == "__main__":
    main()
