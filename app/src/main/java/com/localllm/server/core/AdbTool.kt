package com.localllm.server.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * `adb`：通过无线调试执行 adb 命令（自动连接，配对一次即可长期用）。
 *
 * 为什么要把它单独做成工具：这是本机唯一"不 root、不用电脑"就能拿到 shell 级权限的通道——
 * 免点击装 APK、`screencap` 截屏、`input tap/swipe/text` 精确操作、`uiautomator dump` 拿控件树、
 * `am start` 启动别的 App、`dumpsys` 查系统状态。配合 OCR 与无障碍，才谈得上"操控别的 App"。
 *
 * 安全边界要说清：它等价于 adb shell，能改系统状态、能装/卸应用、能操作别的 App 的数据可见面。
 * 所以调用前一律走 [ToolGuard] 确认；卸载/清除数据/删除文件这类按 DANGER 每次必问。
 */
object AdbTool {

    const val NAME = "adb"

    fun toolJson(): JSONObject = JSONObject().apply {
        put("type", "function")
        put(
            "function", JSONObject().apply {
                put("name", NAME)
                put(
                    "description",
                    "通过无线调试执行 adb 命令（App 会自动发现端口并连接，用户只需在最开始配对一次）。" +
                            "**能用命令解决的事优先用这条通道**：改设置、开关蓝牙/WiFi、装应用、查电量与状态、" +
                            "读写文件、看前台是谁——一条命令就完事，比点界面快一个量级，也不会有点击落空的风险。" +
                            "**常用**：`devices`（看有没有连上）、`shell input tap X Y` / `shell input swipe x1 y1 x2 y2 300` / " +
                            "`shell input text 你好`、`shell screencap -p /sdcard/shot.png`（然后用 file_* 或 OCR 看）、" +
                            "`shell uiautomator dump /sdcard/ui.xml`（拿控件树定位按钮坐标）、" +
                            "`shell am start -n 包名/活动名`（启动别的 App）、`shell pm list packages`、" +
                            "`install -r 文件路径`（**免点击**安装，比 install_apk 省一次确认）、`uninstall 包名`、" +
                            "`shell dumpsys window | grep mCurrentFocus`（看前台是谁）。" +
                            "**通道规矩（重要）**：点屏统一用本工具的 shell input（tap/swipe/text/keyevent）。" +
                            "" +
                            "" +
                            "" +
                            "本工具真正独有的活：装 APK、pm、dumpsys、am start、screencap 存档。" +
                            "**锁屏/灭屏时**：照常执行，但黑屏上点不中——需要时先 `shell input keyevent 224` 唤醒" +
                            "（这条不算点屏，不受通道互斥限制），无密码设备可 `shell wm dismiss-keyguard` 解屏；" +
                            "有密码锁会停在锁屏界面，按实际情况继续。" +
                            "**安全**：这条通道等价于 shell 权限，卸载/清除数据/删除文件/改系统设置会被要求用户确认（每次都问）；" +
                            "用户点过「总是允许」后本会话内不再逐条询问。" +
                            "操作屏幕时用户可能正在用手机，一次只发一条命令、发完确认结果再继续。" +
                            "如果报「没发现端口 / 连不上」，说明无线调试关了或需要重新配对，" +
                            "把这句话原样告诉用户，不要反复重试。"
                )
                put(
                    "parameters", JSONObject().apply {
                        put("type", "object")
                        put(
                            "properties", JSONObject().apply {
                                put(
                                    "args", JSONObject().apply {
                                        put("type", "string")
                                        put("description", "adb 之后的参数，如 `shell input tap 500 600` 或 `devices`")
                                    }
                                )
                                put(
                                    "timeout", JSONObject().apply {
                                        put("type", "integer")
                                        put("description", "超时秒数，5–600，默认 60（install 这类给大一点）")
                                    }
                                )
                            }
                        )
                        put("required", JSONArray().put("args"))
                    }
                )
            }
        )
    }
}
