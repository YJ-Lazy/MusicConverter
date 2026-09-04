MusicConverter 2.0 GitHub 干净上传版

1. 解压本 ZIP。
2. 将 MusicConverter_v2.0_GitHub_Clean_1.10 文件夹“里面”的全部内容上传到仓库根目录。
3. 建议先清空仓库旧的 app/compressed-src、app/split-src、app/build-assets、scripts/restore-ci-inputs.sh 等历史还原文件，避免旧文件继续参与构建。
4. 上传后 main push 会自动运行 .github/workflows/android-apk.yml 并构建 Debug APK。
5. 正式 Release 需要 Actions Secrets：
   KEYSTORE_BASE64
   KEYSTORE_PASSWORD
   KEY_ALIAS
   KEY_PASSWORD
6. 推送 v2.0 tag 或手动 workflow_dispatch + publish_release=true，可生成签名 APK + SHA-256 + GitHub Release。

本包不包含你的 keystore 或任何 Secrets。
