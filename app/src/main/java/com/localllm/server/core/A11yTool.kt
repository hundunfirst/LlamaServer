package com.localllm.server.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * `screen`：用无障碍服务"看"和"点"手机屏幕（不需要 adb、不需要网络）。
 *
 * 相比 adb 的优势是**不依赖 Wi-Fi**，出门也能用；代价是只能做 UI 层的事，
 * 装 APK / `pm` / `am` / `dumpsys` 仍然要 adb。
 */
object A11yTool {

    const val NAME = "screen"

    fun toolJson(): JSONObject = JSONObject().apply {
        put("type", "function")
        put(
            "function", JSONObject().apply {
                put("name", NAME)
                put(
                    "description",
                    "看和点手机屏幕（无障碍服务，不依赖 adb 与网络）。动作由 action 指定：\n" +
                            "· `tree` 读当前屏幕：列出有文字/可点/可输入的控件，带中心坐标；**先用它再决定点哪**\n" +
                            "· `click` 按文字点：action=click 且给出 text（优先精确控件的可点祖先，找不到会补一次坐标点击）\n" +
                            "· `tap` 按坐标点：给出 x,y（坐标来自 tree 或截图）\n" +
                            "· `swipe` 滑动：给出 x,y 到 x2,y2，duration 毫秒（默认 300；快速滑动给小值）\n" +
                            "· `input` 输入文字：给出 text，写进当前焦点输入框\n" +
                            "· `key` 系统键：text 填 back / home / recents / notifications\n" +
                            "· `shot` 截图：存到工作目录，返回图片路径（可以随后用 file_* 查看）\n" +
                            "操作别的 App 时：一次只做一个动作，做完用 `tree` 或 `shot` 确认结果再继续，" +
                            "别连着发一串点击（用户可能也在用手机，两边同时操作会打架）。" +
                            "要弹窗/装应用/查系统状态时用 adb 工具。"
                )
                put(
                    "parameters", JSONObject().apply {
                        put("type", "object")
                        put(
                            "properties", JSONObject().apply {
                                put(
                                    "action", JSONObject().apply {
                                        put("type", "string")
                                        put(
                                            "enum",
                                            JSONArray().put("tree").put("click").put("tap").put("swipe")
                                                .put("input").put("key").put("shot")
                                        )
                                        put("description", "要做的动作")
                                    }
                                )
                                put("text", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "click 时是目标文字的片段；input 时是要输入的内容；key 时是 back/home/recents")
                                })
                                put("x", JSONObject().apply { put("type", "integer"); put("description", "点击/滑动起点 X") })
                                put("y", JSONObject().apply { put("type", "integer"); put("description", "点击/滑动起点 Y") })
                                put("x2", JSONObject().apply { put("type", "integer"); put("description", "滑动终点 X") })
                                put("y2", JSONObject().apply { put("type", "integer"); put("description", "滑动终点 Y") })
                                put("duration", JSONObject().apply {
                                    put("type", "integer"); put("description", "手势时长毫秒，默认 300")
                                })
                            }
                        )
                        put("required", JSONArray().put("action"))
                    }
                )
            }
        )
    }
}
