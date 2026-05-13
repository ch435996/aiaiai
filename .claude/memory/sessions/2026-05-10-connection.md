# AIAIAI Phase 2 RAG 全链路 — Session 2 复盘

**日期**: 2026-05-10  
**项目**: aiaiai — 三维重建/点云补全智能科研助手  
**焦点**: PDF 解析 + 论文库批量导入 + RAG 端到端验证

---

## 1. 会话起点

Session 1 已完成 Phase 1~4 基础设施 + 纯文本摄入管道，Session 2 的目标是把已有 21 篇 PDF 论文库（`D:\Desktop\Folder\research\papers`）批量向量化存入 Pinecone，打通「PDF 上传 → 分段 → 向量化 → 检索 → LLM 回答」全链路。

---

## 2. 新增代码文件（2 个）

| 文件 | 说明 |
|------|------|
| `ingestion/PdfExtractionService.java` | PDFBox 3.0.4 文本提取，30 秒超时，文件校验 |
| `ingestion/BatchImportRunner.java` | `@Profile("batch-import")` 批量导入 Runner |

## 3. 修改文件（4 个）

| 文件 | 变更内容 |
|------|---------|
| `pom.xml` | 新增 `pdfbox` 3.0.4 依赖 |
| `config/EmbeddingConfig.java` | 新增 `.maxSegmentsPerBatch(10)` |
| `controller/KnowledgeController.java` | 新增 `POST /api/knowledge/ingest/pdf` |
| `application.yml` | 新增 `aiaiai.ingestion.pdf-directory` |

---

## 4. 关键 Bug 与修复

### 4.1 Embedding API batch size 超限（唯一根因，决定成败）

- **现象**: 第一次运行 21 篇全部报错：
  ```
  InternalError.Algo.InvalidParameter: Value error, batch size is invalid,
  it should not be larger than 10.: input.contents
  ```
- **原因**: PDF 经 `DocumentByParagraphSplitter(500/100)` 分段后每个文档产生 100+ 个 segment。`EmbeddingStoreIngestor` 将所有 segment 一次性传给百炼 embedding API（`OpenAiEmbeddingModel.embedAll()`），超过百炼 batch size 上限（10），API 拒绝整个请求。
- **修复**: `EmbeddingConfig.java` 中 `OpenAiEmbeddingModel` builder 增加 `.maxSegmentsPerBatch(10)`。LangChain4j 内部将超限 segment 按 10 条一批分次发送，每批走独立 API 调用，符合百炼限制。
- **验证**: 加上该配置后第二次运行 21/21 全部成功，单行改动解决。

### 4.2 超时与文件校验（防御性安全网，未实际触发）

用户提出一个合理担忧：如果 PDFBox 解析损坏/乱码 PDF 时卡死，会阻塞整个批量流程。因此额外添加了以下防御措施：

- `PdfExtractionService`:
  - `ExecutorService` + `Future.get(30s)` 包裹解析逻辑，超时抛 `IOException`
  - `validateFile()` 检查：存在性、可读性、非空、不超过 100MB
- `BatchImportRunner`:
  - `isValidFile()` 预检，不合法直接 skipped
  - 三种结果计数（success / failed / skipped），单文件失败不阻塞后续

**实际运行中上述防御措施均未触发**：21 篇 PDF 都是有效文件且解析远在 30 秒内完成，0 timeout，0 skipped。这些改动是对批量导入管线的一次加固，但并非本次成功导入的原因。

### 4.3 环境变量未持久化

- **现象**: 新开 shell 时 `PINECONE_API_KEY` 等变量为空
- **修复**: 用 PowerShell `[Environment]::SetEnvironmentVariable(..., "User")` 写入注册表

---

## 5. 批量导入结果（21/21 全部成功）

| # | 论文 | 提取字符数 |
|---|------|-----------|
| 1 | 2023 AdaPoinTr | 112,297 |
| 2 | 2024 AAAI CRA PCN | 57,898 |
| 3 | 2024 ECCV ProtoComp | 47,928 |
| 4 | 2025 PointDiffuse | 54,272 |
| 5 | 2025 Back to Basics (44MB) | 68,263 |
| 6 | 3DV 2018 PCN | 56,430 |
| 7 | Survey of Point Cloud Completion | 121,849 |
| 8 | AAAI 2024 PointAttN | 47,844 |
| 9 | CVPR 2021 PoinTr (中文版) | 61,598 |
| 10 | CVPR 2022 PMP Net++ | 82,566 |
| 11 | CVPR 2023 ProxyFormer | 50,239 |
| 12 | CVPR 2018 FoldingNet | 47,263 |
| 13 | CVPR 2023 AnchorFormer | 47,281 |
| 14 | CVPR 2025 PCDreamer | 54,079 |
| 15 | ECCV 2022 SeedFormer | 60,047 |
| 16 | ICCV 2021 PoinTr | 46,464 |
| 17 | ICCV 2021 SnowflakeNet | 51,941 |
| 18 | ICCV 2021 Point Transformer | 43,348 |
| 19 | PointAttN | 47,844 |
| 20 | ShapeFormer (CVPR 2022) | 46,604 |
| 21 | 基于AdaPoinTr模型...（李恩在，中文论文） | 70,607 |

**总计**: 21 篇，约 130 万字符，耗时约 4 分钟，0 失败 0 跳过。

---

## 6. RAG 端到端验证

**测试问题**:
> "关于ProtoComp这篇生成式点云补全论文，我们老师希望我们理解transformer底层细节，所以编码器这里什么是Q，什么是K，什么是V？"

**验证结果**: ✅ 全链路通过

1. `POST /api/chat` → ChatOrchestrator → AiServices
2. LLM 判断需要检索 → 调用 `searchKnowledge("ProtoComp transformer encoder Q K V attention")`
3. `RetrievalServiceImpl` → Embedding → Pinecone `knowledge` 命名空间查询
4. 命中 ProtoComp 论文相关段落（点代理机制、自注意力模块、编码器架构）
5. LLM 整合检索结果 + 自身知识 → 输出结构化回答（含公式、流程图、表格总结）
6. ChatMemory 自动写入 Redis

**回答质量**: 准确引用了 ProtoComp 的架构细节（DGCNN/FPS 特征提取、Point Proxy、多头自注意力位置），并解释了 Q/K/V 均来自同一组点代理特征的自注意力机制。

---

## 7. 当前架构全景

```
POST /api/chat  ←──  方案 B: 日常对话
POST /api/knowledge/ingest      ←── 方案 B: 纯文本上传
POST /api/knowledge/ingest/pdf  ←── 方案 B: PDF 单篇上传
mvn spring-boot:run -Dspring-boot.run.profiles=batch-import  ←── 方案 A: 命令行批量导入
```

```
PDF/Text → PdfExtractionService (30s超时) → DocumentByParagraphSplitter(500/100)
→ OpenAiEmbeddingModel (maxSegmentsPerBatch=10, 百炼 text-embedding-v3, 1024维)
→ PineconeEmbeddingStore (knowledge namespace, index: aiaiai-knowledge)
→ retrievalService.search() → LLM searchKnowledge tool → 回答
```

---

## 8. 环境配置

```bash
# 环境变量（已持久化到 Windows User 级别）
PINECONE_API_KEY=pcsk_xxx
DEEPSEEK_API_KEY=
EMBEDDING_API_KEY=

# JDK 17
JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot
```

```yaml
# application.yml 关键配置
aiaiai:
  embedding:
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
    model-name: text-embedding-v3
    dimensions: 1024           # 百炼仅支持 [64,128,256,512,768,1024]
  pinecone:
    dimension: 1024
    index: aiaiai-knowledge
  ingestion:
    max-segment-size: 500
    max-overlap-size: 100
    pdf-directory: D:\Desktop\Folder\research\papers
```

---

## 9. 累计项目文件（22 个源文件）

```
aiaiai/src/main/java/com/aiaiai/
├── AiaiaiApplication.java
├── config/
│   ├── LLMConfig.java              # OpenAiChatModel → DeepSeek
│   ├── EmbeddingConfig.java        # OpenAiEmbeddingModel → 百炼, maxSegmentsPerBatch=10
│   ├── PineconeConfig.java         # 双命名空间 (knowledge + memory)
│   ├── RedisConfig.java
│   └── IngestionConfig.java        # EmbeddingStoreIngestor
├── ingestion/                       # [Session 2 新增]
│   ├── PdfExtractionService.java    #   PDFBox + 30s 超时 + 文件校验
│   └── BatchImportRunner.java       #   @Profile("batch-import")
├── memory/
│   ├── RedisChatMemoryStore.java
│   ├── MemoryService.java
│   └── MemoryServiceImpl.java
├── retrieval/
│   ├── RetrievalService.java
│   └── RetrievalServiceImpl.java
├── ai/
│   ├── AiaiaiTools.java            # searchKnowledge, saveMemory
│   └── Assistant.java
├── orchestrator/
│   └── ChatOrchestrator.java       # ChatMemoryProvider 模式
└── controller/
    ├── ChatController.java         # POST /api/chat
    ├── KnowledgeController.java    # POST /api/knowledge/ingest + /ingest/pdf
    └── dto/
        ├── ChatRequest.java
        ├── ChatResponse.java
        └── IngestRequest.java
```

---

## 10. 后续待做

- PDF Paper 元数据增强（从 PDF 自身提取标题、作者，而非仅用文件名）
- start 自动检测 PDF 段落编排质量并反馈
- 流式响应
- 前端上传页面
- 认证授权
