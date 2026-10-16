# MCP 集成方案（HTTP 多服务器）

> stdio 型 MCP（llama-server `--mcp-servers-json`）已移除：Android 手机上没有 npx/node，
> 本地命令型 MCP 无法运行。当前与未来的 MCP 支持均为 **HTTP（Streamable HTTP）**。

## 架构

- **App 侧 MCP 客户端**（`core/McpHttpClient.kt`）：完整 Streamable HTTP JSON-RPC——
  `initialize` 握手（协议版本协商 + `Mcp-Session-Id`）→ `notifications/initialized` →
  `tools/list` / `tools/call`，404 自动重连重试。
- **多服务器配置**：设置页「MCP 工具（HTTP）」卡片列表，每个服务器独立：
  启用开关、编辑、删除；弹窗内「连接并拉取工具」+ 工具逐项开关（对齐 Rikkahub 交互）。
  持久化于 `mcp_http_servers`（SharedPreferences JSON），旧单服务器配置启动时自动迁移。
- **聊天注入**：发送前聚合所有已启用服务器的工具，函数名加前缀 `mcp__<服务器>__<工具>`
  防止跨服务器重名；模型 `tool_call` 后按前缀路由回对应服务器执行，结果以 tool 消息回传，
  最多 5 轮。**只执行真实注入过的工具名**（弱模型幻觉的工具调用直接忽略并结束本轮）。
- **内置搜索**（`core/WebSearch.kt`）：免密钥 Bing 搜索，以 `web_search` 工具注入（聊天顶栏 🔍），
  与 MCP 工具同一条 tool_call 循环；每条消息最多搜索 3 次；来源附在回答末尾。

## 已知限制

- 手机上无 OAuth 浏览器回环流程（远端如要求 OAuth 授权暂不支持）。
- 工具执行无逐次「需要审批」弹窗（每个工具的启用开关是粗粒度控制）。
