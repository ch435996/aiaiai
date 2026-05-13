# Session Summary: 前端搭建 + 长期记忆召回修复 + 会话持久化

**Date:** 2026-05-11
**Branch:** N/A (no git repo)

## Goal
为 aiaiai 项目搭建交互前端，修复长期记忆"存了取不出"的链路断裂，实现会话持久化。

## Completed
- 创建前端 SPA（深色实验室风，粒子背景），对接 `POST /api/chat`
- 新增 `recallMemory` 工具，让 LLM 能主动召回 Pinecone 长期记忆
- 新 session 首条消息自动从 Pinecone 拉取长期记忆注入上下文（`ChatOrchestrator.processMessage`）
- `RedisChatMemoryStore` 新增 `trackSession()` / `listSessions()` 会话元数据持久化
- `ChatController` 新增 `GET /api/sessions` + `DELETE /api/sessions/{id}`
- 侧栏改为会话历史列表，localStorage 持久化 sessionId
- 前端移除原始 Tool Activity 日志，改为状态行过渡提示
- `application.yml` chunk size 500→800（对齐 paper-ingest 规范）
- 安装 `frontend-design` skill 到全局

## Modified Files
| File | Change |
|------|--------|
| `src/main/resources/static/index.html` | Created — 完整前端 SPA |
| `src/main/java/com/aiaiai/ai/AiaiaiTools.java` | Modified — 新增 recallMemory 工具 |
| `src/main/java/com/aiaiai/orchestrator/ChatOrchestrator.java` | Modified — 注入 MemoryService，新 session 主动召回长期记忆 |
| `src/main/java/com/aiaiai/memory/RedisChatMemoryStore.java` | Modified — 新增会话元数据追踪（track/list/delete） |
| `src/main/java/com/aiaiai/controller/ChatController.java` | Modified — 新增 session 列表/删除端点 |
| `src/main/resources/application.yml` | Modified — max-segment-size 500→800 |
| `.claude/skills/session-summary/SKILL.md` | Created |
| `.claude/skills/paper-ingest/SKILL.md` | Created |
| `.claude/memory/` | Created — sessions/architecture/decisions/roadmap 目录 |

## Architecture Decisions
- 长期记忆召回采用"新 session 首条消息主动注入 + recallMemory 工具"双保险 (confirmed)
- 会话元数据用 Redis Sorted Set + 独立 meta key 存储，TTL 与 chat memory 一致 7 天 (confirmed)
- 不做 SSE 流式推送，工具活动以状态行形式展示（当前阶段不做流式）(confirmed)
- chunk_size 锁定 800/100，不可逐篇变动 (confirmed)

## TODO / Remaining
- [ ] 前端切换历史会话后消息加载（需 `GET /api/sessions/:id/messages` 端点）
- [ ] BM25 稀疏向量混合检索（RetrievalService 目前只有纯 dense）
- [ ] paper-ingest 的实际 PDF 解析与 Pinecone 写入链路
- [ ] 单元测试 / 冒烟测试自动化

## Known Risks
- 项目未初始化为 git repo，无版本控制
- 无认证授权，任何能访问 8080 端口的人都能使用

## Next Step
实现 BM25 + 稠密向量混合检索，提升方法名/指标名等术语精确匹配的召回质量。
