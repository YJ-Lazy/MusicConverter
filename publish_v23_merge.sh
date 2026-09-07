#!/usr/bin/env bash
set -euo pipefail

# MusicConverter v2.3 合并 + GitHub 上传脚本
# 使用方法：
# 1) 将本脚本放在 Downloads 目录
# 2) 确保已解压修正版源码目录：
#      MusicConverter-v2.3-full-source-fix1
# 3) Git Bash 中执行：
#      bash publish_v23_merge.sh

REPO_URL="https://github.com/YJ-Lazy/MusicConverter.git"
SOURCE_DIR="$HOME/Downloads/MusicConverter-v2.3-full-source-fix1"
REPO_DIR="$HOME/Downloads/MusicConverter-v2.3-release"
BRANCH="main"
TAG="v2.3"
COMMIT_MSG="fix: merge verified v2.3 source and mini player fix"
TAG_MSG="MusicConverter v2.3"

echo "========================================"
echo " MusicConverter v2.3 合并 / 上传"
echo "========================================"

if [ ! -d "$SOURCE_DIR" ]; then
  echo "错误：找不到修正版源码目录："
  echo "$SOURCE_DIR"
  echo
  echo "请先解压 MusicConverter-v2.3-full-source-fix1.zip 到 Downloads。"
  exit 1
fi

# 如果本地发布仓库不存在，自动 clone。
if [ ! -d "$REPO_DIR/.git" ]; then
  echo "[1/8] 克隆 GitHub 仓库..."
  rm -rf "$REPO_DIR"
  git clone "$REPO_URL" "$REPO_DIR"
else
  echo "[1/8] 使用已有 Git 仓库..."
fi

cd "$REPO_DIR"

echo "[2/8] 同步远程 main..."
git fetch origin
git checkout "$BRANCH"
git pull --ff-only origin "$BRANCH"

echo "[3/8] 合并修正版源码..."
# 只覆盖/新增修正版源码中的文件，不删除仓库中已有但修正版包里没有的文件。
# 这样可以保留 settings.gradle、update/update.json 等仓库文件。
cp -a "$SOURCE_DIR"/. "$REPO_DIR"/

# 保证 Gradle Wrapper 在 Git 中具有执行权限。
if [ -f gradlew ]; then
  chmod +x gradlew
  git update-index --chmod=+x gradlew 2>/dev/null || true
fi

echo
echo "合并后的 Git 状态："
git status --short
echo

echo "[5/8] 提交合并内容..."
git add -A

if git diff --cached --quiet; then
  echo "没有检测到新的文件变化，跳过 commit。"
else
  git commit -m "$COMMIT_MSG"
fi

echo "[6/8] 推送 main..."
git push origin "$BRANCH"

echo "[7/8] 更新 v2.3 标签..."
# 旧 v2.3 已经发布过，因此需要让标签指向当前修复后的 main。
if git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then
  echo "检测到远程已存在 $TAG。"
  read -r -p "是否删除旧的远程 $TAG 并重新发布？[y/N] " answer
  case "$answer" in
    y|Y|yes|YES)
      git tag -d "$TAG" 2>/dev/null || true
      git push origin ":refs/tags/$TAG"
      ;;
    *)
      echo "已保留旧标签。main 已上传，但不会重新触发 v2.3 Tag Release。"
      echo "操作结束。"
      exit 0
      ;;
  esac
else
  git tag -d "$TAG" 2>/dev/null || true
fi

git tag -a "$TAG" -m "$TAG_MSG"
git push origin "$TAG"

echo "[8/8] 完成。"
echo
echo "main 已推送，$TAG 已指向最新修复提交。"
echo "接下来请到 GitHub Actions 查看 Android APK Build & Release。"
