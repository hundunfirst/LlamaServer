package com.localllm.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 消息时间显示：往上翻会话时靠它判断「这条是什么时候发的」。
 * 现在时间一律显式传入，测试不跟系统时钟走。
 */
class MessageTimeLabelTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun weekdayOf(year: Int, month: Int, day: Int): String {
        val dow = java.time.LocalDate.of(year, month, day).dayOfWeek.value // 1=周一 … 7=周日
        return listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")[dow - 1]
    }

    private val now = at(2026, 11, 1, 15, 30)

    @Test
    fun `今天的消息只显示时分`() {
        assertEquals("14:23", ChatStore.stampLabel(at(2026, 11, 1, 14, 23), now))
    }

    @Test
    fun `昨天的消息带昨天前缀`() {
        assertEquals("昨天 14:23", ChatStore.stampLabel(at(2026, 10, 31, 14, 23), now))
    }

    @Test
    fun `一周内显示星期几`() {
        assertEquals(
            "${weekdayOf(2026, 10, 29)} 14:23",
            ChatStore.stampLabel(at(2026, 10, 29, 14, 23), now)
        )
    }

    @Test
    fun `今年更早显示几月几日`() {
        assertEquals("3月8日 09:05", ChatStore.stampLabel(at(2026, 3, 8, 9, 5), now))
    }

    @Test
    fun `跨年带年份`() {
        assertEquals("2025年12月31日 23:50", ChatStore.stampLabel(at(2025, 12, 31, 23, 50), now))
    }

    @Test
    fun `老数据没有时间戳就不显示`() {
        assertEquals("", ChatStore.stampLabel(0, now))
        assertEquals("", ChatStore.dayLabel(0, now))
    }

    @Test
    fun `日期分隔_今天昨天与更早`() {
        assertEquals("今天", ChatStore.dayLabel(at(2026, 11, 1, 2, 0), now))
        assertEquals("昨天", ChatStore.dayLabel(at(2026, 10, 31, 23, 59), now))
        assertEquals(weekdayOf(2026, 10, 26), ChatStore.dayLabel(at(2026, 10, 26, 8, 0), now))
        assertEquals("10月1日", ChatStore.dayLabel(at(2026, 10, 1, 8, 0), now))
        assertEquals("2024年5月1日", ChatStore.dayLabel(at(2024, 5, 1, 8, 0), now))
    }

    @Test
    fun `同一天才算同一天_按日历日不按二十四小时`() {
        // 前一晚 23:50 与今天 00:10 只差 20 分钟，但不是同一天，要插日期分隔
        assertFalse(ChatStore.sameDay(at(2026, 10, 31, 23, 50), at(2026, 11, 1, 0, 10)))
        assertTrue(ChatStore.sameDay(at(2026, 11, 1, 0, 10), at(2026, 11, 1, 23, 50)))
        // 没有时间戳的老数据不与任何消息算同一天
        assertFalse(ChatStore.sameDay(0, now))
    }

    @Test
    fun `耗时标签`() {
        assertEquals("", ChatStore.elapsedLabel(0))
        assertEquals("12秒", ChatStore.elapsedLabel(12_400))
        assertEquals("1分5秒", ChatStore.elapsedLabel(65_000))
        assertEquals("2分", ChatStore.elapsedLabel(120_000))
        assertEquals("13分20秒", ChatStore.elapsedLabel(800_000))
    }
}
