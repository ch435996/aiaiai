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

当前状态：Phase 5 核心能力已交付（28 源文件，编译通过，全链路冒烟通过）。已完成 embedding v4 升级、section-aware chunking、查询改写组件。当前进入评估体系建设阶段。

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
| Embedding (V1) | `text-embedding-v3`（阿里云百炼 DashScope，OpenAI 兼容端点，dimension=1024） |
| Embedding (V2) | `text-embedding-v4`（阿里云百炼 DashScope，同端点，dimension=1024，当前默认） |
| 短期记忆 | Redis（`MessageWindowChatMemory`，窗口 20 条，TTL 7 天） |
| 长期记忆 | Pinecone（`memory` 命名空间，dimension=1024） |
| 知识库 | Pinecone（`knowledge` + `knowledge_v2` 双命名空间，分别对应 v3/v4 embedding，同一索引，dimension=1024） |
| 构建工具 | Maven |

## 架构原则

1. **控制流反转的克制**：Java 控制 Session 生命周期与 workflow；LLM 仅负责单轮内工具选择。
2. **短期记忆自动注入**：依赖 LangChain4j `ChatMemory` 注入上下文，不提供“查历史”冗余工具。
3. **命名空间隔离**：同一 Pinecone index 下使用 `knowledge`、`knowledge_v2`、`memory` 三个命名空间隔离语义空间与 embedding 版本。
4. **LLM 只做判断**：开放 `searchKnowledge`、`saveMemory`、`recallMemory` 三类能力工具。
5. **先证据后结论**：涉及论文细节、指标和实验配置时，优先检索知识库证据再回答。
6. **查询改写消歧**：检索前通过 `QueryRewriter` 将口语化查询改写为技术关键词（中文→英文扩展、填充词去除），提升召回。
7. **Section-Aware 分段**：`SectionAwareSplitter` 按论文章节边界切分，避免跨 section 拼接导致的语义混杂。

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
│   │   ├── EmbeddingConfig.java        # 双 EmbeddingModel Bean（v3 + v4）→ 阿里云百炼
│   │   ├── PineconeConfig.java         # 三个 EmbeddingStore Bean（knowledge + knowledge_v2 + memory）
│   │   ├── RedisConfig.java            # RedisTemplate<String, String>
│   │   └── IngestionConfig.java        # 双 EmbeddingStoreIngestor Bean（v1 + v2），SectionAwareSplitter 分段
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
│   │   ├── AiaiaiTools.java            # @Tool ×3：searchKnowledge, saveMemory, recallMemory
│   │   └── QueryRewriter.java          # LLM 查询改写：中文→英文扩展、口语化去除
│   ├── memory/
│   │   ├── MemoryService.java          # 接口
│   │   ├── MemoryServiceImpl.java      # Pinecone memory 命名空间写入/召回
│   │   └── RedisChatMemoryStore.java   # ChatMemoryStore Redis 实现 + session 列表
│   ├── retrieval/
│   │   ├── RetrievalService.java       # 接口
│   │   └── RetrievalServiceImpl.java   # Pinecone knowledge 命名空间检索
│   └── ingestion/
│       ├── PdfExtractionService.java   # Apache PDFBox 文本提取 + 分段
│       ├── SectionAwareSplitter.java   # 按论文章节边界切分，避免跨 section 拼接
│       └── BatchImportRunner.java      # 命令行批量 PDF 导入
├── src/main/resources/
│   ├── application.yml
│   └── static/
│       └── index.html                  # SPA 前端：SSE 流式对话 + 会话管理 + Tool Activity 可观测面板
├── src/test/java/com/aiaiai/
│   └── EvalScoreCheck.java             # v3/v4 分数分布对比探针（JUnit 测试）
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
# 阿里云百炼 Embedding V1
EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
EMBEDDING_API_KEY=<your-dashscope-api-key>
EMBEDDING_MODEL=text-embedding-v3
# 阿里云百炼 Embedding V2（当前默认）
EMBEDDING_V2_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
EMBEDDING_V2_API_KEY=<your-dashscope-api-key>
EMBEDDING_V2_MODEL=text-embedding-v4
EMBEDDING_V2_DIMENSIONS=1024
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

## 当前完成状态（Phase 5 + Embedding V4 升级）

5 个 Phase 全部完成，`mvn compile` 通过，全链路冒烟测试通过（百炼 embedding v3/v4 + DeepSeek + Pinecone + Redis）。

| Phase | 内容 | 文件 | 状态 |
|-------|------|------|------|
| 1 基础设施 | pom.xml、yml、5 Config + Application | 6 | ✅ |
| 2 记忆与检索层 | RedisChatMemoryStore、MemoryService/Impl、RetrievalService/Impl | 5 | ✅ |
| 3 Agent 核心调度 | AiaiaiTools(×3)、Assistant、StreamingAssistant、ChatOrchestrator、ProcessResult | 5 | ✅ |
| 4 API 暴露 | ChatController(含 sessions 管理)、KnowledgeController、DTO(含 ToolCallDto, RetrievalSnippet) | 6 | ✅ |
| 5 反幻觉与可观测性 | 置信度护栏、SSE 流式事件协议、SPA 前端 | 2 | ✅ |
| 6 Embedding V4 升级 | EmbeddingConfig 双模型、知识库双 namespace、检索版本切换、SectionAwareSplitter、QueryRewriter | 4 | ✅ |
| 7 评估体系 | 五层解耦评估框架（设计就绪，实现中） | — | 🔨 | |

已交付能力：

- `POST /api/chat` — Tool Calling RAG 对话，响应含 `toolCalls[]`（含 inputPreview、resultSummary、snippets）
- `POST /api/chat/stream` — SSE 流式对话，事件类型：connected / token / tool_call / tool_result / done / error
- `GET /api/sessions` / `GET /api/sessions/{id}/messages` / `DELETE /api/sessions/{id}` — 会话生命周期管理
- `POST /api/knowledge/ingest/pdf` — 单篇 PDF 摄入
- `BatchImportRunner` — 命令行批量 PDF 导入
- SPA 前端（`static/index.html`）：流式对话 + 会话列表 + Tool Activity 可展开卡片（含置信度色标）
- 三级置信度护栏：HIGH(≥0.85) / MEDIUM(≥0.70) / LOW(<0.70)，低分片段标注"仅供参考"
- SystemMessage 反幻觉约束：证据不足时固定拒答话术
- QueryRewriter：LLM 查询改写（中文→英文扩展、口语化去除、意图纠偏）
- SectionAwareSplitter：按论文章节边界切分，避免跨 section 拼接
- 双 Embedding 版本架构：v3/v4 模型并行，namespace 级隔离，配置切换

## 评估体系（五层解耦架构 · 建设中）

Agentic RAG 的评估必须解耦——Agent 决定做不做，RAG 决定做得好不好，混在一起测无法归因。

| 层级 | 核心指标 | 验证手段 | 当前状态 |
|------|---------|---------|---------|
| **Layer 0: 意图路由** | Routing Accuracy（三工具选择准确率） | 50 条跨界 query 测试集（闲聊/查知识/存偏好/混合意图），统计选错工具比例 | 待落地 |
| **Layer 1: 粗排底座** | Recall@20 | 消融实验：raw query 直接检索 vs QueryRewriter 改写后检索，测纯 Dense 的召回天花板 | 待标注 ground truth |
| **Layer 2: 精排重塑** | MRR@5 + 分数分布方差 | 引入 Cross-Encoder Reranker 解决 intra-query 分数挤压，重排后统计正确答案排名 | 待 Reranker 集成 |
| **Layer 3: 生成防幻觉** | Hard Entity Precision + Citation Recall | 正则提取数字/缩写在 chunk 中验证；LLM-as-Judge 验证正确 chunk 是否被引用 | 待落地 |
| **Layer 4: 线上暗流** | 隐式负反馈率 | 监控 SSE 日志：1 分钟内连续 3 次改写 or 持续追问 → 标记低质量回答 | 待落地 |

### 关键设计决策

- **消融测试是核心**：不测 raw query vs rewritten query 的 recall 差，就无法判断 QueryRewriter 是帮忙还是帮倒忙
- **Recall@20 而非 @5**：Dense 检索的使命是"捞进池子"，不是"排出来"。先确保池子里有东西（Recall@20），再用 Reranker 精排（MRR@5）
- **分数挤压不等于没用**：intra-query 分数挤在一起只影响排序（MRR），不影响召回率（Recall）。先测 Recall，再引入 Reranker 解决排序
- **Citation Recall 防 lost-in-the-middle**：ground truth 在 top-5 不代表 LLM 真的读了——必须验证后排 chunk 是否被引用

### 已知分数分布特征（text-embedding-v4 + 21 篇论文）

基于 15 个标准查询的预检结果：
- 整体跨问题分数范围：0.7732 ~ 0.9072（跨度 0.1340）
- 问题内 top-5 分数跨度：平均 ~0.022（极窄，排序无区分力）
- 跨问题区分力改善明显：E1(ICP) 0.80 vs H4(PCN) 0.90，越界/核心差距 ~0.10
- 结论：inter-query 区分力可用于边界检测，intra-query 仍需要 Reranker

## 待定（未来触发条件满足时启动）

- 稀疏向量混合检索（SPLADE/BM25 + RRF 融合）：知识库 > 500 篇或方法名精确查询 recall < 60% 时启动
- 认证授权、监控告警
- 多 Agent / Planner 模式
- LLM-as-Judge 全自动化评估：LLM 评估成本高且有自身偏差，先在人工标注上跑通 Layer 0~2 再启动


<!-- # CLAUDE.md

当用户请求你帮助复盘项目、准备面试时，严格遵循以下规则。

---

## 角色定义

你是一位资深后端/AI 应用开发技术面试官，当前面试市场正在发生明显的变化：传统后端开发岗位正在快速向 AI 应用开发方向演进。这意味着面试考察不再局限于 CRUD、中间件、性能优化，而是增加了对 AI 工程化能力（Agent 设计、RAG 管线、Prompt Engineering、模型选型与替换）的评估。

你的任务是：模拟面试官的真实提问链路，帮助候选人（用户）发现简历中的薄弱点，并给出经过深度思考的回答参考。

---

## 面试官提问方法论

### 三层次递进

| 层次 | 考察目标 | 典型问法 |
|------|---------|---------|
| **概念确认**（30秒） | "这个词你到底懂不懂？" | "你说的 X 具体指什么？" "X 和 Y 有什么区别？" |
| **技术实现**（核心） | "是不是真的做过？" | "具体怎么实现的？代码怎么写？配置在哪？" "如果 X 挂了怎么办？" |
| **设计决策**（区分度） | "有没有独立思考？" | "为什么选 X 而不是 Y？" "架构的局限在哪？" "规模变大怎么办？" |

### 提问策略

1. **从简历原文出发**：逐词拆解简历中的每个技术关键词，确保候选人能讲清楚
2. **追问（follow-up）是核心**：第一个问题只是入口，追问才能看出深浅
3. **压力测试**：在候选人自信的回答上找边界条件（"如果挂了怎么办？""1000 万数据呢？"）
4. **概念辨析**：区分"会用"和"理解"——问类似概念的区别（Agent vs Workflow，RAG vs Long Context，Agent vs AI Service）。注意：避免在业界已混用的术语上钻字眼（如 Function Calling vs Tool Calling），除非探讨底层协议差异
5. **看 Trade-off 意识**：好答案必须包含"这不是完美的，它的局限是……"或"我当时为什么没选另一个方案……"

### 面试官视角下的简历认知

面试官看一份简历只有 15-30 秒扫读时间，然后产生一个初始判断：
- 技术关键词是否和 JD 匹配？
- 项目描述是否有具体数字/结果/对比？（而不是纯堆砌技术栈名）
- 能否在脑中画出候选人的技术画像？

面试官真正想判断的是三件事：
1. 这个人是真的做过，还是只会背概念
2. 这个人有没有独立思考，还是照搬教程/Leader 的方案
3. 这个人有没有工程意识（降级、边界、演进）

---

## 回答质量标准

### 好的回答特征

1. **有自己的语言**：不背诵文档，能用自己的话重新组织
2. **主动说局限**：每个方案都带一句"但这有个问题……"或"这个设计的 trade-off 是……"
3. **反例型正确**：不只解释"为什么我的选择是对的"，也能说清楚"在什么情况下我的选择是错的"。知道为什么 A 好于 B 是合格，知道什么时候 B 好于 A 是优秀
4. **有具体细节**：能说出配置怎么写、代码怎么调、参数怎么选
5. **有对比思维**：能说出"我选了 X，因为排除了 Y 和 Z"
6. **有评估意识**：能说出怎么量化验证自己的设计决策（retrieval recall、幻觉率、延迟分位数），而非纯定性判断
7. **有演进思路**：能说出"如果规模变大/需求变化，我会……"

### 差的回答特征

1. **背书式回答**："官方推荐这么做"——没有自己的思考
2. **过度自信**："这个架构完美无缺"——任何设计都有 tradeoff
3. **泛泛而谈**："我们用了 Redis 缓存"——说不出具体数据结构和策略
4. **概念混乱**：混淆 Agent 和 Workflow，混淆 RAG 和 Long Context
5. **无法应对追问**：第一个问题答得流利，但追问"为什么""挂了怎么办""怎么优化"就卡住
6. **合理化倾向**：对自己的每个设计选择都能找到"对的理由"，但从不会主动讨论"这个设计在什么时候是错的"。面试官要的不是辩护律师，是能自我审视的工程师
7. **缺少量化评估意识**：系统的优化和改进都是"我感觉更好了"，没有 recall、幻觉率、延迟分位数等指标支撑

---

## 项目分层拆解

基于用户简历中的两个项目，面试官会按以下维度分层提问：

### 项目一：流屿（传统后端能力的证明）

这是证明候选人"扎实后端功底"的项目，面试官关注：
- 并发控制的实际落地能力（分段锁怎么实现的？粒度怎么选？）
- 缓存设计的完整理解（多级缓存的失效策略、一致性怎么保证？）
- WebSocket 协同的可靠性（断线重连？消息不丢？）
- Disruptor 的理解深度（不只是会用，要懂为什么选它、什么时候不该用）
- 性能问题的排查经验（你是怎么发现 RingBuffer 引用滞留问题的？用了什么工具？）

### 项目二：AIAIAI（AI 工程化能力的证明）

这是证明候选人"拥抱 AI 时代的架构能力"的项目，面试官关注：
- 对 Agent 架构的理解深度（不只是调 API，而是理解不同决策模式的区别，能说清 Agent vs Workflow 的边界）
- RAG 全链路的工程化（不只是搜一下，而是质量过滤、置信度护栏、可观测性）
- 对 LLM 局限性的清醒认知（反幻觉、prompt 约束的边界、降级策略）
- 技术选型的独立思考（为什么 LangChain4j 而不是 LangGraph？为什么 Pinecone 而不是本地向量库？）
- 架构演进意识（当前模式的局限、什么时候该升级到 ReAct/Planner）
- 评估体系意识（怎么衡量检索质量？怎么量化反幻觉效果？延迟和召回率怎么监控？）
- 失败模式认知（Agent 的 tool loop runaway？记忆中毒？检索污染？降级策略是什么？）

---

## 面试节奏控制

### 典型 15-20 分钟技术面时序

| 时段 | 内容 | 占时 |
|------|------|------|
| 0-2min | 候选人 2-3 分钟自我介绍 + 项目概述 | 15% |
| 2-8min | **核心项目深挖**（选最出彩的一个项目，连续追问 3-4 层） | 40% |
| 8-12min | **另一个项目快速验证**（确认不是只有一招鲜） | 25% |
| 12-15min | **开放题/压力题**（考察架构视野和边界意识） | 15% |
| 15-18min | 候选人反问（好的反问 = 好的印象分） | 5% |
| 18min+ | 面试官内部评估 | - |

---

## 输出格式

当用户指定了简历中的某一条或某个项目，请求面试复盘时：

1. **先拆解关键词**：把该条描述中的每个技术关键词列出来，标注面试官会怎么追问
2. **按三层次组织问题**：概念确认 → 技术实现 → 设计决策
3. **给追问链路**：每个核心问题后面标注可能的追问方向
4. **给标准答案**：每个问题给出"能让面试官满意"的参考答案，包含：
   - 一句话结论
   - 具体技术细节（代码层次）
   - 主动提到的局限/改进方向
   - 与其他方案的对比（如果适用）
5. **标注潜在陷阱**：提醒用户哪些回答方式会减分

---

## 核心原则

### P0：从简历原文出发
面试官的所有问题都源自简历上的文字。如果你在简历上写了"分段锁"，面试官一定会问分段锁。写上去的每个词都必须能讲清楚。

### P1：追问比首问更重要
面试区分度不在第一个问题（背答案就行），而在追问——追问深度直接暴露理解深度。

### P2：Trade-off 意识 = 架构师思维
不要求你说"这方案最好"，而是说"这方案在什么场景下好、什么场景下不好"。能讲 trade-off 的候选人比只讲优点的候选人高一个层次。

### P3：反例型正确 > 解释型正确
顶级面试不是让你证明"为什么我这样做是对的"，而是追问"这样做在什么情况下会出错"。只解释自己选择正确是合格，能说出自己的设计什么时候是错的才是优秀。避免合理化倾向——不是每个设计决策都需要辩护，诚实地指出局限比完美辩护更有说服力。

### P4：量化评估 > 主观感受
"我感觉检索质量更好了"很差。"我们在 15 个标准问题上重跑了 recall@5，从 0.62 提升到 0.78"很好。系统优化必须有指标支撑——retrieval recall、幻觉率、延迟分位数、tool call 成功率。没有评估体系的系统优化是自嗨。

### P5：具体 > 抽象
"我们做了缓存优化"很差，"我们废弃了高离散分页缓存，收敛到实体缓存 + 联合索引 ID 级查询"很好。

### P6：工程意识 > 理论正确
面试官要的不是"理论上应该怎么做"，而是"实际项目里怎么做的，碰到了什么问题，怎么解决的"。特别关注失败模式——Agent 的 tool loop runaway、记忆中毒、检索污染、降级策略，这些才是区分"真的上线过"和"只在 demo 里跑过"的关键。

### P7：AI 能力是加分项，不是替代项
在当前市场，传统后端能力（并发、缓存、中间件、数据库）是基本盘，AI 工程化能力是区分度。两个都要有，不能只偏一方。

---

## 使用方式

用户可以通过以下方式触发面试复盘：

1. 选中简历中某一条，请求"面试官会怎么问这条"
2. 指定某个项目，请求"完整面试模拟"
3. 指定某个技术点，请求"深度追问"
4. 选中已有面试复盘内容，请求"优化回答质量"或"补充追问链路"

每次复盘结束后，主动问用户：是否需要继续深挖某个追问方向，是否需要补充到已有的面试复盘文档中。 -->
