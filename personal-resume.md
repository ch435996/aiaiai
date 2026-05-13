项目经历：

流屿 - 多租户实时协同图像管理平台    后端开发     2025-10   2025-12
简介：构建面向团队协作的多租户 SaaS 云端图片管理平台，支持海量图片存储、实时协作编辑与多维度图片搜索，并通过多级缓存、Disruptor 异步队列与分段锁并发控制优化系统在高并发场景下的性能与稳定性
技术栈：Spring Boot、MySQL、Redis、WebSocket、Disruptor、Caffeine、COS、Sa-Token、RBAC
•并发控制：基于 spaceId 实现分段锁，并结合编程式事务控制事务边界，解决团队空间并发上传导致的容量超限问题
•协同架构： 基于 WebSocket + 事件驱动 构建实时协同链路，通过编辑锁保障多人协同编辑的并发安全，引入 Disruptor 与异步发送机制将事件广播与网络 I/O 解耦，避免 WebSocket I/O 阻塞反压业务线程
•协同架构优化： 基于 Hash 路由构建多 Disruptor 阵列，实现跨图片并行、单图片串行消费；排查发现 RingBuffer 预分配导致的引用滞留问题，在消费末端主动清理 Session 与上下文引用，降低老年代压力并消除 Full GC 风险。
•缓存架构：基于 Caffeine + Redis 构建多级缓存，以随机 TTL 与空对象防御缓存雪崩、穿透；采用 Cache Aside 策略结合 Spring 事件机制，解耦并同步清理多级缓存，兼顾单机高并发吞吐与数据一致性
•缓存架构重构：废弃高离散分页缓存，收敛至图片实体与字典数据；通过 MySQL 联合索引与延迟关联完成 ID 级查询，结合实体缓存与 Cache Aside 精准失效，显著降低缓存污染与一致性问题。


基于 Agentic RAG 的科研知识助手     独立架构设计与全栈开发    2026-01   2026-03

简介：独立设计并全栈开发面向三维重建课题组的 Agentic RAG 知识引擎，通过 Tool Calling 自主调度、长短期分级记忆与流式可观测协议实现论文检索→问答→记忆沉淀全链路闭环，已覆盖 2018—2025 点云补全主流方法，并完成端到端验证
技术栈：Spring Boot、LangChain4j、Redis、Pinecone、DeepSeek、SSE
•Agentic 可控调度与分层解耦：基于 LangChain4j 构建 Tool Calling 调度引擎。Java 严控会话生命周期，LLM 仅自主决定知识检索 / 偏好持久化 / 记忆召回三类工具的调用，实现控制面（意图路由）与数据层（RAG / 记忆）的分层解耦
•流式可观测性与结构化审计：基于 SseEmitter 设计 Agent 流式事件协议，通过异步回调实时推送检索 Query、召回片段、来源出处与归一化置信度，实现 Agent 执行链路的可观测、可解释与可溯源
•设计长短期分级记忆体系：短期记忆基于 Redis 滑动窗口维护会话上下文；长期记忆由 LLM 按需写入 Pinecone 独立向量空间，与知识库物理隔离，有效防止长轮对话下的向量污染
•反幻觉决策与置信度护栏：在 SystemMessage 层注入引用约束与主动拒答机制；将底层检索分数归一化为 HIGH/MEDIUM/LOW 三级置信度，并针对低分片段动态拼接警示标记，引导模型规避过度引用，有效降低知识幻觉风险。



专业技能：

Java:熟悉面向对象，理解集合框架（ArrayList、HashMap）底层实现、反射与泛型机制
•JUC:熟悉JMM、CAS、AQS、Synchronized锁升级、ConcurrentHashMap、线程池等，并掌握 Disruptor 高性能无锁队列原理
•JVM:熟悉类加载与双亲委派机制、内存结构、垃圾回收机制及常见垃圾回收器、了解 OOM 诊断及常见内存泄漏定位
•MySQL:熟悉InnoDB存储引擎、事务与MVCC机制、索引，具备 SQL 调优经验，能结合 EXPLAIN 进行慢 SQL 排查
•Redis:熟悉 Redis 常见数据结构与持久化机制，具备多级缓存设计经验，能够解决缓存穿透、雪崩与一致性问题
•中间件：熟悉 RocketMQ 等消息中间件，具备处理分布式环境下异步解耦、削峰填谷的架构意识
•其他:熟悉常见数据结构与算法；了解 HTTP/HTTPS 等网络协议以及常见设计模式
•AI 前沿：熟悉 Agentic AI 应用架构模式与 MCP / Skill 等扩展机制；实践 AI-Native 协同开发范式，能够基于 Claude / Trae 完成需求拆解与架构迭代，并构建可控的 AI Code Review 工作流，通过前置规范约束收敛 AI 生成代码的不确定性。



