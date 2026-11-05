package com.localllm.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动打开无障碍时写的命令。
 *
 * 关键约束：`enabled_accessibility_services` 是全系统共用的一串，
 * 我们只能往后面**追加**——整串覆盖会把用户自己的 TalkBack 之类顶掉。
 */
class A11ySelfEnableTest {

    private val me = "com.localllm.server/com.localllm.server.core.A11yService"

    @Test
    fun `没登记过时追加自己并打开总开关`() {
        val cmds = A11ySelfEnable.plan("null", me)
        assertEquals(2, cmds.size)
        assertTrue(cmds[0].contains("enabled_accessibility_services"))
        assertTrue(cmds[0].contains(me))
        assertEquals("shell settings put secure accessibility_enabled 1", cmds[1])
    }

    @Test
    fun `别动用户已有的无障碍服务`() {
        val talkback = "com.google.android.marvin.talkback/.TalkBackService"
        val cmds = A11ySelfEnable.plan("$talkback:", me)
        assertTrue(cmds[0].contains(talkback))
        assertTrue(cmds[0].contains(me))
        assertTrue(cmds[0].indexOf(talkback) < cmds[0].indexOf(me))
        // 中间不能出现空段（冒号拼接错位会让系统认不出任何一个服务）
        assertFalse(cmds[0].contains("::"))
    }

    @Test
    fun `已经登记过就只补总开关`() {
        val cmds = A11ySelfEnable.plan(me, me)
        assertEquals(1, cmds.size)
        assertEquals("shell settings put secure accessibility_enabled 1", cmds[0])
    }

    @Test
    fun `空值和多段都能处理`() {
        assertTrue(A11ySelfEnable.plan("", me)[0].contains(me))
        val two = A11ySelfEnable.plan("a/.A:b/.B", me)
        assertTrue(two[0].contains("a/.A:b/.B:"))
    }
}
