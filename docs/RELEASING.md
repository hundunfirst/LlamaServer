# 版本管理与发版流程

## 纪律（重要）

1. **发布即冻结**：一旦打过 tag / 发过 GitHub Release 的版本号，不允许在其上继续堆积改动。
2. **发布后的任何改动，先升版本号再提交**（在 `app/build.gradle.kts`）：

   | 版本 | versionCode | 状态 |
   |---|---|---|
   | 0.3.0 | 3 | 已发布 |
   | 0.3.1 | 4 | 已发布（tag `ddb337d`，GitHub Release + APK 附件） |
   | 0.3.2 | 5 | 已发布（循环修复 / 内置搜索 / OCR / 隐私策略 / MCP 多服务器 / 消息删除 / 日志持久化） |
   | 0.3.3 | 6 | 已发布 |
   | 0.3.4 | 7 | 未发布（引擎升级并入 0.3.5，跳过） |
   | 0.3.5 | 8 | 已发布（tag `v0.3.5`）：引擎升级到 llama.cpp v0.7.0 + 模型下载免责声明 |

   **什么时候该升大版本**：版本号表达的是对外契约变化，不是内部改动风险。出现下列之一才动 0.4.0——
   破坏性数据结构迁移、`targetSdk` 提升（换掉 28 那套执行方案）、默认模型代际切换、技能/工具协议改动。
   「换了引擎但参数与接口全没变」这种只算补丁位。

3. 提交信息里写清内容；**chore: bump version…** 单独一条提交，便于回溯。

## 发版前真机自测（四项，缺一不发）

这四项历史上出过问题，且都只能在真机上点出来：

1. **prism 引擎开关**：设置里打开「Bonsai 优化引擎（PrismML fork）」+ ngl=0，启动三元模型 →
   日志首行必须是 `引擎：prism（…）`。若是 `引擎：cpu`，说明开关没生效。
2. **GGUF 分类拦截**：模型页把投影器/草稿文件当主模型启动 → 必须被拦下并提示
   「不是主模型：这是多模态投影器…」，不能真的去加载。
3. **切会话不中断生成**：会话 A 发长回答 → 出字后立刻切到会话 B → A 必须继续生成，
   顶栏有运行中标记，切回 A 能看到完整结果。
4. **技能链生效标记**：应用一个技能/技能链 → 输入框上方出现常驻标记，技能弹窗里对应条目显示「✓ 生效中」。

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
