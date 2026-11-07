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

    /**
     * 极简版屏工具：整个命令就是一个字符串，给本地小模型用。
     *
     * 真机对比很说明问题：同一个 4B 模型，`adb`（单字符串参数）三轮就能关蓝牙，
     * 而 [NAME]（枚举 + 多字段 + 嵌套数组）连"打开小红书"都拼不出来。
     * 所以给本地模型只暴露这一个，云端模型继续用结构化的 [NAME]。
     */
    const val CMD_NAME = "screen_cmd"

    fun commandToolJson(): JSONObject = JSONObject().apply {
        put("type", "function")
        put(
            "function", JSONObject().apply {
                put("name", CMD_NAME)
                put(
                    "description",
                    "在后台屏上操作手机（不打扰用户主屏）。**只传一个字符串命令**。\n" +
                            "**注意：下面的尖括号部分是「格式说明」，千万不要把里面的字当成任务内容**" +
                            "——要操作哪个 App、搜什么词，一律按用户这一次的要求来。\n" +
                            "· `tree` 看当前屏幕（返回 @e1 @e2 这样的编号，先看再用）\n" +
                            "· `open <应用名或包名>` 打开 App，写成 `open 应用名`，例如 `open com.android.settings`\n" +
                            "· `click <编号>` 点编号对应的元素，例如 `click 3`；也可以 `click <文字>` 按文字点\n" +
                            "· `search <关键词>` **搜索一条龙**：自己找搜索框、点开、写进去（最省步骤，优先用它）\n" +
                            "· `type <内容>` 往当前输入框输入\n" +
                            "· `swipe <x> <y> <x2> <y2>` 滑动；`scroll` 往下翻一屏；`up` 往上翻\n" +
                            "· `scroll_to <文字>` 一直往下翻直到看到它\n" +
                            "· `key back` 返回；`long <编号>` 长按\n" +
                            "每次动作后会直接告诉你新的屏幕，**不用再 tree**。\n" +
                            "典型顺序：`open <目标App>` → `search <要搜的词>` → `tree` 看结果。"
                )
                put(
                    "parameters", JSONObject().apply {
                        put("type", "object")
                        put(
                            "properties", JSONObject().apply {
                                put(
                                    "cmd", JSONObject().apply {
                                        put("type", "string")
                                        put("description", "要执行的命令，例如 `tree`、`open 小红书`、`click 3`")
                                    }
                                )
                            }
                        )
                        put("required", JSONArray().put("cmd"))
                    }
                )
            }
        )
    }

    fun toolJson(): JSONObject = JSONObject().apply {
        put("type", "function")
        put(
            "function", JSONObject().apply {
                put("name", NAME)
                put(
                    "description",
                    "看和点手机屏幕。**只在必须看/点界面时才用**——能用 adb 命令直接办成的事（改设置、" +
                            "开关蓝牙、装应用、查状态）不要用这里，点界面永远比一条命令慢、也更容易失败。\n" +
                            "默认在一块独立的后台虚拟屏上操作，用户的主屏不会被打扰；" +
                            "后台屏建不起来时会自动改在用户主屏上操作（设置里可以关掉这个降级）。" +
                            "动作由 action 指定：\n" +
                            "· `tree` 读当前屏幕：返回**带编号的元素**，形如 `@e3 按钮「搜索」 @540,120 id=search_box`；" +
                            "先用它再决定点哪个\n" +
                            "· `click` 点：text 填编号 `@e3`（推荐，直接点那个元素），或填屏幕上的文字（按文字找）\n" +
                            "· `type` 输入：text 写 `@e5 要输入的文字`（编号是输入框）；" +
                            "**挑不准编号时用 `type_any`**，text 直接写要输入的内容，代码会自己找输入框\n" +
                            "· `search` **搜索一条龙**：text 填关键词，代码自己找搜索框、点开、写进去——" +
                            "要搜索时**优先用它**，别自己一步步摸\n" +
                            "· `long_press` 长按：text 填 `@e3` 最准，或给 x,y（duration 毫秒，默认 800）\n" +
                            "· `double_tap` 双击：给出 x,y\n" +
                            "· `scroll` 滚动：text 留空往下滚一屏，写 `up` 往上滚；已知列表的编号时可写 `@e7` 只滚那个列表\n" +
                            "· `scroll_to` 滚到看到某段文字：text 填要查找的文字（最多滚 6 屏），长列表里翻找用它，" +
                            "比一屏一屏地问快得多\n" +
                            "· `tap` 按坐标点：给出 x,y（只在拿不到编号时用）\n" +
                            "· `swipe` 滑动：给出 x,y 到 x2,y2，duration 毫秒（默认 300）\n" +
                            "· `open` 打开 App：text 填包名（**别猜 Activity 名**，这里会自己找入口），" +
                            "打开后会直接把屏幕上的元素列表给你\n" +
                            "· `key` 系统键：text 填 back / enter / delete（home、最近任务会被拒，它们绑在用户主屏上）\n" +
                            "· `shot` 截图存档：**你看不到图片内容，别用它来确认结果**——确认用 tree，" +
                            "每步动作的结果里也自带最新屏幕" +
                            "**规矩**：一次只做一个动作，做完看回来的「当前屏幕」再继续；" +
                            "同一屏上要连做好几步时用 `steps` 一次给出来，比一步一问快得多。\n" +
                            "**照这个流程做**（最省轮次）：\n" +
                            "1) `{\"action\":\"open\",\"text\":\"com.xiaomi.shop\"}` → 打开 App 并拿到元素列表\n" +
                            "2) `{\"action\":\"click\",\"text\":\"@e3\"}` → 点编号 3 的元素\n" +
                            "3) `{\"action\":\"type\",\"text\":\"@e9 香氛机\"}` → 往编号 9 的输入框输入\n" +
                            "4) 每次动作的结果里**已经带上了新的屏幕**，不要再补一次 tree\n" +
                            "编号可以写成 `@e3`、`e3` 或直接 `3`，都认。\n" +
                            "**如果返回「拿不到后台屏画面」**，说明后台屏没建起来（无线调试没连、或容器没就绪），" +
                            "这时界面的活做不成——把这一点如实告诉用户，不要改去动用户的主屏。"
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
                                                .put("input").put("type").put("key").put("shot")
                                                .put("open").put("long_press").put("double_tap").put("scroll")
                                                .put("scroll_to").put("type_any").put("search")
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
                                // 一次多步：省掉「一步一问」的往返，翻列表/连续点几下的场景快一个量级
                                put("steps", JSONObject().apply {
                                    put("type", "array")
                                    put(
                                        "description",
                                        "一次做多步（**推荐**，最多 10 个）：每个元素形如 " +
                                                "{action:'click',text:'消息'} 或 {action:'swipe',x:540,y:1800,x2:540,y2:600}，" +
                                                "可选 wait（毫秒，默认 350）表示这一步之后等界面反应。\n" +
                                                "用它一轮就能完成「翻一页 → 点开一条 → 看内容」，比一步一问快得多；" +
                                                "任何一步失败会立刻停下并告诉你卡在哪一步。\n" +
                                                "给了 steps 就不用再传顶层的 action/text/x/y。"
                                    )
                                    put("items", JSONObject().apply {
                                        put("type", "object")
                                        put(
                                            "properties", JSONObject().apply {
                                                put("action", JSONObject().apply { put("type", "string") })
                                                put("text", JSONObject().apply { put("type", "string") })
                                                put("x", JSONObject().apply { put("type", "integer") })
                                                put("y", JSONObject().apply { put("type", "integer") })
                                                put("x2", JSONObject().apply { put("type", "integer") })
                                                put("y2", JSONObject().apply { put("type", "integer") })
                                                put("duration", JSONObject().apply { put("type", "integer") })
                                                put("wait", JSONObject().apply { put("type", "integer") })
                                                put("expect", JSONObject().apply {
                                                    put("type", "string")
                                                    put(
                                                        "description",
                                                        "这一步做完等这段文字出现（最多 timeout 秒）再做下一步；" +
                                                                "比固定等一会儿稳，也省掉再问一次屏"
                                                    )
                                                })
                                                put("expect_gone", JSONObject().apply {
                                                    put("type", "string")
                                                    put("description", "这一步做完等这段文字消失")
                                                })
                                                put("timeout", JSONObject().apply {
                                                    put("type", "integer")
                                                    put("description", "等待上限秒数，1–20，默认 6")
                                                })
                                            }
                                        )
                                    })
                                })
                            }
                        )
                        put("required", JSONArray())
                    }
                )
            }
        )
    }
}
