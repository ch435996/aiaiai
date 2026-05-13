# Session Summary: Tool Calling 可观测性升级 — 问题诊断与架构复盘

**Date:** 2026-05-12
**Branch:** N/A

## Goal
将 Agent Tool Calling 从黑盒升级为结构化可观测协议（P0），实现 query 透明、检索结果预览、置信度展示。过程中触发三个典型 AI 工程问题，逐一诊断修复。

## Completed
- 新增 `RetrievalSnippet` DTO（score, confidence, preview, source）
- 扩展 `ToolCallDto`：inputPreview, resultSummary, resultCount, snippets
- `RetrievalServiceImpl` 新增 `extractSnippets()` / `normalizeConfidence()` 后处理方法
- `ChatOrchestrator` SSE 事件升级：tool_call 传 query 预览，tool_result 传结构化片段
- 前端状态行可点击展开 snippet 卡片（置信度色标、来源、文本预览）
- 修复 3 个生产级 bug（见下）

## Modified Files
| File | Change |
|------|--------|
| `controller/dto/RetrievalSnippet.java` | 新建 |
| `controller/dto/ToolCallDto.java` | 扩展字段 |
| `retrieval/RetrievalService.java` | 接口加 extractSnippets/normalizeConfidence |
| `retrieval/RetrievalServiceImpl.java` | 新增后处理方法；search() 保持原样 |
| `orchestrator/ChatOrchestrator.java` | SSE 回调升级；buildRequestDto/buildResultDto；注入 RetrievalService；失败日志 |
| `resources/static/index.html` | 前端：expandable cards、parseToolSnippets、插入顺序修复、持久化修复 |

## 三个关键 Bug 及解决方案（面试重点）

### Bug 1：searchKnowledge 工具静默失败（failed=true，无异常日志）

**现象**：LLM 调用 searchKnowledge 后工具标记 `failed=true`，Java 日志无任何异常输出。LLM 收到空错误信息后回答「知识库暂时无法检索」。

**根因**：`RetrievalServiceImpl.search()` 中新增的 `extractSource()` 方法直接操作 LangChain4j `Metadata` 对象。`Metadata` 并非标准 `Map`，其内部存储结构与 Pinecone 返回的 JSON 反序列化路径存在不兼容，导致静默异常。LangChain4j 框架在 `@Tool` 方法抛出异常时只捕获不记录，仅在 `ToolExecution.result()` 中以文本形式保留错误消息。

**排查过程**：
1. 对比日志：`recallMemory` 成功（memory namespace），`searchKnowledge` 全部失败（knowledge namespace）
2. 排除 Pinecone/Embedding API 问题（recallMemory 使用相同基础设施）
3. 定位到 search() 方法中唯一新增的代码路径：metadata 访问
4. 确认 `failed=true` 但无 ERROR 日志 — 这是 LangChain4j 框架行为：工具异常被框架吞掉

**解决**：将 `search()` 回退到原始代码（metadata 访问从 LLM 上下文路径中完全移除），结构化提取移到独立的 `extractSnippets()` 后处理方法中，由 `ChatOrchestrator` 在 SSE 回调路径调用。**这是"审计层与上下文层分离"原则的工程落地。**

**面试话术**：
> "Agent 工具调用链路中，`@Tool` 方法的异常会被框架静默吞掉。我发现 searchKnowledge 全部返回 failed=true 但没有任何 Java 异常日志，排查后发现是对 LangChain4j Metadata 对象的类型假设错误。这个问题的本质是：LLM 上下文消费的检索文本和前端需要的结构化审计数据是两种不同的数据形态，耦合在一起就会互相污染。解决方案是审计层与上下文层分离——search() 保持纯文本输出给 LLM，extractSnippets() 独立负责结构化提取。"

---

### Bug 2：Tool Calling 状态行在回答完成后消失

**现象**：SSE 流式对话中 tool 状态短暂显示后消失，用户无法回溯系统做了什么。

**根因**：三个代码位置依次清除了 tool 状态：
1. `token` 事件处理器调用 `removeStatusLines()` — LLM 开始生成文本时清空所有状态行
2. `done` 事件处理器调用 `removeStatusLines()` 后重新添加 — 部分恢复
3. 流结束后的清理代码再次调用 `removeStatusLines()` — **最终全部清除**

**解决**：
- `token` 事件：改为只移除带 `.initial` 标记的初始"正在处理…"行
- 流结束清理：移除 `removeStatusLines()` 调用
- `done` 事件：保留 remove + re-add（最终定位用）

**面试话术**：
> "前端 SSE 事件处理的时序设计有一个典型的时序竞争问题。初始占位符、tool_call 状态、token 流、done 事件这四种状态转换中，removeStatusLines 被无差别调用，导致 tool 可观测信息在用户看到之前就被销毁。修复策略是给不同生命周期的 UI 元素加标记（initial vs persistent），让清除操作从'全量清除'变为'精确清除'。"

---

### Bug 3：Tool 状态行出现在 AI 回复**之后**，而非之前

**现象**：对话中 tool 活动卡片出现在 AI 回答下方，不符合"先检索→后回答"的逻辑顺序。

**根因**：`addStatusLine()` 始终用 `appendChild` 追加到 `chatArea` 末尾，而流式气泡 `streamEl` 先于 `done` 事件被创建和追加。`done` 事件触发时 `streamEl` 已在 DOM 中，tool 状态行自然追加在其后。

**解决**：`addStatusLine()` 增加可选 `beforeEl` 参数，通过 `insertBefore` 插入到指定元素之前。`done` 事件中将 `streamEl` 作为锚点，tool 状态行插入在 AI 回复气泡**之前**。

**面试话术**：
> "可观测信息的空间位置应该反映 Agent 的执行时序。检索发生在生成之前，UI 上工具状态就该在回答之前。这个修复本质上是把 SSE 事件的时序语义映射到了 DOM 的空間位置——done 事件携带本轮所有 tool call 的最终汇总，以 streamEl 为锚点向前插入，保证了执行轨迹的因果顺序在 UI 上的正确呈现。"

---

## Architecture Decisions
- **审计层与上下文层分离**：`@Tool searchKnowledge()` 返回纯文本给 LLM；`extractSnippets()` 独立提取结构化数据给 SSE 前端。两个消费者各自有独立的数据通道，互不污染（confirmed）
- **不修改 `@Tool` 返回类型**：LangChain4j 要求 `@Tool` 方法返回 `String`，不宜改为 DTO。结构化提取在 ChatOrchestrator 拦截层完成（confirmed）
- **置信度归一化在后端完成**：裸 cosine score 在不同 embedding 模型间不可比较，归一化为 HIGH/MEDIUM/LOW 后前端稳定渲染（confirmed）
- **source 字段暂时写死为"知识库"**：完整 source 需要在 ingestion 阶段存 Pinecone metadata，并修改检索路径读取，P1 处理（confirmed）

## Known Risks
- `parseToolSnippets()` 前端正则与后端 `extractSnippets()` 独立维护，格式变更需同步更新
- 历史会话的 tool 消息仅存文本，无结构化 DTO，parseToolSnippets 是事后解析，新增格式字段会丢失
- LangChain4j 框架吞异常的行为不透明，建议在 `onToolExecuted` 中统一打印 `exec.result()` 做审计

## Next Step
进入 P1：给 `ToolCallDto` 加 `traceId`/`latencyMs`，改 `PdfExtractionService` 在 ingestion 时写入 source/title 到 Pinecone metadata，使 `RetrievalSnippet.source` 展示真实论文来源而非"知识库"。
