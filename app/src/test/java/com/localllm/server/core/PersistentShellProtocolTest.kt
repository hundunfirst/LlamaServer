package com.localllm.server.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedReader
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * 用真的 bash 跑一遍持久会话的协议：`cd` / `export` 真的跨两次调用保留吗？
 * 命令的 stdin 真的被接到 /dev/null 了吗（否则 `cat` 会吃掉哨兵行、永远不结束）？
 *
 * 这里不碰 Android/proot（跑不了），只验证「包装脚本 + 哨兵」这套机制本身；
 * 宿主没有 bash 的环境自动跳过，不让 CI 变红。
 */
class PersistentShellProtocolTest {

    private fun findBash(): String? {
        val candidates = buildList {
            add("bash")
            add("C:\\Program Files\\Git\\bin\\bash.exe")
            add("C:\\Program Files\\Git\\usr\\bin\\bash.exe")
        }
        return candidates.firstOrNull { exe ->
            runCatching {
                val p = ProcessBuilder(exe, "-c", "echo ok")
                    .redirectErrorStream(true).start()
                val ok = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
                if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly()
                ok
            }.getOrDefault(false)
        }
    }

    /** 和 PersistentShell 内部同一套：写脚本 → 逐行读到哨兵 */
    private class MiniSession(bash: String) : AutoCloseable {
        private val proc = ProcessBuilder(bash, "--noprofile", "--norc").start()
        private val queue = LinkedBlockingQueue<String>()

        init {
            Thread {
                runCatching {
                    proc.inputStream.bufferedReader(Charsets.UTF_8).use { r: BufferedReader ->
                        while (true) queue.put(r.readLine() ?: break)
                    }
                }
                queue.put("\u0000EOF")
            }.apply { isDaemon = true; start() }
        }

        private val out = proc.outputStream.bufferedWriter(Charsets.UTF_8)

        /** 发一条命令，返回 (退出码, PWD, 输出) */
        fun run(command: String, token: String, timeoutMs: Long = 15_000): Triple<Int, String?, String> {
            out.write(ShellProtocol.buildScript(command, null, token))
            out.flush()
            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val remain = deadline - System.currentTimeMillis()
                assertTrue("等待哨兵超时：$command", remain > 0)
                val line = queue.poll(remain, TimeUnit.MILLISECONDS)
                    ?: throw AssertionError("等待哨兵超时：$command")
                assertTrue("shell 意外退出：$command", line != "\u0000EOF")
                val sent = ShellProtocol.parseSentinel(line, token)
                if (sent != null) return Triple(sent.first, sent.second, sb.toString().trim())
                sb.append(line).append('\n')
            }
        }

        override fun close() {
            runCatching { out.close() }
            runCatching { proc.destroyForcibly() }
        }
    }

    @Test
    fun `cd 和 export 跨两次调用保留`() {
        val bash = findBash()
        assumeTrue("宿主没有可用的 bash，跳过", bash != null)
        MiniSession(bash!!).use { s ->
            val r1 = s.run("cd /tmp && export L2S_MARK=持久", "t1")
            assertEquals(0, r1.first)
            assertEquals("/tmp", r1.second)

            // 第二次调用既没有 cd 也没有 export：状态必须还在
            val r2 = s.run("pwd; echo \${L2S_MARK:-missing}", "t2")
            assertEquals(0, r2.first)
            assertEquals("/tmp", r2.second)
            assertTrue("export 没保留：${r2.third}", r2.third.contains("持久"))
        }
    }

    @Test
    fun `命令的 stdin 接的是 devnull_读 stdin 的命令不会挂住`() {
        val bash = findBash()
        assumeTrue("宿主没有可用的 bash，跳过", bash != null)
        MiniSession(bash!!).use { s ->
            // cat / read 若真读到我们的哨兵行，就会吞掉结束标记、这里必然超时
            val r = s.run("cat; read -r x || true; echo done", "t3", timeoutMs = 8_000)
            assertEquals(0, r.first)
            assertTrue(r.third.contains("done"))
        }
    }

    @Test
    fun `非零退出码与输出都如实回传`() {
        val bash = findBash()
        assumeTrue("宿主没有可用的 bash，跳过", bash != null)
        MiniSession(bash!!).use { s ->
            val r = s.run("echo before; nosuchcommand_xyz 2>/dev/null; false", "t4")
            assertEquals(1, r.first)
            assertTrue(r.third.contains("before"))
            assertNotNull(r.second)
        }
    }
}
