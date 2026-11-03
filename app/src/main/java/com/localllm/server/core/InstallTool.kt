package com.localllm.server.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * `install_apk`：把编好的 APK 交给**系统安装器**。
 *
 * 为什么要有它：模型以前装 APK 走的是容器里的 `adb connect IP:端口` + `adb install`，
 * 那条路要无线调试的配对码（一次性）、要手抄每次都变的端口，失败了还得来回折腾
 * （见 HANDOFF 里「检查当前运行环境」那次的记录）。
 * 而 App 自己就在这台手机上——直接用系统安装器就行：不需要 adb、不需要配对、不需要端口。
 *
 * 代价是**必须用户点一次「安装」**（系统安装界面）。想零点击静默安装得有 Device Owner /
 * root / Shizuku，都不值得为这个功能引入。
 */
object InstallTool {

    const val NAME = "install_apk"

    fun toolJson(): JSONObject = JSONObject().apply {
        put("type", "function")
        put(
            "function", JSONObject().apply {
                put("name", NAME)
                put(
                    "description",
                    "把本地构建好的 APK 交给系统安装器（会弹出系统的安装确认界面，用户点一下即安装）。" +
                            "**装 APK 一律用它，不要用 adb**：无线调试需要一次性配对码和每次都变的端口，" +
                            "这条路在本机是多余的。" +
                            "构建完成后先确认文件存在（如 app/build/outputs/apk/debug/app-debug.apk），" +
                            "再调用本工具并传入它的路径。"
                )
                put(
                    "parameters", JSONObject().apply {
                        put("type", "object")
                        put(
                            "properties", JSONObject().apply {
                                put(
                                    "path", JSONObject().apply {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "APK 路径：工作目录下的相对路径（如 android-build/hello.apk）" +
                                                    "或真实绝对路径"
                                        )
                                    }
                                )
                            }
                        )
                        put("required", JSONArray().put("path"))
                    }
                )
            }
        )
    }

    /** @return 给模型看的一句话（成功/失败都写清楚下一步该做什么） */
    fun execute(context: Context, args: JSONObject): String {
        val path = args.optString("path").trim()
        if (path.isBlank()) return "错误：path 不能为空"
        if (!path.endsWith(".apk", ignoreCase = true)) return "错误：只支持 .apk 文件（收到：$path）"
        val exists = FileBytes.realFile(context, path) != null ||
                FileTools.documentFor(context, path)?.isFile == true
        if (!exists) {
            return "错误：找不到 $path。APK 必须落在工作目录（用户可见的那个文件夹）里，或者给出真实绝对路径；" +
                    "在 Linux 的 /root 或 /tmp 下构建的产物用户点不到，请把它复制到工作目录后再装"
        }
        return if (FileOpener.open(context, path)) {
            "已弹出系统安装界面：请让用户在手机上点「安装」。" +
                    "如果系统提示「不允许安装未知应用」，需要用户在设置里给本 App 打开该开关后重试。"
        } else {
            "错误：无法打开系统安装器（文件不可读，或系统拒绝）。可以改用 file_* 把 APK 放到工作目录，" +
                    "再让用户从产物卡里点「安装」"
        }
    }
}
