package com.localllm.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「撤销整轮」的分组规则：同一轮里一条路径可能被写好几次，
 * 要还原的是**这一轮最早那份**（= 动手之前的样子），而不是最后一次。
 */
class FileSnapshotsTest {

    private fun snap(path: String, ts: Long, turn: Long) =
        FileSnapshots.Snap(path = path, ts = ts, existed = true, content = "old", turn = turn)

    @Test
    fun `同轮同路径取最早_别的轮次不参与`() {
        val snaps = listOf(
            snap("a.txt", 100, 1),
            snap("a.txt", 200, 1),
            snap("b.txt", 150, 1),
            snap("a.txt", 300, 2),
        )
        val plan = FileSnapshots.planTurnRestore(snaps, 1)
        assertEquals(2, plan.size)
        assertEquals(100L, plan.first { it.path == "a.txt" }.ts)
        assertEquals(150L, plan.first { it.path == "b.txt" }.ts)
    }

    @Test
    fun `本轮没有记录时什么也不还原`() {
        assertTrue(FileSnapshots.planTurnRestore(listOf(snap("a.txt", 100, 2)), 3).isEmpty())
        assertTrue(FileSnapshots.planTurnRestore(emptyList(), 1).isEmpty())
    }

    @Test
    fun `多条路径按路径排序_结果稳定`() {
        val snaps = listOf(snap("z.txt", 120, 5), snap("c.txt", 110, 5))
        val paths = FileSnapshots.planTurnRestore(snaps, 5).map { it.path }
        assertEquals(listOf("c.txt", "z.txt"), paths)
    }
}
