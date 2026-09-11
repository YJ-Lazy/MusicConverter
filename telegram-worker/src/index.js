const DEFAULT_REPOSITORY = "YJ-Lazy/MusicConverter";
const DEFAULT_COMMUNITY = "https://t.me/MusicConverter_YJ_Lazy";

const commands = [
  { command: "start", description: "打开机器人主菜单" },
  { command: "latest", description: "获取最新正式版" },
  { command: "changelog", description: "查看最新版本更新内容" },
  { command: "formats", description: "查看支持的音频格式" },
  { command: "guide", description: "查看转换与剪辑教程" },
  { command: "status", description: "查看 GitHub 构建状态" },
  { command: "faq", description: "查看常见问题" },
  { command: "github", description: "打开 GitHub 项目" },
  { command: "group", description: "加入 Telegram 交流群" },
  { command: "feedback", description: "提交问题或建议" },
  { command: "about", description: "关于 MusicConverter" },
];

const staticText = {
  formats:
    "<b>🎵 支持格式</b>\n\n" +
    "常规音频：MP3、FLAC、M4A、AAC、WAV、OGG、OPUS\n\n" +
    "加密音乐：NCM、QMC、MFLAC、KGM、KGMA、VPR、KWM\n\n" +
    "⚠️ 部分新版加密格式可能受密钥或上游解析能力限制。",
  guide:
    "<b>📖 使用提示</b>\n\n" +
    "1. 首次使用时授予音乐和文件访问权限。\n" +
    "2. 在“转码”页选择单个文件或目录批量处理。\n" +
    "3. 大量文件建议先用少量样本确认输出结果。\n" +
    "4. 批量转换建议使用 2 路并行。\n" +
    "5. 默认输出目录：<code>Music/MusicConverter/</code>",
  faq:
    "<b>❓ 常见问题</b>\n\n" +
    "• 转换中断：请允许后台运行并关闭电池优化。\n" +
    "• 找不到文件：检查媒体与所有文件访问权限。\n" +
    "• 转换失败：先确认格式受支持，并用少量文件测试。\n" +
    "• 在线音乐不可用：该功能不维护，第三方接口可能失效。\n" +
    "• 输出目录：<code>Music/MusicConverter/</code>。",
  about:
    "<b>ℹ️ MusicConverter</b>\n\n" +
    "面向 Android 的本地音乐管理、格式转换与音频处理工具。\n" +
    "最低系统：Android 10（API 29）\n\n" +
    "项目用于管理和处理用户自己拥有并有权处理的音频文件。",
};

function escapeHtml(value = "") {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function config(env) {
  const repository = env.GITHUB_REPOSITORY || DEFAULT_REPOSITORY;
  return {
    repository,
    githubUrl: `https://github.com/${repository}`,
    communityUrl: env.TG_COMMUNITY_URL || DEFAULT_COMMUNITY,
  };
}

async function telegram(env, method, body) {
  const response = await fetch(
    `https://api.telegram.org/bot${env.TG_BOT_TOKEN}/${method}`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
    },
  );
  const result = await response.json();
  if (!result.ok) throw new Error(`Telegram ${method}: ${JSON.stringify(result)}`);
  return result.result;
}

async function github(env, path) {
  const { repository } = config(env);
  const response = await fetch(`https://api.github.com/repos/${repository}${path}`, {
    headers: {
      Accept: "application/vnd.github+json",
      "User-Agent": "MusicConverter-Telegram-Worker",
    },
    cf: { cacheTtl: 60, cacheEverything: true },
    signal: AbortSignal.timeout(8000),
  });
  if (!response.ok) throw new Error(`GitHub API ${response.status}`);
  return response.json();
}

function menu(env) {
  const { githubUrl, communityUrl } = config(env);
  return {
    inline_keyboard: [
      [
        { text: "📥 下载最新版", callback_data: "latest" },
        { text: "🆕 更新内容", callback_data: "changelog" },
      ],
      [
        { text: "🎵 支持格式", callback_data: "formats" },
        { text: "📖 使用教程", callback_data: "guide" },
      ],
      [
        { text: "🛠 构建状态", callback_data: "status" },
        { text: "❓ 常见问题", callback_data: "faq" },
      ],
      [
        { text: "💻 GitHub 项目", url: githubUrl },
        { text: "👥 交流群", url: communityUrl },
      ],
      [
        { text: "📝 问题反馈", url: `${githubUrl}/issues/new` },
        { text: "ℹ️ 关于项目", callback_data: "about" },
      ],
    ],
  };
}

function backButton() {
  return { inline_keyboard: [[{ text: "⬅️ 返回主菜单", callback_data: "menu" }]] };
}

async function send(env, chatId, text, replyMarkup) {
  const body = {
    chat_id: chatId,
    text,
    parse_mode: "HTML",
    disable_web_page_preview: false,
  };
  if (replyMarkup) body.reply_markup = replyMarkup;
  return telegram(env, "sendMessage", body);
}

async function showLatest(env, chatId) {
  const release = await github(env, "/releases/latest");
  const apk = (release.assets || []).find((asset) =>
    asset.name.toLowerCase().endsWith(".apk"),
  );
  const downloadUrl = apk?.browser_download_url || release.html_url;
  const size = apk ? `${(apk.size / 1024 / 1024).toFixed(1)} MB` : "请在 Release 页面查看";
  const text =
    "<b>🎵 MusicConverter 最新正式版</b>\n\n" +
    `📦 版本：<b>${escapeHtml(release.tag_name)}</b>\n` +
    `📅 发布：${escapeHtml((release.published_at || "").slice(0, 10))}\n` +
    `💾 大小：${size}\n` +
    "📱 系统要求：Android 10 及以上";
  return send(env, chatId, text, {
    inline_keyboard: [
      [{ text: "📥 下载正式版 APK", url: downloadUrl }],
      [{ text: "📋 Release 页面", url: release.html_url }],
      [{ text: "⬅️ 返回主菜单", callback_data: "menu" }],
    ],
  });
}

async function showChangelog(env, chatId) {
  const release = await github(env, "/releases/latest");
  let body = (release.body || "本次发布暂未填写更新说明。").trim();
  if (body.length > 3000) body = `${body.slice(0, 3000)}…`;
  return send(
    env,
    chatId,
    `<b>🆕 ${escapeHtml(release.tag_name)} 更新内容</b>\n\n${escapeHtml(body)}`,
    backButton(),
  );
}

async function showStatus(env, chatId) {
  const data = await github(env, "/actions/workflows/android-apk.yml/runs?per_page=1");
  const run = data.workflow_runs?.[0];
  if (!run) return send(env, chatId, "暂时没有构建记录。", backButton());
  const state = run.conclusion || run.status || "unknown";
  const icon = { success: "✅", failure: "❌", cancelled: "⚪", in_progress: "⏳" }[state] || "ℹ️";
  return send(
    env,
    chatId,
    "<b>🛠 最近一次构建</b>\n\n" +
      `${icon} 状态：${escapeHtml(state)}\n` +
      `🌿 分支：${escapeHtml(run.head_branch || "-")}\n` +
      `📝 提交：${escapeHtml((run.head_sha || "").slice(0, 7))}`,
    {
      inline_keyboard: [
        [{ text: "查看构建详情", url: run.html_url }],
        [{ text: "⬅️ 返回主菜单", callback_data: "menu" }],
      ],
    },
  );
}

async function action(env, chatId, name) {
  const { githubUrl, communityUrl } = config(env);
  if (name === "start" || name === "menu") {
    return send(env, chatId, "<b>🎵 MusicConverter 助手</b>\n\n请选择需要的功能：", menu(env));
  }
  if (name === "latest") return showLatest(env, chatId);
  if (name === "changelog") return showChangelog(env, chatId);
  if (name === "status") return showStatus(env, chatId);
  if (staticText[name]) return send(env, chatId, staticText[name], backButton());
  if (name === "github") return send(env, chatId, `💻 ${githubUrl}`);
  if (name === "group") return send(env, chatId, `👥 ${communityUrl}`);
  if (name === "feedback") return send(env, chatId, `📝 ${githubUrl}/issues/new`);
  return send(env, chatId, "未识别的命令，请发送 /start 打开菜单。");
}

async function safeAction(env, chatId, name) {
  try {
    await action(env, chatId, name);
  } catch (error) {
    console.error(`Action ${name} failed`, error?.stack || error);
    const { githubUrl } = config(env);
    const fallback = {
      changelog: {
        text: "⚠️ 暂时无法读取更新内容，请通过 GitHub Release 查看。",
        url: `${githubUrl}/releases/latest`,
        label: "📋 查看更新内容",
      },
      status: {
        text: "⚠️ 暂时无法读取构建状态，请通过 GitHub Actions 查看。",
        url: `${githubUrl}/actions/workflows/android-apk.yml`,
        label: "🛠 查看构建状态",
      },
    }[name];
    if (fallback) {
      await send(env, chatId, fallback.text, {
        inline_keyboard: [
          [{ text: fallback.label, url: fallback.url }],
          [{ text: "⬅️ 返回主菜单", callback_data: "menu" }],
        ],
      });
      return;
    }
    await send(env, chatId, "⚠️ 请求处理失败，请稍后重试。", backButton());
  }
}

async function handleUpdate(env, update) {
  if (update.callback_query) {
    const query = update.callback_query;
    await telegram(env, "answerCallbackQuery", { callback_query_id: query.id });
    if (query.message?.chat?.id) {
      await safeAction(env, query.message.chat.id, query.data || "menu");
    }
    return;
  }
  const message = update.message || update.channel_post;
  const text = message?.text?.trim();
  if (!text?.startsWith("/")) return;
  const command = text.split(/\s+/, 1)[0].slice(1).split("@", 1)[0].toLowerCase();
  await safeAction(env, message.chat.id, command);
}

async function setup(request, env) {
  const missing = ["TG_BOT_TOKEN", "TG_WEBHOOK_SECRET"].filter(
    (name) => !env[name],
  );
  if (missing.length) {
    return Response.json(
      { ok: false, error: "Missing Worker runtime secrets", missing },
      { status: 503 },
    );
  }
  const auth = request.headers.get("authorization");
  if (auth !== `Bearer ${env.TG_WEBHOOK_SECRET}`) {
    return new Response("Unauthorized", { status: 401 });
  }
  const webhookUrl = new URL("/webhook", request.url).toString();
  const webhook = await telegram(env, "setWebhook", {
    url: webhookUrl,
    secret_token: env.TG_WEBHOOK_SECRET,
    allowed_updates: ["message", "callback_query", "channel_post"],
    drop_pending_updates: true,
  });
  await telegram(env, "setMyCommands", { commands });
  return Response.json({ ok: true, webhook, webhookUrl });
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/") {
      return Response.json({ ok: true, service: "MusicConverter Telegram Bot" });
    }
    if (request.method === "POST" && url.pathname === "/setup") {
      return setup(request, env);
    }
    if (request.method !== "POST" || url.pathname !== "/webhook") {
      return new Response("Not Found", { status: 404 });
    }
    if (!env.TG_BOT_TOKEN || !env.TG_WEBHOOK_SECRET) {
      const missing = ["TG_BOT_TOKEN", "TG_WEBHOOK_SECRET"].filter(
        (name) => !env[name],
      );
      return Response.json(
        { ok: false, error: "Missing Worker runtime secrets", missing },
        { status: 503 },
      );
    }
    if (
      request.headers.get("x-telegram-bot-api-secret-token") !==
      env.TG_WEBHOOK_SECRET
    ) {
      return new Response("Unauthorized", { status: 401 });
    }
    const update = await request.json();
    ctx.waitUntil(
      handleUpdate(env, update).catch((error) => console.error(error.stack || error)),
    );
    return new Response("OK");
  },
};
