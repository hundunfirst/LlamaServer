package com.localllm.server.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 通道闸门：两条通道不能同时对同一屏下手，锁屏时也不该硬点。
 */
class PhoneChannelTest {

    @Before
    fun reset() {
        PhoneChannel.reset()
    }

    @Test
    fun `adb 的界面命令能认出来`() {
        assertTrue(PhoneChannel.adbTouchesUi("shell input tap 500 600"))
        assertTrue(PhoneChannel.adbTouchesUi("shell input swipe 100 200 100 800 300"))
        assertTrue(PhoneChannel.adbTouchesUi("shell input text hello"))
        assertTrue(PhoneChannel.adbTouchesUi("shell uiautomator dump /sdcard/ui.xml"))
        assertTrue(PhoneChannel.adbTouchesUi("shell screencap -p /sdcard/a.png"))
    }

    @Test
    fun `adb 的非界面命令不算点屏`() {
        assertFalse(PhoneChannel.adbTouchesUi("devices"))
        assertFalse(PhoneChannel.adbTouchesUi("install -r /sdcard/a.apk"))
        assertFalse(PhoneChannel.adbTouchesUi("shell pm list packages"))
        assertFalse(PhoneChannel.adbTouchesUi("shell am start -n com.x/.Main"))
        assertFalse(PhoneChannel.adbTouchesUi("shell dumpsys window"))
    }

    @Test
    fun `主动亮屏放行`() {
        assertFalse(PhoneChannel.adbTouchesUi("shell input keyevent 224"))
        assertFalse(PhoneChannel.adbTouchesUi("shell input keyevent 26"))
        assertFalse(PhoneChannel.adbTouchesUi("shell wm dismiss-keyguard"))
    }

    @Test
    fun `读屏和截图不算改界面`() {
        assertTrue(PhoneChannel.a11yTouchesUi("tap"))
        assertTrue(PhoneChannel.a11yTouchesUi("click"))
        assertTrue(PhoneChannel.a11yTouchesUi("input"))
        assertTrue(PhoneChannel.a11yTouchesUi("key"))
        assertFalse(PhoneChannel.a11yTouchesUi("tree"))
        assertFalse(PhoneChannel.a11yTouchesUi("shot"))
    }

    @Test
    fun `一条通道占了就把另一条挡住`() {
        assertNull(PhoneChannel.reject(PhoneChannel.Lane.A11Y))
        assertNull(PhoneChannel.reject(PhoneChannel.Lane.ADB))

        PhoneChannel.mark(PhoneChannel.Lane.A11Y)
        assertNull(PhoneChannel.reject(PhoneChannel.Lane.A11Y))
        assertNotNull(PhoneChannel.reject(PhoneChannel.Lane.ADB))
        // 挡住的那句话要能指路，否则模型只会换个别的方式瞎试
        assertTrue(PhoneChannel.reject(PhoneChannel.Lane.ADB)!!.contains("screen"))
    }

    @Test
    fun `失败不占通道`() {
        // 没 mark 过（无障碍没连上、adb 失败）：换个通道仍然是通的
        assertNull(PhoneChannel.reject(PhoneChannel.Lane.ADB))
        PhoneChannel.mark(PhoneChannel.Lane.ADB)
        assertNotNull(PhoneChannel.reject(PhoneChannel.Lane.A11Y))
        PhoneChannel.reset()
        assertNull(PhoneChannel.reject(PhoneChannel.Lane.A11Y))
    }
}
