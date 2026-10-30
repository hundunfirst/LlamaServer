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
                    "在手机内置的 Linux 环境（Ubuntu）里执行一条 shell 命令并返回输出。" +
                            "用于：跑单元测试 / lint / 脚本、执行 git 操作、node 运行代码、看报错定位问题。" +
                            "命令在 /root 或指定目录下以 root 用户执行；不要用它做用户没要求的破坏性操作。" +
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
                                        put("description", "要执行的 shell 命令，如：cd demo && node test.js")
                                    }
                                )
                                put(
                                    "cwd", JSONObject().apply {
                                        put("type", "string")
                                        put("description", "工作目录（Linux 环境内路径），默认 /root")
                                    }
                                )
                                put(
                                    "timeout", JSONObject().apply {
                                        put("type", "integer")
                                        put("description", "超时秒数，5–600，默认 60")
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

    fun execute(context: Context, args: JSONObject): String {
        val command = args.optString("command").trim()
        if (command.isBlank()) return "错误：command 不能为空"
        val timeout = args.optInt("timeout", 60).coerceIn(5, 600)
        val cwd = args.optString("cwd").ifBlank { "/root" }

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

        val r = LinuxRuntime.exec(context, command, cwd = cwd, timeoutMs = timeout * 1000L)
        val out = if (r.output.length > 6000)
            r.output.take(6000) + "\n…（输出过长已截断，共 ${r.output.length} 字符）" else r.output
        return if (r.exitCode == 0) out
        else "【命令退出码 ${r.exitCode}】\n" + out
    }
}
