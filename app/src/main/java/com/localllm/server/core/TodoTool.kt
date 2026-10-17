package com.localllm.server.core

import org.json.JSONArray
import org.json.JSONObject

/** 任务清单工具：让模型"先列计划、再逐步执行、随时更新状态" */
object TodoTool {

    const val NAME = "todo_write"

    fun toolJson(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function", JSONObject()
                .put("name", NAME)
                .put(
                    "description",
                    "创建或更新任务清单。多步任务开始时先列计划，之后每完成一步就再次调用本工具更新状态。" +
                            "每次都提交完整清单（不是增量）。状态取值：pending / in_progress / completed。"
                )
                .put(
                    "parameters", JSONObject()
                        .put("type", "object")
                        .put(
                            "properties", JSONObject().put(
                                "tasks", JSONObject()
                                    .put("type", "array")
                                    .put(
                                        "items", JSONObject()
                                            .put("type", "object")
                                            .put(
                                                "properties", JSONObject()
                                                    .put("title", JSONObject().put("type", "string"))
                                                    .put(
                                                        "status", JSONObject()
                                                            .put("type", "string")
                                                            .put(
                                                                "enum",
                                                                JSONArray().put("pending")
                                                                    .put("in_progress").put("completed")
                                                            )
                                                    )
                                            )
                                            .put("required", JSONArray().put("title").put("status"))
                                    )
                            )
                        )
                        .put("required", JSONArray().put("tasks"))
                )
        )

    /** 解析工具参数为 ChatStore.Todo 列表；失败返回空表 */
    fun parse(argsJson: String): List<ChatStore.Todo> = runCatching {
        val tasks = JSONObject(argsJson).optJSONArray("tasks") ?: return emptyList()
        (0 until tasks.length()).mapNotNull { i ->
            val o = tasks.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title").trim()
            if (title.isBlank()) return@mapNotNull null
            val st = o.optString("status").lowercase()
            ChatStore.Todo(
                title,
                if (st in setOf("pending", "in_progress", "completed")) st else "pending"
            )
        }
    }.getOrDefault(emptyList())
}
