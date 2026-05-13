# CLAUDE.md

# 通用
- 优先选择编辑而非重写整个文件
- 除非文件被编辑过，否则不要重复阅读已读过的文件
- 输出追求简洁，但推理过程必须详尽

# 代码规范
- 一个文件不超过 400 行，超了就拆
- 嵌套不超过 4 层

# 反回归规则（Anti-Regression Rules）
这些规则来自真实踩坑。每次生成或修改代码前，逐条自检。

## AR-1：跨边界数据必须空值保护
任何消费外部数据（LLM 返回、API 响应、Redis 序列化结果）的代码，必须假定每个字段都可能为 null。
- `escHtml(s)` 等工具函数第一行写 `if (s == null) return "";`
- `addMessage(role, content)` 调用前确保 `content` 非空
- 从 `ChatMessage` 提取 `text()` 时，考虑 `AiMessage` 的 tool request 形态（`text()` 可能为 null）

## AR-2：前后端接口必须交叉验证
当同时涉及前端和后端代码时：
- 写前端前，必须先读 Controller 签名的真实返回值结构（字段名、类型、可选性）
- 写前端前，检查后端是否已有流式接口但前端未使用
- 不允许前端 `fetch('/api/chat')` 而后端提供了 `POST /api/chat/stream` 而不自知

## AR-3：数据加载路径必须容错降级
任何从外部源加载数据并渲染的路径（session 列表、消息历史、tool results），必须包裹 try-catch 并在失败时展示用户可理解的错误提示，而非白屏或 `Cannot read properties of null`。
- 列表渲染：`messages.forEach(msg => { try { render(msg); } catch { renderPlaceholder(); } })`
- 数据加载：`try { load(); } catch { show("加载失败: " + reason); }`

## AR-4：文件操作必须先检查再行动，且路径必须锚定项目根
所有 shell 命令和文件写入操作遵循"验证→行动"顺序，不允许"盲动"。
- 写入任何文件前，必须先确认目标目录是否存在（Glob / LS），存在则直接用，不存在才创建
- 项目相关的所有产出（session 记录、skill 文件、memory、配置）一律写入项目根目录下的 `.claude/`，绝对禁止写入 `C:\Users\xxx\.claude\` 等用户目录
- 执行 `mkdir`、`rm`、`mv` 等变更命令前，必须先用 `ls` 或 `glob` 确认当前状态
- 路径一律使用项目相对路径（如 `.claude/memory/sessions/`），需要绝对路径时基于当前工作目录拼接，不得硬编码 `C:\Users\`


## 项目概述

AIAIAI：面向研究生课题组的三维重建/点云补全智能科研助手。

当前状态：Phase 3 核心能力已交付（26 源文件，编译通过，全链路冒烟通过），进入收尾与优化阶段。

核心模式：LLM 自主决定是否调用检索、是否写入/召回记忆；会话生命周期和主流程由 Java `ChatOrchestrator` 严格控制。这是手动编排与全自主 Agent 之间的工业级中间态。

## 领域边界与任务定义（强约束）

本项目仅服务于三维重建与点云补全研究场景，优先支持以下任务：

1. 论文理解：方法动机、网络结构、损失函数、训练策略、数据集、指标、实验结论。
2. 方法对比：同类方法优缺点、适用场景、性能-复杂度权衡。
3. 实验辅助：复现实验步骤梳理、参数与数据流程整理、排障思路建议。
4. 知识沉淀：将课题组稳定偏好和长期有效经验写入长期记忆。

当用户问题明显超出点云补全/三维重建范围时：

- 可以简要回答；
- 但必须明确提示“超出当前知识库重点领域”，避免生成看似权威的跨域结论。

## 技术栈

| 层面 | 选型 |
|------|------|
| 语言 | Java 17（Spring Boot 3.x 硬要求，JDK 17.0.19 LTS） |
| 框架 | Spring Boot 3.4.4 + LangChain4j 1.14.0 |
| LLM | deepseek-v4-pro（DeepSeek 官方 OpenAI 兼容 API，`https://api.deepseek.com`） |
| Chat 模块 | `langchain4j-open-ai` → `OpenAiChatModel`（实现 `ChatModel` 接口） |
| Embedding | `text-embedding-v3`（阿里云百炼 DashScope，OpenAI 兼容端点：`https://dashscope.aliyuncs.com/compatible-mode/v1`） |
| 短期记忆 | Redis（`MessageWindowChatMemory`，窗口 20 条，TTL 7 天） |
| 长期记忆 | Pinecone（`memory` 命名空间，dimension=1024） |
| 知识库 | Pinecone（`knowledge` 命名空间，同一索引，dimension=1024） |
| 构建工具 | Maven |

## 架构原则

1. **控制流反转的克制**：Java 控制 Session 生命周期与 workflow；LLM 仅负责单轮内工具选择。
2. **短期记忆自动注入**：依赖 LangChain4j `ChatMemory` 注入上下文，不提供“查历史”冗余工具。
3. **命名空间隔离**：同一 Pinecone index 下使用 `knowledge` 与 `memory` 隔离语义空间。
4. **LLM 只做判断**：开放 `searchKnowledge`、`saveMemory`、`recallMemory` 三类能力工具。
5. **先证据后结论**：涉及论文细节、指标和实验配置时，优先检索知识库证据再回答。

## 工具语义契约（Tool Semantic Contract）

### `searchKnowledge`

仅在以下场景调用：

- 用户询问论文事实、方法细节、实验结果、数据集或指标；
- 当前对话上下文不足以支持可靠回答；
- 回答需要外部知识证据支撑。

禁止在以下场景调用：

- 闲聊或寒暄；
- 用户个性偏好记忆写入；
- 已在当前上下文明确给出的事实重复查询。

### `saveMemory`

仅在以下场景调用：

- 用户明确要求"记住"；
- 内容属于长期稳定偏好或长期有效研究约束。

禁止在以下场景调用：

- 临时情绪、一次性任务、短期状态；
- 可从知识库检索获得的公共事实；
- 含敏感信息且未获得明确授权。

### `recallMemory`

仅在以下场景调用：

- 用户问题涉及课题组历史偏好、编码规范、研究方向约定；
- 对话中可能已有相关长期记忆但未被当前上下文覆盖；
- 判断用户意图与已存储记忆可能相关时主动查询。

禁止在以下场景调用：

- 闲聊或寒暄；
- 已明确在当前对话中讨论过的偏好重复召回；
- 与当前问题明显无关的记忆泛查。

## Tool 结果注入策略（Tool Result Policy）

1. 工具返回统一为结构化文本，最少包含：`source`、`summary`、`confidence`。
2. 检索结果默认 `topK <= 5`，避免上下文污染与 token 膨胀。
3. 单次工具结果设置最大 token 预算，超限时先摘要再注入。
4. 禁止在工具结果中注入 chain-of-thought 或冗余推理草稿。
5. 多条证据冲突时，显式提示冲突并给出保守结论。

## 记忆写入策略（Memory Write Policy）

允许写入长期记忆：

- 课题组稳定编码偏好（语言、风格、框架约束）；
- 研究方向稳定偏好（任务定义、评价指标优先级、常用数据集）；
- 长期复用的实验规范（命名规则、日志规范、对比基线习惯）。

禁止写入长期记忆：

- 临时情绪与一次性指令；
- 未确认真实性的论文结论；
- 包含隐私或密钥的敏感信息。

## 知识来源优先级

回答时按以下优先级使用证据：

1. 用户刚上传/当前会话提供的论文内容；
2. 已入库的点云补全知识库条目；
3. 通用背景知识（仅作补充，不覆盖前两者）。

若证据不足，明确说明“不确定”并建议补充论文片段或实验日志。

## 项目结构

```text
aiaiai/
├── pom.xml
├── CLAUDE.md
├── src/main/java/com/aiaiai/
│   ├── AiaiaiApplication.java
│   ├── config/
│   │   ├── LLMConfig.java              # OpenAiChatModel → DeepSeek 官方 API
│   │   ├── EmbeddingConfig.java        # OpenAiEmbeddingModel → 阿里云百炼
│   │   ├── PineconeConfig.java         # 两个 EmbeddingStore Bean（knowledge + memory 命名空间）
│   │   ├── RedisConfig.java            # RedisTemplate<String, String>
│   │   └── IngestionConfig.java        # EmbeddingStoreIngestor（文档分段→向量化→Pinecone）
│   ├── controller/
│   │   ├── ChatController.java         # POST /api/chat、POST /api/chat/stream、GET /api/sessions/{id}/messages、GET /api/sessions、DELETE /api/sessions/{id}
│   │   ├── KnowledgeController.java    # POST /api/knowledge/ingest/pdf
│   │   └── dto/
│   │       ├── ChatRequest.java        # { sessionId, message }
│   │       ├── ChatResponse.java       # { sessionId, reply, toolCalls[] }
│   │       ├── ToolCallDto.java        # { toolName, status, inputPreview, resultSummary, resultCount, snippets[] }
│   │       ├── RetrievalSnippet.java   # { score, confidence(HIGH|MEDIUM|LOW), preview, source }
│   │       ├── IngestRequest.java      # 文档摄入请求
│   │       └── MessageDto.java         # 消息 DTO
│   ├── orchestrator/
│   │   ├── ChatOrchestrator.java       # 核心：会话管理 + AI Service 调度
│   │   └── ProcessResult.java          # { reply, toolCalls[] }
│   ├── ai/
│   │   ├── Assistant.java              # @AiService 接口（同步）
│   │   ├── StreamingAssistant.java     # TokenStream 接口（SSE 流式）
│   │   └── AiaiaiTools.java            # @Tool ×3：searchKnowledge, saveMemory, recallMemory
│   ├── memory/
│   │   ├── MemoryService.java          # 接口
│   │   ├── MemoryServiceImpl.java      # Pinecone memory 命名空间写入/召回
│   │   └── RedisChatMemoryStore.java   # ChatMemoryStore Redis 实现 + session 列表
│   ├── retrieval/
│   │   ├── RetrievalService.java       # 接口
│   │   └── RetrievalServiceImpl.java   # Pinecone knowledge 命名空间检索
│   └── ingestion/
│       ├── PdfExtractionService.java   # Apache PDFBox 文本提取 + 分段
│       └── BatchImportRunner.java      # 命令行批量 PDF 导入
├── src/main/resources/
│   ├── application.yml
│   └── static/
│       └── index.html                  # SPA 前端：SSE 流式对话 + 会话管理 + Tool Activity 可观测面板
└── src/test/java/com/aiaiai/
```

## 开发约定

- 不写 Javadoc 多行注释，需要解释 WHY 时才加简短单行注释。
- 不做防御式编程，信任框架与内部契约。
- 不过度抽象，避免为假设性变化提前设计复杂层。
- 中文回答用户，代码与标识符使用英文。
- 任何新增能力必须先说明“解决什么具体科研问题”再实现。

## 环境变量

```bash
# JDK 17（必须，Spring Boot 3.4.4 不支持更低版本）
JAVA_HOME=<your-jdk-17-path>
# DeepSeek
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_API_KEY=<your-deepseek-api-key>
DEEPSEEK_MODEL=deepseek-chat
# 阿里云百炼 Embedding
EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
EMBEDDING_API_KEY=<your-dashscope-api-key>
EMBEDDING_MODEL=text-embedding-v3
# Pinecone
PINECONE_API_KEY=<your-pinecone-api-key>
PINECONE_INDEX=aiaiai-knowledge
PINECONE_DIMENSION=1024
# Redis
REDIS_HOST=localhost
REDIS_PORT=6379
```

## 构建与运行

```bash
mvn spring-boot:run
```

## 冒烟测试（手动）

| 输入 | 预期 |
|------|------|
| "你好" | 不调工具，直接回复 |
| "SnowflakeNet 的核心思想是什么？" | 调 `searchKnowledge` 并给出结构化摘要 |
| "记住我们课题组偏好使用 Chamfer Distance + F-score" | 调 `saveMemory` |
| "PCN 和 FoldingNet 在补全任务上的差异？并记住我偏好先看泛化性" | 先 `searchKnowledge` 再 `saveMemory` |
| "如何梳理 ProtoComp 特征融合 baseline 的复现步骤？如果训练时 Chamfer Distance 卡在 14.50 左右降不下去，有什么排障建议？" | 调 `searchKnowledge` 检索复现步骤和排障经验，如知识库有相关实验日志则一并召回 |

## 当前完成状态（Phase 3）

5 个 Phase 中 4 个已完成，`mvn compile` 通过，全链路冒烟测试通过（百炼 embedding + DeepSeek + Pinecone + Redis）。

| Phase | 内容 | 文件 | 状态 |
|-------|------|------|------|
| 1 基础设施 | pom.xml、yml、5 Config + Application | 6 | ✅ |
| 2 记忆与检索层 | RedisChatMemoryStore、MemoryService/Impl、RetrievalService/Impl | 5 | ✅ |
| 3 Agent 核心调度 | AiaiaiTools(×3)、Assistant、StreamingAssistant、ChatOrchestrator、ProcessResult | 5 | ✅ |
| 4 API 暴露 | ChatController(含 sessions 管理)、KnowledgeController、DTO(含 ToolCallDto, RetrievalSnippet) | 6 | ✅ |
| 5 反幻觉与可观测性 | 置信度护栏、SSE 流式事件协议、SPA 前端 | 2 | ✅ |

已交付能力：

- `POST /api/chat` — Tool Calling RAG 对话，响应含 `toolCalls[]`（含 inputPreview、resultSummary、snippets）
- `POST /api/chat/stream` — SSE 流式对话，事件类型：connected / token / tool_call / tool_result / done / error
- `GET /api/sessions` / `GET /api/sessions/{id}/messages` / `DELETE /api/sessions/{id}` — 会话生命周期管理
- `POST /api/knowledge/ingest/pdf` — 单篇 PDF 摄入
- `BatchImportRunner` — 命令行批量 PDF 导入
- SPA 前端（`static/index.html`）：流式对话 + 会话列表 + Tool Activity 可展开卡片（含置信度色标）
- 三级置信度护栏：HIGH(≥0.85) / MEDIUM(≥0.70) / LOW(<0.70)，低分片段标注"仅供参考"
- SystemMessage 反幻觉约束：证据不足时固定拒答话术

## 待定（未来触发条件满足时启动）

- 稀疏向量混合检索（SPLADE/BM25 + RRF 融合）：知识库 > 500 篇或方法名精确查询 recall < 60% 时启动
- 认证授权、监控告警
- 多 Agent / Planner 模式
