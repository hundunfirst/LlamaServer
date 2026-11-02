package com.localllm.server.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * `run_bash`：在内置 Linux 环境（PRoot + Ubuntu）里执行命令。
 *
 * 这是「写→跑→看报错→改」闭环的最后一环：模型可以用它跑测试、lint、脚本、git、node，
 * 而不只是产出代码文本。首次使用会先解压环境并按需安装 git/curl/python3
 * （同步进行，可能需要几分钟；之后每次调用都很快）。
 *
 * **是持久会话**（[PersistentShell]）：同一个聊天会话里的多次 run_bash 共用一个常驻 bash，
 * `cd` / `export` / shell 函数 / 后台任务都会留下来。想清空状态就把 reset 设为 true。
 *
 * 安全定位：进程是 App 自己的 uid，rootfs 在 App 私有目录，proot 是用户态 chroot，
 * 不是内核级沙箱。命令里可以读 /storage（模型本来就能读授权目录），
 * 写入/删除也受同一套 SAF 与用户确认约束之外——这里是 shell，解释清楚边界：
 * 它和「用户在手机上跑终端」权限相当。
 */
object BashTool {

    const val NAME = "run_bash"

    fun toolJson(): JSONObject = JSONObject().apply {
        put("type", "function")
        put(
            "function", JSONObject().apply {
                put("name", NAME)
                put(
                    "description",
                    "在手机内置的 Linux 环境（Ubuntu）里执行 shell 命令并返回输出。" +
                            "用于：跑单元测试 / lint / 脚本、执行 git 操作、node 运行代码、看报错定位问题。" +
                            "**这是一个持久会话：cd、export、shell 函数、后台任务在下一次调用时仍然有效。**" +
                            "所以 cd 一次就够，后面直接写相对的后续命令；每次返回值第一行会告诉你当前目录。" +
                            "需要回到干净状态（清掉 cd/env）时把 reset 设为 true。" +
                            "**注意：这是与手机文件系统分开的 Linux 环境（home 是 /root），但用户的工作目录已经" +
                            "挂在这里的 /storage/emulated/0 下，且会话默认工作目录就是这个工作目录——" +
                            "所以命令里直接用文件名即可（如 python3 x.py、cat a.csv）；" +
                            "要读写用户文件请用 file_* 工具，不要 cd /root 或 ls / 去找用户的文件。**" +
                            "命令以 root 用户执行；不要用它做用户没要求的破坏性操作。" +
                            "超时会杀掉并重建会话（状态丢失），所以别跑长驻前台进程。" +
                            "**后台任务这么管**：启动时把输出重定向到文件（如 `node server.js > /tmp/srv.log 2>&1 &`）；" +
                            "`jobs -l` 看还在跑的有哪些（含 pid）；`tail -n 50 /tmp/srv.log` 看它的输出；" +
                            "`kill <pid>` 停掉（这条会弹框让用户确认，属于危险操作）。" +
                            "不要用 `nohup` 或直接把输出丢给会话（不重定向的话它的输出会混进你后面的每条命令）。" +
                            "首次调用会自动准备环境（解压 + 安装基础工具，可能几分钟，之后即用即走）。"
                )
                put(
                    "parameters", JSONObject().apply {
                        put("type", "object")
                        put(
                            "properties", JSONObject().apply {
                                put(
                                    "command", JSONObject().apply {
                                        put("type", "string")
                                        put("description", "要执行的 shell 命令，如：node test.js")
                                    }
                                )
                                put(
                                    "cwd", JSONObject().apply {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "切换到这个目录再执行（Linux 环境内路径）。会改变会话当前目录，" +
                                                    "后续调用仍在这个目录——不需要每次重复传"
                                        )
                                    }
                                )
                                put(
                                    "timeout", JSONObject().apply {
                                        put("type", "integer")
                                        put("description", "超时秒数，5–600，默认 60；超时会重建会话")
                                    }
                                )
                                put(
                                    "reset", JSONObject().apply {
                                        put("type", "boolean")
                                        put("description", "设为 true 先清空会话状态（cd/env/函数），再从工作目录重新开始")
                                    }
                                )
                            }
                        )
                        put("required", JSONArray().put("command"))
                    }
                )
            }
        )
    }

    fun execute(context: Context, sid: String, args: JSONObject): String {
        val command = args.optString("command").trim()
        if (command.isBlank()) return "错误：command 不能为空"
        val timeout = args.optInt("timeout", 60).coerceIn(5, 600)
        val reset = args.optBoolean("reset", false)
        // 会话的初始工作目录：user 的文件在工作目录里，而 bash 默认 cwd 是容器 home(/root)，
        // 两边不是同一棵树——不设成工作目录，模型就会用同一个文件名在 python 里报 FileNotFound。
        val initialCwd = LinuxRuntime.workdirAbsPath(context) ?: "/root"
        // cwd 参数只在模型明确给了才生效：每次调用都强行 cd 回工作目录的话，
        // 「先 cd 到项目里再连着跑几条」这件事就永远做不到
        val cdTo = args.optString("cwd").trim().ifBlank { null }

        // Linux 环境 bind 了 /storage（全盘访问），没授权的话命令会在看不到文件的地方跑，先拦住
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return "错误：还没有「读写存储」权限——Linux 环境里的 /storage 需要它（全盘文件访问）。" +
                    "请在 设置 → 文件工具 里点击「开启全盘访问」授权后重试。"
        }

        val prep = LinuxRuntime.prepare(context)
        if (prep.isNotEmpty()) return "Linux 环境未就绪：$prep"

        val r = PersistentShell.run(
            context = context,
            sid = sid,
            command = command,
            initialCwd = initialCwd,
            cdTo = cdTo,
            timeoutMs = timeout * 1000L,
            reset = reset,
        )

        val sb = StringBuilder()
        // 抬头告诉模型「你现在站在哪」：小模型不会自己 pwd，也就不知道相对路径指的是哪
        sb.append("（当前目录：")
            .append(r.cwd ?: if (r.died || r.timedOut) "未知（会话已重建）" else (cdTo ?: initialCwd))
            .append("）\n")
        if (r.died) sb.append("【这条命令让 shell 退出了（如 exit）：下一轮会自动重建，cd/env 已丢失】\n")
        if (r.rebuilt && !reset) {
            sb.append("（持久 shell 已重建：之前的 cd / export 不再有效）\n")
        }
        if (r.timedOut) sb.append("【命令超时（${timeout}s）：已终止并重建 shell，cd/env 状态已丢失】\n")
        if (r.exitCode != 0 && !r.timedOut && !r.died) sb.append("【命令退出码 ${r.exitCode}】\n")
        // 后台进程必须显式告诉模型：它们继承的是这条会话的 stdout，输出会混进后续命令
        r.backgroundJobs?.let { jobs ->
            sb.append("【后台进程仍在运行：pid $jobs —— 它的输出会混进后面的命令。")
                .append("看全部：`jobs -l`；看输出：启动时重定向到文件再 `tail -n 50 文件`；")
                .append("停掉：`kill <pid>`（会弹确认框）】\n")
        }
        sb.append(r.output)
        if (r.output.isBlank()) sb.append("（无输出）")
        if (r.truncated) sb.append("\n…（输出过多已截断：需要看全部就让它 grep / head / tail 缩小范围）")
        return sb.toString()
    }
}

