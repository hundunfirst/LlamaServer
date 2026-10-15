# LlamaServer 社交媒体发布文案

> 产品：LlamaServer（包名 com.localllm.server）— 安卓手机本地跑大模型推理的开源 App
> 开源协议：MIT ｜ GitHub：hundunfirst/LlamaServer ｜ 当前版本：v0.3.x ｜ 中文 UI

---

## 一、小红书文案

### 标题

手机秒变私人AI服务器📱数据不出门

### 正文

谁懂啊！我的手机现在是一个能跑大模型的私人 AI 服务器🤖

事情是这样的：装了一个开源 App 叫 LlamaServer，模型直接下载到手机里，全程**数据不出手机**，断网也能用 Wi-Fi 都不连，纯本地推理，聊天记录哪儿都不去🔒

实测小米 17 Pro Max（骁龙 8 Elite / 16GB），跑 Qwen2.5-0.5B，生成速度约 100 tok/s，屏幕上 tok/s 实时跳，看着就很爽✨ 双引擎设计，CPU 和 OpenCL 自动切换，不用自己折腾。

它还开了个 OpenAI 兼容接口 127.0.0.1:8080，Rikkahub 这类客户端直接接入，同机的其他 App 也能调，等于手机自带一个本地 API 服务🚀

App 本体也做得挺完整：
💬 多会话聊天，思考过程折叠成卡片
🖼️ 支持图片理解，Markdown 表格代码块都渲染
⚡ 流式输出 + 实时速度显示
☁️ 也接云端渠道，MiMo/DeepSeek/通义/智谱/Kimi 随切多预设
🔧 还有 MCP 工具调用（stdio + HTTP）
📥 内置 hf-mirror，手机上直接下 GGUF 模型

重点是 MIT 开源，代码全在 GitHub（hundunfirst/LlamaServer），想自己改也行。

在意隐私的真的可以冲——写东西、问问题、传私人文档，全程不上传，这才叫私人 AI 吧🥹

#本地大模型 #AI #开源 #大模型 #隐私保护 #人工智能 #手机AI

---

## 二、抖音文案

### 口播 / 字幕文案（约 200 字）

[画面: 手机怼脸拍，手指断网开关]

把 Wi-Fi 关了，我这台手机照样能聊天。

[画面: 打开 App，模型开始流式输出，tok/s 实时跳动]

这就是开源项目 LlamaServer，模型直接下到手机里，llama.cpp 本地跑，数据一步都不出手机。实测骁龙 8 Elite 跑 0.5B 模型，约 100 tok/s，刷刷出字。

[画面: 展示聊天界面——思考过程折叠卡、图片理解、Markdown 表格]

界面还特别完整：思考过程折叠、能看图、表格代码都能渲染，还有实时速度显示。

[画面: 展示 API 地址 + Rikkahub 接入画面]

最离谱的是它开了个 OpenAI 兼容接口，Rikkahub 直接接入，同机别的 App 也能调，手机秒变私人 AI 服务器。

[画面: GitHub 页面滑动，MIT 协议]

MIT 开源，GitHub 搜 hundunfirst，想要的关注我，评论区扣 1，我发你入口。

### 话题标签

#本地大模型 #开源 #AI #手机AI #隐私保护 #LlamaServer #人工智能 #大模型

---

## 三、配图说明（2 张，已用 image_gen 生成）

| 文件 | 用途 | 尺寸 | 说明 |
| --- | --- | --- | --- |
| `assets/xiaohongshu_cover.png` | 小红书封面 | 1024x1536 | 现代手机屏幕显示 AI 聊天界面 + 对话气泡 + 思考过程卡片，手机旁芯片/电路纹理暗示本地算力，干净浅色背景，扁平插画风，无文字 |
| `assets/douyin_cover.png` | 抖音封面 | 1024x1536 | 深色科技感，手机屏幕发光显示对话流，周围粒子/数据流环绕暗示数据不出手机，霓虹紫蓝色调，无文字 |

### image_gen 提示词

**图1（小红书封面）**

```
Vertical illustration, flat vector style, clean light background. A modern
smartphone screen showing an AI chat interface with rounded conversation
bubbles and a collapsed "thinking process" card. Beside the phone, a stylized
computer chip and subtle circuit-board traces suggesting on-device computing
power. Soft pastel palette, minimal composition, generous whitespace.
no text, no letters, no watermark
```

**图2（抖音封面）**

```
Vertical dark sci-fi poster, a glowing smartphone displaying a streaming
chat conversation, surrounded by orbiting particles and data-flow lines that
stay coiled around the phone suggesting data never leaves the device. Neon
purple and blue color scheme, high contrast, cinematic rim lighting, subtle
grid and circuit texture in the background. no text, no letters, no watermark
```
