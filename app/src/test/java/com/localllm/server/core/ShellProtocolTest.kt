package com.localllm.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 持久 shell 的文本协议：命令怎么包、结束标记怎么认。
 * 这里错一处，表现就是「命令永远不结束」或者「上一条命令的输出混进下一条」，
 * 在真机上极难看出来，所以逐字测。
 */
class ShellProtocolTest {

    private val token = "abc123"

    @Test
    fun `脚本把命令包在花括号里而不是子 shell`() {
        val s = ShellProtocol.buildScript("pwd", null, token)
        assertTrue(s.contains("{\npwd\n} </dev/null"))
        // 子 shell 会让 cd/export 不回流，持久会话就白做了
        assertTrue(!s.contains("(pwd)"))
    }

    @Test
    fun `给了 cwd 才 cd_并且单引号转义`() {
        val withCd = ShellProtocol.buildScript("ls", "/storage/emulated/0/Download", token)
        assertTrue(withCd.startsWith("cd -- '/storage/emulated/0/Download'\n"))
        val blank = ShellProtocol.buildScript("ls", "   ", token)
        assertTrue(!blank.contains("cd --"))
    }

    @Test
    fun `路径里的单引号被正确转义`() {
        assertEquals("'it'\\''s'", ShellProtocol.quoteSingle("it's"))
        assertEquals("'/a/b c/d'", ShellProtocol.quoteSingle("/a/b c/d"))
    }

    @Test
    fun `最后一行是带本次 token 的哨兵`() {
        val s = ShellProtocol.buildScript("echo hi", null, token)
        assertTrue(s.trimEnd().endsWith("printf '__L2S_END_abc123__ %s %s\\n' \"\$?\" \"\$PWD\""))
    }

    @Test
    fun `多行命令原样保留_只去掉尾部换行`() {
        val cmd = "cat > /tmp/x <<EOF\nhello\nEOF"
        val s = ShellProtocol.buildScript(cmd, null, token)
        assertTrue(s.contains("{\n$cmd\n} </dev/null"))
    }

    @Test
    fun `能解析出退出码和当前目录_目录里有空格也行`() {
        val line = "__L2S_END_abc123__ 0 /storage/emulated/0/My Documents"
        assertEquals(0 to "/storage/emulated/0/My Documents", ShellProtocol.parseSentinel(line, token))
    }

    @Test
    fun `非零退出码照样解析`() {
        val line = "__L2S_END_abc123__ 127 /root"
        assertEquals(127 to "/root", ShellProtocol.parseSentinel(line, token))
    }

    @Test
    fun `token 不同就不是本次的哨兵`() {
        val line = "__L2S_END_other__ 0 /root"
        assertNull(ShellProtocol.parseSentinel(line, token))
    }

    @Test
    fun `普通输出不会被当成哨兵`() {
        assertNull(ShellProtocol.parseSentinel("echo hi", token))
        assertNull(ShellProtocol.parseSentinel("__L2S_END_", token))
        assertNull(ShellProtocol.parseSentinel("", token))
    }

    @Test
    fun `拿不到 PWD 时退出码仍然可用`() {
        val line = "__L2S_END_abc123__ 3"
        assertEquals(3 to null, ShellProtocol.parseSentinel(line, token))
    }

    @Test
    fun `退出码不是数字时按 0_处理_不抛异常`() {
        val line = "__L2S_END_abc123__ ?? /root"
        assertEquals(0 to "/root", ShellProtocol.parseSentinel(line, token))
    }
}
