package com.localllm.server.core

import org.json.JSONObject

/**
 * 上下文类工具错误的「改参数自救」。
 *
 * 真机上模型连撞两个坑，都不是服务没起来，是它手里的 id 不对：
 *  - 把 `mt://current-apk` 这个**路径**当成 `workspaceId` 传（服务端只认 open 时分配的 `m5l2wls9` 这种）；
 *  - 编辑会话过了几分钟失效后继续用旧 `editSessionId`（`EDIT_SESSION_NOT_FOUND`）。
 * 每次都要耗掉一整轮让模型自己猜，它还不一定猜得对——实测它换的做法是重新 open 一个新工作区，
 * 于是手上同时有三个 id，越滚越乱。
 *
 * 这两类改完就能成，所以由我们直接改参数重试一次。判据只碰我们自己确定过的事实：
 * 服务端分配过的 id 存在这里，过期的东西只删不造。
 */
object McpArgRepair {

    /** 改好之后的参数 + 给日志/时间线看的一句话 */
    data class Repair(val args: JSONObject, val note: String)

    private val WORKSPACE_ID = Regex("\"workspaceId\"\\s*:\\s*\"([^\"]{1,80})\"")

    /** 从成功的工具结果里记住服务端分配的工作区 id（MT 的 open 工具在 data.workspaceId 里给） */
    fun rememberedWorkspaceId(result: String): String? =
        WORKSPACE_ID.find(result)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    /**
     * 能不能靠改参数救回来；救不了返回 null，交回原来的失败路径。
     *
     * [knownWorkspaceId] 是这台服务器上一次 open 成功返回的 id，只用来替换模型传错的值，
     * 不会在模型根本没传 workspaceId 时凭空加一个参数——那属于猜工具签名。
     */
    fun repair(info: McpToolError.Info?, args: JSONObject, knownWorkspaceId: String?): Repair? {
        val code = info?.code?.uppercase().orEmpty()
        if (code.isEmpty()) return null

        // 编辑会话过期：MT 自己在 nextActions 里让退回基础工作区，等价于把 editSessionId 清空
        if (code.contains("EDIT_SESSION") || (code.contains("SESSION") && args.has("editSessionId"))) {
            val stale = args.optString("editSessionId")
            if (stale.isBlank()) return null
            return Repair(
                JSONObject(args.toString()).put("editSessionId", ""),
                "编辑会话 $stale 已失效，改用基础工作区重试一次"
            )
        }

        if (code.contains("WORKSPACE")) {
            val passed = args.optString("workspaceId")
            if (!args.has("workspaceId") || passed == knownWorkspaceId) return null
            if (knownWorkspaceId.isNullOrBlank()) return null
            return Repair(
                JSONObject(args.toString()).put("workspaceId", knownWorkspaceId),
                "workspaceId=$passed 服务端不认，改用已知可用的 $knownWorkspaceId 重试一次"
            )
        }
        return null
    }
}
