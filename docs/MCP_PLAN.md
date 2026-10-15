# MCP 集成方案（M6）— 调研结论

> 调研日期：2026-10-15 · 基于 llama.cpp 3fb4767 本地源码

## 结论：不需要自研 MCP 协议栈

llama-server **已内置实验性 MCP 客户端**（`tools/server/server-mcp.cpp`）：

- `--mcp-servers-json '<JSON>'`：直接传入 **Cursor 兼容格式** 的服务器定义
- `--mcp-servers-config <PATH>`：同上，从文件读取
- 行为：llama-server 拉起配置的 MCP 服务器（**stdio 子进程**，按 `command` 启动）、收集其 tools、通过原生 tool-calling 注入对话——与我们的 OpenAI 接口天然打通

JSON 格式（节选）：
```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/sdcard/Download"]
    }
  }
}
```

## M6 实现范围（缩至约 1 天）

1. 设置页新增「MCP 服务器」管理：条目 = 名称 + 命令 + 参数（空格分隔）
2. 持久化（SharedPreferences JSON）→ 启动服务时拼 `--mcp-servers-json`
3. 聊天/工具调用依赖 llama-server 原生 `tools` 协议（`--jinja` 已开）
4. 注意：当前实验特性**仅支持 stdio（本地命令）型 MCP**；远程 HTTP/SSE 的 MCP 需后续版本或经 mcp-proxy 转 stdio
5. 日志已有英文原文；MCP 报错行加入中文化映射

## 安全提示

配置里的 `command` 由 llama-server 直接执行——**只添加可信 MCP 服务器**（与 Cursor 同一风险模型）。设置页需固定提示。
