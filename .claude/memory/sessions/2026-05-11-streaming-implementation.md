# Session Summary: 流式输出实现与前端修复

**Date:** 2026-05-11
**Branch:** N/A (no git)

## Goal
实现 SSE 流式输出，修复前端空指针崩溃和流式端点未接入问题。

## Completed
- 新增 `StreamingAssistant` 接口，返回 `TokenStream` 供 AiServices 流式调用
- `LLMConfig` 新增 `OpenAiStreamingChatModel` bean（独立于 `OpenAiChatModel`）
- `ChatOrchestrator` 新增 `processMessageStreaming()`，SseEmitter + TokenStream 回调链路
- `ChatController` 新增 `POST /api/chat/stream` SSE 端点
- SSE 事件协议：connected / token / tool_call / tool_result / done / error
- 修复 `toDto()` 中 `AiMessage.text()` 为 null 导致前端 `escHtml(null).replace` 崩溃
- 前端 `escHtml` 增加 null 保护，`addStatusLine` 去重 ID 改用 class 选择器
- 前端 `sendMessage()` 重写为 fetch + ReadableStream SSE 解析
- `application.yml` 开启 `dev.langchain4j: DEBUG` 用于诊断流式兼容性

## Modified Files
| File | Change |
|------|--------|
| `config/LLMConfig.java` | Modified — 新增 StreamingChatModel bean |
| `ai/StreamingAssistant.java` | Created — TokenStream 接口 |
| `orchestrator/ChatOrchestrator.java` | Modified — 流式方法 + null 修复 + SLF4J 日志 |
| `controller/ChatController.java` | Modified — 新增 /chat/stream 端点 |
| `resources/static/index.html` | Modified — 流式前端 + null 保护 |
| `resources/application.yml` | Modified — DEBUG 日志配置 |

## Architecture Decisions
- **SseEmitter 而非 WebFlux**: 项目是 Spring MVC，SseEmitter 零额外依赖，并发 < 50 完全够用 (confirmed)
- **OpenAiStreamingChatModel 独立 bean**: 与 OpenAiChatModel 是两个独立类，不能共用实例 (confirmed)

## Known Risks
- `OpenAiStreamingChatModel` 解析 DeepSeek 流式 SSE 响应可能存在兼容性问题（token 不触发），需通过 DEBUG 日志确认
- SseEmitter 依赖 Servlet 异步支持，若被中间件缓冲则 SSE 退化为普通响应

## Next Step
浏览器访问 `http://localhost:8080/`，发送消息验证流式输出；若无 token 事件，检查后台 `Starting stream for session` 和 `token` DEBUG 日志
