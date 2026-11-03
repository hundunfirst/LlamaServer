package com.localllm.server.core

/**
 * 危险操作确认：把「模型打算执行的动作」翻成一句人话 + 一个风险档，
 * 交给界面向用户要一次点头。
 *
 * 判据刻意做粗——误报的代价只是多点一次「允许」，漏报的代价是文件被删。
 * 风险档只决定能不能「本会话免打扰」：DANGER 每次都问，WRITE 可以一键放行同类。
 */
object ToolGuard {

    enum class Risk { WRITE, DANGER }

    data class Verdict(
        /** 弹窗标题，带问号，直接当界面文案用 */
        val title: String,
        /** 主体内容的标签（命令 / 路径 / 参数） */
        val subjectLabel: String,
        /** 要展示给用户看的主体内容 */
        val subject: String,
        /** 为什么问这一句：露出危险点，而不是笼统地说「有风险」 */
        val reason: String,
        val risk: Risk,
        /** 本会话「总是允许」的键；null = 不给这个选项（危险命令） */
        val autoKey: String?,
    )

    /** 命中第一条即定级；顺序从最要命的往下排 */
    private val dangerRules: List<Pair<Regex, String>> = listOf(
        Regex("""\brm\s""") to "删除文件",
        Regex("""\b(rmdir|shred|unlink|truncate)\b""") to "删除或清空文件",
        Regex("""\b(dd|mkfs\.[a-z0-9]+|mke2fs|fdisk|sfdisk|parted|wipefs|blkdiscard)\b""") to "直接操作磁盘分区",
        Regex("""\b(chmod|chown|chgrp|setfacl)\b""") to "修改权限或所有者",
        Regex("""\b(sudo|su|doas)\b""") to "提权执行",
        Regex("""\b(reboot|shutdown|halt|poweroff)\b""") to "重启或关机",
        Regex("""\b(kill|pkill|killall)\b""") to "结束进程",
        Regex("""\|\s*(sudo\s+)?(ba|z|k|da)?sh\b""") to "把下载到的内容直接交给 shell 执行",
        // 排除 2>/dev/null 这类丢弃输出的写法：它最常见，也完全无害
        Regex("""(?<![0-9>])>\s*/(?!dev/null)(system|vendor|data|etc|proc|sys|dev)\b""") to "写入系统目录",
        Regex("""\b(mv|cp|tee)\s[^\n]*\s/(system|vendor)\b""") to "改写系统目录",
        Regex(""":\(\)\s*\{.*\|.*&.*\}""") to "fork 炸弹",
        Regex("""\bgit\s+(reset\s+--hard|clean\s+-[a-z]*f|checkout\s+--\s|push\s+[^\n]*--force)""") to
                "破坏性 git 操作（丢掉未提交的改动）",
    )

    private val mcpDangerWords = Regex(
        """(delete|remove|write|edit|update|patch|move|rename|exec|run|shell|command|upload|install|kill|drop|truncate|create)""",
        RegexOption.IGNORE_CASE
    )

    private const val SUBJECT_MAX = 2000

    private fun clip(s: String): String =
        if (s.length <= SUBJECT_MAX) s else s.take(SUBJECT_MAX) + "\n…（已截断，共 ${s.length} 字）"

    /** `run_bash`：全部拦截，再按命令内容分档；空命令不用问（执行会直接报错） */
    fun assessBash(command: String): Verdict? {
        val cmd = command.trim()
        if (cmd.isBlank()) return null
        val hit = dangerRules.firstOrNull { (re, _) -> re.containsMatchIn(cmd) }
        return if (hit != null) {
            Verdict(
                title = "允许执行这条命令？",
                subjectLabel = "命令",
                subject = clip(cmd),
                reason = "这条命令会${hit.second}，无法撤销。",
                risk = Risk.DANGER,
                autoKey = null,
            )
        } else {
            Verdict(
                title = "允许执行命令？",
                subjectLabel = "命令",
                subject = clip(cmd),
                reason = "命令会在内置 Linux 环境里真的跑起来，可能改动文件或环境。",
                risk = Risk.WRITE,
                autoKey = "bash",
            )
        }
    }

    /**
     * `adb`：等价 shell 权限。
     *
     * 分档的关键在**打断成本**：`input tap/swipe/text` 是 UI 自动化的主力，一秒钟能发好几条，
     * 如果每条都弹窗，用户就得在自己手机上反复点「允许」——而 agent 正在操作屏幕，
     * 两边抢界面必然打架（真机反馈过）。所以 UI 操作归 WRITE 档（一次「总是允许」之后不再问）；
     * 卸载、清数据、删文件、改系统设置、重启这些真正会毁东西的，仍是 DANGER 每次必问。
     */
    fun assessAdb(args: String): Verdict {
        val cmd = args.trim()
        val danger = listOf(
            Regex("""\buninstall\b""") to "卸载应用",
            Regex("""\bpm\s+(clear|disable|uninstall)\b""") to "清除数据或禁用应用",
            Regex("""\brm\s""") to "删除文件",
            Regex("""\bshell\s+(reboot|shutdown)\b""") to "重启或关机",
            Regex("""\bshell\s+settings\s+put\b""") to "修改系统设置",
            Regex("""\b(root|remount)\b""") to "尝试提权",
        ).firstOrNull { (re, _) -> re.containsMatchIn(cmd) }
        if (danger != null) {
            return Verdict(
                title = "允许执行这条 adb 命令？",
                subjectLabel = "adb",
                subject = clip(cmd),
                reason = "这条命令会${danger.second}。adb 等价于 shell 权限，影响整台手机而不只是本 App。",
                risk = Risk.DANGER,
                autoKey = null,
            )
        }
        val uiDriving = Regex("""\bshell\s+input\s+(tap|swipe|text|keyevent|press|roll)""")
            .containsMatchIn(cmd)
        return Verdict(
            title = if (uiDriving) "允许它操作屏幕？" else "允许执行 adb 命令？",
            subjectLabel = "adb",
            subject = clip(cmd),
            reason = if (uiDriving)
                "会在当前屏幕上模拟点击/滑动/输入，作用的是**前台那个 App**。" +
                        "选「总是允许」后本会话不再逐条询问，免得它一边操作你一边点确认。"
            else "会通过无线调试在手机上执行，等价于 shell 权限（能读系统状态、操作别的 App）。",
            risk = Risk.WRITE,
            // UI 操作单独一个键：放行「操作屏幕」不等于放行所有 adb
            autoKey = if (uiDriving) "adb-ui" else "adb",
        )
    }

    /**
     * `screen`（无障碍）：读屏/截图不用问；点、滑、输入、按键按 WRITE 档问一次。
     * 和 adb 的 UI 操作共用一个「本会话放行」的键（`adb-ui`）：用户对"让它操作屏幕"的
     * 授权是一次性的，不该因为换了通道（adb / 无障碍）就再问一遍。
     */
    fun assessA11y(action: String, text: String): Verdict? {
        return when (action.lowercase()) {
            // 只读：看屏幕、截图，不打扰
            "tree", "shot" -> null
            "input" -> Verdict(
                title = "允许它输入文字？",
                subjectLabel = "内容",
                subject = clip(text),
                reason = "会写进当前焦点输入框，等同你在这台手机上打字。",
                risk = Risk.WRITE,
                autoKey = "adb-ui",
            )
            else -> Verdict(
                title = "允许它操作屏幕？",
                subjectLabel = "动作",
                subject = clip("$action ${if (text.isNotBlank()) text else ""}".trim()),
                reason = "会在当前屏幕上模拟点击/滑动/按键，作用的是**前台那个 App**。",
                risk = Risk.WRITE,
                autoKey = "adb-ui",
            )
        }
    }

    /** 文件工具的写类动作；读/列/搜返回 null（不用打扰用户） */
    fun assessFile(tool: String, args: org.json.JSONObject): Verdict? {
        val path = args.optString("path")
        return when (tool) {
            FileTools.TOOL_WRITE -> {
                val body = args.optString("content")
                Verdict(
                    title = "允许写入文件？",
                    subjectLabel = "路径",
                    subject = clip(path),
                    reason = "会创建或整段覆盖这个文件（写入 ${body.length} 字）。原内容不保留。",
                    risk = Risk.WRITE,
                    autoKey = "file_write",
                )
            }

            FileTools.TOOL_PATCH -> Verdict(
                title = "允许修改文件？",
                subjectLabel = "路径",
                subject = clip(path),
                reason = "会就地替换文件里的一段内容（删 ${args.optString("old").length} 字，写 " +
                        "${args.optString("new").length} 字）。",
                risk = Risk.WRITE,
                autoKey = "file_patch",
            )

            else -> null
        }
    }

    /**
     * MCP 工具：名字看不出危险性的（比如 list_issues、search）不打扰；
     * 带写/删/执行语义的按 WRITE 档问一次。
     */
    fun assessMcp(tool: String, args: org.json.JSONObject): Verdict? {
        if (!mcpDangerWords.containsMatchIn(tool)) return null
        return Verdict(
            title = "允许调用工具 $tool ？",
            subjectLabel = "参数",
            subject = clip(args.toString(2)),
            reason = "这个工具可能改动数据或执行操作，结果由外部服务决定。",
            risk = Risk.WRITE,
            autoKey = "mcp:$tool",
        )
    }
}
