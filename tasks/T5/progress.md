# T5 progress

## §1 Task identity
- task_id: T5
- short summary: TROUBLESHOOTING.md 追加 #22/#23 两条坑 + ChatScreen.kt latexToUnicode 增强（环境/符号/修饰）；`:app:compileDebugKotlin` 最终 **EXIT=0 BUILD SUCCESSFUL**。

## §2 Subagent intent
本子代理被要求在 LlamaServer 项目完成两件独立小事：(A) 在 docs/TROUBLESHOOTING.md 末尾按现有 `### #序号` 中文风格追加两条坑——#22 GitHub Actions CI 三连坑（packages 不能写 ninja、cmake --install 参数、gradlew 可执行位，commit 参考 db5ed3c/01246a4）与 #23 GitHub 国内连接间歇失败（连接层丢包非 DNS，诊断/缓解/重试策略）；(B) 在 ChatScreen.kt 的 latexToUnicode 做增量增强：支持 \begin{matrix|pmatrix|bmatrix|vmatrix|aligned|cases|array} 环境（去 begin/end 标记、\\→换行、&→分隔符）、补 lt/gt/leqslant/geqslant/varnothing/mathbb 等符号，确认既有样例不回归。约束：只许改这两个文件（ChatScreen 只动 latexToUnicode 及私有辅助），禁动 ChatClient/ChatViewModel/SettingsScreen/AppSettings/StatusScreen；不 git commit；编译 exit=0 才算过，报错若来自他人文件则重试并说明。

## §3 Files and code sections
- docs/TROUBLESHOOTING.md: 文件末尾（#20 条目之后）追加 **#22 GitHub Actions CI 三连坑（build.yml 首跑踩过）[CI]**（ninja 非 Android SDK 包、cmake --install 传构建目录非 CMAKE_INSTALL_PREFIX、gradlew 100644 需 `git update-index --chmod=+x`，commit 参考 db5ed3c/01246a4）与 **#23 GitHub 国内连接间歇失败（连接层丢包，非 DNS）[网络]**（DNS 正常 20.205.243.166 但 TCP 443 五次仅 1 成、诊断命令、挂代理缓解、镜像仅用于 clone 见 #21、push 退避重试、定性为网络环境问题非仓库配置）。用 Python UTF-8 追加以绕开 Edit 工具对 CRLF/LF 混合行尾的匹配失败。
- app/src/main/java/com/localllm/server/ui/ChatScreen.kt: 仅动 latexToUnicode 私有辅助区，未动 UI 及其它 .kt 文件。本代理改动：(1) LATEX_SYMBOLS 补 `lt→<、gt→>、leqslant→≤、geqslant→≥、varnothing→∅`（:200）；(2) 取内容命令组补 `mathbb、mathcal、mathfrak`（:275-278）；(3) when(name) 新增 `begin` 分支（:293-310，消费至 \end{同名}，行分隔 \\→\n，列分隔 &→matrix 族/array 用 `|`、cases/aligned 用全角空格，单元格 trim 后递归，k 跳过整个环境）与 `end` 分支（:311，孤立 \end 去标记）。同任务另一并行轮次在同一区域追加并已合并共存：符号扩展 to/gets/leftrightarrow/mapsto/propto/sim/equiv/ll/gg/ast/circ/bullet/oplus/otimes/neg/land/lor/emptyset/therefore/because/cong/doteq（:201-205）、big/Big/Bigl/Bigr/bigg/Bigg/biggl/biggr/Biggl/Biggr 进 strip 列表（:279-281）、hat/bar/vec 组合附加符（:282-292）。
- tasks/T5/progress.md（项目根）与会话记忆目录 tasks/T5/progress.md：本进度报告（双写）。

## §4 Verbatim commands
编译验证（最终一次 **EXIT=0 BUILD SUCCESSFUL**）：
```
$env:JAVA_HOME='C:\Program Files\Java\jdk-23'; $env:ANDROID_HOME='C:\Users\mingliang\AppData\Local\Android\Sdk'; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME; & 'C:\Users\mingliang\XiaomiMiMoProjects\LlamaServer\gradlew.bat' -p 'C:\Users\mingliang\XiaomiMiMoProjects\LlamaServer' :app:compileDebugKotlin --no-daemon
```

逻辑对照验证（Python 1:1 移植 + 样例，脚本在系统临时目录未入库）：
```
python "C:\Users\mingliang\AppData\Local\Temp\mimo_t5_latex_check.py"
```

## §5 Outcome and discoveries
- Outcome (success): 任务A/B 全部完成。必测样例经 Python 移植对照全过（`$$E_k = \frac{1}{2} \times 0.05 \times 33^2 \approx \boxed{27 \text{ J}}$$`→`Eₖ = 1/2 × 0.05 × 33² ≈ 27  J`；`\sqrt{2gh}`→`√2gh`；`\begin{aligned} a&=b+c \\ d&=e \end{aligned}`→`a　=b+c\nd　=e` 含换行且无 begin/end/&）。编译此前 6 次被并行代理在改的文件阻塞（时序 McpHttpClient.kt→DeviceCheckup.kt→StatusScreen.kt→ChatViewModel.kt:142 mcpHttpUrl），ChatScreen.kt 全程零错误；最终并行改动收敛后 **compileDebugKotlin EXIT=0 BUILD SUCCESSFUL**。未 git commit（按约束）。
- Discoveries that may matter for other tasks:
  - 本任务 T5 存在两轮/两个并行实现并已合流：环境支持 + lt/gt/leqslant/geqslant/varnothing/mathbb（本代理）与 hat/bar/vec + 大符号表扩展 + big 系 strip（另一轮）在 ChatScreen.kt 同区域共存无冲突。
  - TROUBLESHOOTING.md 行尾混用 LF/CRLF（尾部 #20 段为 CRLF），Edit 工具整段匹配会失败；中文内容用 Python io.open(utf-8) 追加更稳。PowerShell 5.1 无 heredoc（`python - <<EOF` 不可用），需先写临时 .py 再执行。
  - latexToUnicode 环境处理已知边界：同名环境嵌套取首个 `\end{env}`（indexOf 无深度计数）；环境体内 `\&` 转义与列分隔冲突未处理；pmatrix/bmatrix/vmatrix 只做列分隔 `|` 不画括号；hat 等对多字符基底退化为直接输出内容。
  - 并行开发期 `:app:compileDebugKotlin` 会暴露全模块错误，用“其它文件有详细类型错误而目标文件无任何 e: 行”可提前判定目标文件通过前端检查；多代理并行时错误会随他人保存而时移，轮询重试直至 BUILD SUCCESSFUL 即可。
  - 进度文件存在双写位置：项目根 `tasks/T5/progress.md` 与会话记忆 `...\memory\sessions\ses_ffe5ec12b5925ffeSkzXrvSroY\tasks\T5\progress.md`，校验器读的是会话记忆侧，两处需保持同步。
