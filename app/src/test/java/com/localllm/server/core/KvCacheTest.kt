package com.localllm.server.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NPU 上量化 KV 会让引擎在建上下文时 gin_abort（后端缺 SET_ROWS），
 * 所以 `KvCache.quantized()` 决定要不要自动回退 f16。判错的两个方向都有代价：
 * 该回退却没回退 = 服务起不来；不该回退却回退 = 白白多吃内存。
 */
class KvCacheTest {

    @Test
    fun `空值与 f16 不算量化`() {
        assertFalse(KvCache.isQuantized(""))
        assertFalse(KvCache.isQuantized("f16"))
        assertFalse(KvCache.isQuantized("F16"))
        assertFalse(KvCache.isQuantized("bf16"))
        assertFalse(KvCache.quantized("", ""))
        assertFalse(KvCache.quantized("f16", "f16"))
    }

    @Test
    fun `设定里的量化档都算量化`() {
        assertTrue(KvCache.isQuantized("q8_0"))
        assertTrue(KvCache.isQuantized("q4_0"))
        assertTrue(KvCache.isQuantized("q4_1"))
        // 只量化 V 也会崩（同一张张量走 SET_ROWS）
        assertTrue(KvCache.quantized("f16", "q8_0"))
        assertTrue(KvCache.quantized("q4_0", "f16"))
        assertTrue(KvCache.quantized("q4_0", "q8_0"))
    }
}
