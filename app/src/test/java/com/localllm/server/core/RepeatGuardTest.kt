package com.localllm.server.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复读检测的回归测试。
 * 真实的坑（2026-10-23）：模型把整个 `<tool_call>` 块（约 2.5 KB，含整份 HTML）反复吐了十几遍，
 * 旧的「周期 24–200」判据完全看不见，于是没拦住，参数 JSON 被撑到 40 KB 不闭合，
 * 服务端解析失败返回 500，整轮作废。这里把两种形态都钉住。
 */
class RepeatGuardTest {

    @Test
    fun `短周期复读能抓到`() {
        // 注意样本要够长：判据需要尾部 400 字以上才有意义
        assertTrue(RepeatGuard.isRepetitive("好的，" + "这个功能很好用。".repeat(200)))
        assertTrue(RepeatGuard.isRepetitive("x".repeat(10) + "abcdefghij".repeat(200)))
    }

    @Test
    fun `长周期复读能抓到（真实故障形态）`() {
        // 模拟：一整个工具调用块（含整份 HTML）重复若干次，周期 ~2.5 KB
        val block = "<tool_call>\n<function=web_run>\n<parameter=path>\ndemo/pomod.html" +
                "\",\"code\":\"<!DOCTYPE html>\\n<html lang=\\\"zh-CN\\\">\\n<head>\\n<meta charset=\\\"UTF-8\\\">" +
                "\\n<title>番茄钟</title>\\n<style>body{display:flex;justify-content:center;align-items:center;" +
                "height:100vh;background:#f0f2f5;margin:0}.timer{text-align:center;padding:20px;" +
                "border-radius:8px;background:#fff;box-shadow:0 4px 6px rgba(0,0,0,.1)}</style>" +
                "</head>\n</body>\n</html>\n</function>\n</tool_call>\n"
        val text = block.repeat(4)
        assertTrue("长周期复读应被判定为复读（长度 ${text.length}）", RepeatGuard.isRepetitive(text))
    }

    @Test
    fun `正常长文本不误判`() {
        val prose = buildString {
            for (i in 1..60) {
                append("第 $i 条：茶文化的形成与地理环境、宗教礼仪、商贸路线都有关系，")
                append("不同产区的制茶工艺和品饮方式因此差异明显。\n")
            }
        }
        assertFalse(RepeatGuard.isRepetitive(prose))
    }

    @Test
    fun `结构相似的代码块不误判`() {
        // 连续几个形状相同但内容不同的 if 块：这是正常代码，不是复读
        val code = buildString {
            append("fun check(value: Int): Boolean {\n")
            for (i in 1..20) {
                append("    if (value == $i) {\n        println(\"命中分支 $i\")\n        return true\n    }\n")
            }
            append("    return false\n}\n")
        }
        assertFalse(RepeatGuard.isRepetitive(code))
    }

    @Test
    fun `一长串重复符号不误判为复读`() {
        // markdown 表格横线 / 长分隔线：形态上是周期性的，但单元里没有实际内容，
        // 属于正常排版。误判会平白掐断一个正在输出的宽表格。
        assertFalse(RepeatGuard.isRepetitive("-".repeat(3000)))
        assertFalse(RepeatGuard.isRepetitive("|---|---|---|---|---|---|---|---|\n".repeat(80)))
    }
}
