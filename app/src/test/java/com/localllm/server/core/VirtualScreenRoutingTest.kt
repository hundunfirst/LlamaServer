package com.localllm.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 后台屏的命令改写规则。
 *
 * 这几个函数是"绝不碰用户主屏"这条要求的实际落点，改错了就是主屏被抢，
 * 真机上一眼看不出来（它只是"切走了"），所以用单测钉住。
 */
class VirtualScreenRoutingTest {

    private val display = 31

    // ---- input 命令改走注入 ----

    @Test
    fun `把 input 命令翻成注入指令`() {
        assertEquals("tap 100 200", VirtualScreen.parseInputCommand("shell input tap 100 200"))
        // 模型自己写了 -d，也要截下来（那条命令在虚拟屏上无效）
        assertEquals("tap 100 200", VirtualScreen.parseInputCommand("shell input -d 5 tap 100 200"))
        assertEquals("swipe 1 2 3 4", VirtualScreen.parseInputCommand("shell input swipe 1 2 3 4"))
        assertEquals("swipe 1 2 3 4 500", VirtualScreen.parseInputCommand("shell input swipe 1 2 3 4 500"))
        assertEquals("key 4", VirtualScreen.parseInputCommand("shell input keyevent 4"))
        assertEquals("key BACK", VirtualScreen.parseInputCommand("shell input keyevent BACK"))
        assertEquals("text 你好", VirtualScreen.parseInputCommand("shell input text 你好"))
    }

    @Test
    fun `不是 input 的命令不碰`() {
        assertNull(VirtualScreen.parseInputCommand("shell am start -n a/b"))
        assertNull(VirtualScreen.parseInputCommand("shell settings get global x"))
        assertNull(VirtualScreen.parseInputCommand("devices"))
        // 参数不够的也别瞎改
        assertNull(VirtualScreen.parseInputCommand("shell input tap 100"))
    }

    // ---- adb 界面命令改投后台屏 ----

    @Test
    fun `am start 补上屏号`() {
        assertEquals(
            "shell am start --display 31 -n com.a/.B",
            VirtualScreen.reroute("shell am start -n com.a/.B", display)
        )
        assertEquals(
            "shell am start --display 31 -a android.intent.action.VIEW -d https://x",
            VirtualScreen.reroute("shell am start -a android.intent.action.VIEW -d https://x", display)
        )
    }

    @Test
    fun `模型自己写的 display 一律纠正`() {
        // 真机上模型写过 --display 0（就是主屏），这一条最危险
        assertEquals(
            "shell am start --display 31 -n com.a/.B",
            VirtualScreen.reroute("shell am start --display 0 -n com.a/.B", display)
        )
        assertEquals(
            "shell am start --display 31 -n com.a/.B",
            VirtualScreen.reroute("shell am start --display=7 -n com.a/.B", display)
        )
        // 已经是本屏就不用改
        assertNull(VirtualScreen.reroute("shell am start --display 31 -n com.a/.B", display))
    }

    @Test
    fun `monkey 启动换成 am start`() {
        val got = VirtualScreen.reroute(
            "shell monkey -p com.xingin.xhs -c android.intent.category.LAUNCHER 1", display
        )
        assertNotNull(got)
        assertEquals(
            "shell am start --display 31 -a android.intent.action.MAIN " +
                    "-c android.intent.category.LAUNCHER -p com.xingin.xhs",
            got
        )
    }

    @Test
    fun `input 补屏号`() {
        assertEquals("shell input -d 31 tap 5 6", VirtualScreen.reroute("shell input tap 5 6", display))
        // 已经有 -d 的不动
        assertNull(VirtualScreen.reroute("shell input -d 9 tap 5 6", display))
    }

    @Test
    fun `分号连着写的命令逐条改`() {
        val got = VirtualScreen.reroute(
            "shell am start -n com.a/.B; shell input tap 1 2", display
        )
        assertEquals(
            "shell am start --display 31 -n com.a/.B; shell input -d 31 tap 1 2",
            got
        )
    }

    @Test
    fun `非界面命令不改`() {
        assertNull(VirtualScreen.reroute("shell settings get global bluetooth_on", display))
        assertNull(VirtualScreen.reroute("devices", display))
        assertNull(VirtualScreen.reroute("shell dumpsys window", display))
    }

    // ---- 会切走主屏的命令直接拦掉 ----

    @Test
    fun `HOME 与最近任务被拦`() {
        assertNotNull(VirtualScreen.blockedOnVirtual("shell input keyevent HOME"))
        assertNotNull(VirtualScreen.blockedOnVirtual("shell input keyevent KEYCODE_APP_SWITCH"))
        assertNotNull(VirtualScreen.blockedOnVirtual("shell input keyevent 187"))
        assertNotNull(VirtualScreen.blockedOnVirtual("shell input keyevent 3"))
        assertNotNull(VirtualScreen.blockedOnVirtual("shell am start -a android.intent.action.MAIN -c android.intent.category.HOME"))
    }

    @Test
    fun `普通命令不受影响`() {
        assertNull(VirtualScreen.blockedOnVirtual("shell input keyevent 4"))
        assertNull(VirtualScreen.blockedOnVirtual("shell input tap 1 2"))
        assertNull(VirtualScreen.blockedOnVirtual("shell am start -n com.a/.B"))
    }
}
