package com.localllm.server.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * web_run：在 App 内置 WebView 沙箱里真的执行一次生成的单文件网页，
 * 把「错在第几行 / 死循环了没 / 画面画出来没」以纯文本回传给模型。
 *
 * 这是弱模型能自己把代码改对的前提——它看不到结果就永远不会修。
 * 输入二选一：path（读工作目录里的文件）或 code（直接跑一段 HTML）。
 */
object WebRunTool {

    const val NAME = "web_run"

    private const val MAX_HTML_CHARS = 400_000

    fun toolJson(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function", JSONObject()
                .put("name", NAME)
                .put(
                    "description",
                    "在沙箱里真的运行一个 HTML 页面，返回语法/运行期错误（含行号）、是否死循环、" +
                            "canvas 是否真的画出了内容、画面是否在动。生成或修改 HTML/JS 后必须调用它自检，" +
                            "按返回的错误逐条修，修完再调用一次，直到没有错误。"
                )
                .put(
                    "parameters", JSONObject()
                        .put("type", "object")
                        .put(
                            "properties", JSONObject()
                                .put(
                                    "path", JSONObject().put("type", "string")
                                        .put("description", "工作目录内的文件名，如 game.html")
                                )
                                .put(
                                    "code", JSONObject().put("type", "string")
                                        .put("description", "直接运行的完整 HTML 源码（未授权工作目录时用这个）")
                                )
                        )
                        .put("required", JSONArray())
                )
        )

    suspend fun execute(context: Context, args: JSONObject): String {
        // 注意：这里不能先检查 HtmlSandbox.available。面板是看到 lastHtml 才挂载的，
        // 而 lastHtml 由 run() 在开头设置——提前检查会永远为假，模型连门都进不去。
        val code = args.optString("code")
        val html = when {
            code.isNotBlank() -> code
            args.optString("path").isNotBlank() -> FileTools.execute(
                context, FileTools.TOOL_READ, JSONObject().put("path", args.optString("path"))
            )
            else -> throw IllegalStateException("需要 path 或 code 参数之一")
        }
        if (html.length > MAX_HTML_CHARS) {
            throw IllegalStateException("页面过大（${html.length} 字），超过 $MAX_HTML_CHARS 上限")
        }
        val report = HtmlSandbox.run(
            html, label = args.optString("path").ifBlank { "内联代码" }
        )
        // 详情也落日志：只记「错误=N」事后没法复盘到底是哪一行、两次是不是同一个错
        ServerProcessManager.appLog(
            "沙箱自检详情：存活=${report.alive} 错误=${report.errors.size} " +
                    "画布=${report.canvases.size} 动画=${report.animated} | " +
                    report.errors.joinToString(" ;; ").take(400)
        )
        return report.toToolText()
    }
}
