# MusicConverter Telegram Bot

该目录包含 MusicConverter 的 Telegram 菜单机器人。机器人直接读取公开的 GitHub Release 和 Actions 数据，提供最新版下载、更新日志、支持格式、使用帮助和构建状态。

## 本地运行

需要 Python 3.10 或更高版本，无第三方依赖。

```bash
export TG_BOT_TOKEN="BotFather 提供的 Token"
python telegram-bot/bot.py
```

也可以使用 Docker：

```bash
docker build -t musicconverter-tg-bot telegram-bot
docker run -d --restart unless-stopped \
  --name musicconverter-tg-bot \
  -e TG_BOT_TOKEN="BotFather 提供的 Token" \
  musicconverter-tg-bot
```

> 不要将真实 Token 写入代码、`.env.example` 或提交到 GitHub。

## 环境变量

| 名称 | 必填 | 默认值 | 用途 |
| --- | --- | --- | --- |
| `TG_BOT_TOKEN` | 是 | 无 | Telegram Bot Token |
| `GITHUB_REPOSITORY` | 否 | `YJ-Lazy/MusicConverter` | 数据来源仓库 |
| `TG_COMMUNITY_URL` | 否 | TG 交流群地址 | 菜单中的交流群入口 |
| `LOG_LEVEL` | 否 | `INFO` | 日志等级 |

GitHub 仓库中的 Actions Secret 只提供给工作流使用。长期运行菜单机器人时，还需要在部署机器人所在的平台配置同名的 `TG_BOT_TOKEN` 环境变量。

## 命令

机器人启动时会自动向 Telegram 注册 `/start`、`/latest`、`/changelog`、`/formats`、`/guide`、`/status`、`/faq`、`/github`、`/group`、`/feedback` 和 `/about`。
