package com.localllm.server.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 外部分享（ACTION_SEND）内容的暂存箱：MainActivity 写入，ChatViewModel 消费 */
object ShareInbox {
    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text

    fun put(value: String?) {
        _text.value = value?.takeIf { it.isNotBlank() }
    }

    fun consume(): String? {
        val v = _text.value
        _text.value = null
        return v
    }
}
