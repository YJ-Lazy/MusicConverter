# Cloudflare Workers 部署

这是 MusicConverter Telegram 机器人的推荐部署版本，使用 Telegram Webhook，不需要常驻服务器。

## Cloudflare Git 部署设置

- Root directory：`telegram-worker`
- Build command：留空
- Deploy command：`npx wrangler deploy`
- Production branch：`main`
- Cloudflare Access：关闭（Telegram 必须能访问 Webhook）

首次部署完成后，在 Worker 的 Settings → Variables and Secrets 中添加两个加密 Secret：

- `TG_BOT_TOKEN`：BotFather 提供的 Token
- `TG_WEBHOOK_SECRET`：自行生成的随机字符串，只使用英文字母、数字、下划线和短横线，长度建议 32～64 位

`GITHUB_REPOSITORY` 和 `TG_COMMUNITY_URL` 已在 `wrangler.jsonc` 中配置，不需要重复添加。

## 启用 Webhook

部署并配置 Secrets 后，对 Worker 的 `/setup` 地址发送一次 POST 请求：

```bash
curl -X POST "https://你的Worker地址/setup" \
  -H "Authorization: Bearer 你的TG_WEBHOOK_SECRET"
```

成功时返回 `{"ok":true,...}`。之后向机器人发送 `/start` 即可打开菜单。
