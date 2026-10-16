# 版本管理与发版流程

## 纪律（重要）

1. **发布即冻结**：一旦打过 tag / 发过 GitHub Release 的版本号，不允许在其上继续堆积改动。
2. **发布后的任何改动，先升版本号再提交**（在 `app/build.gradle.kts`）：

   | 版本 | versionCode | 状态 |
   |---|---|---|
   | 0.3.0 | 3 | 已发布 |
   | 0.3.1 | 4 | 已发布（tag `ddb337d`，GitHub Release + APK 附件） |
   | 0.3.2 | 5 | **开发中**（循环修复 / 内置搜索 / OCR / 隐私策略 / MCP 多服务器 / 消息删除 / 日志持久化） |

3. 提交信息里写清内容；**chore: bump version…** 单独一条提交，便于回溯。

## 发版步骤

```powershell
# 1) 确保 versionCode/versionName 已是目标版本（未发布过）
# 2) 构建签名 release 包
$env:JAVA_HOME='C:\Program Files\Java\jdk-23'; $env:ANDROID_HOME='C:\Users\<you>\AppData\Local\Android\Sdk'
.\gradlew.bat :app:assembleRelease --no-daemon
# 3) 归档
copy app\build\outputs\apk\release\app-release.apk dist\LlamaServer-v0.3.2-release.apk   # dist/ 不入库
# 4) 打 tag 并推送（SSH over 443）
git tag -a v0.3.2 -m "v0.3.2: <要点>"; git push origin master; git push origin v0.3.2
# 5) GitHub Release + 上传 APK 附件（api/uploads.github.com 可达时；令牌取自凭据库）
```

## 注意

- `dist/` 已在 `.gitignore`，APK 只本地归档、不进仓。
- 引擎（llama.cpp）版本升级单独记录在 `docs/UPGRADING_LLAMA.md`，不影响 App 版本号。
