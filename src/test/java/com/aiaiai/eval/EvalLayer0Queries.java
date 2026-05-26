package com.aiaiai.eval;

import java.util.*;

/**
 * Layer 0: Intent Routing Evaluation — 38-query test set.
 *
 * Tests whether the Agent correctly decides which tool(s) to call
 * (or not call). This is the "Agent" part of Agentic RAG —
 * search quality is meaningless if the LLM doesn't invoke search at all.
 *
 * Each query has:
 *   expectedTools — tools that MUST be called
 *   forbiddenTools — tools that MUST NOT be called
 *
 * Metrics:
 *   Routing Accuracy = % of queries where all expected AND no forbidden
 *   Tool-level Precision / Recall
 */
public class EvalLayer0Queries {

    public enum Layer0Category {
        CHAT,            // Greeting, chitchat → no tools
        SEARCH,          // Paper/method question → searchKnowledge only
        SAVE,            // Explicit remember request → saveMemory only
        RECALL,          // Query about stored preferences → recallMemory only
        SEARCH_SAVE,     // Mixed intent → searchKnowledge + saveMemory
        BOUNDARY         // Out-of-scope → no tools (gatekeeping)
    }

    public record Layer0Query(
            String id,
            String query,
            Layer0Category category,
            Set<String> expectedTools,   // empty set = no tools expected
            Set<String> forbiddenTools,  // tools that must NOT be called
            String note
    ) {}

    public static List<Layer0Query> all() {
        List<Layer0Query> list = new ArrayList<>();

        // ── CHAT: greetings / casual (should call NO tools) ──
        list.add(q("C1", "你好", Layer0Category.CHAT,
                none(), none(), "基础问候"));
        list.add(q("C2", "早上好，今天有什么能帮我的？", Layer0Category.CHAT,
                none(), none(), "问候变体"));
        list.add(q("C3", "谢谢你的帮助", Layer0Category.CHAT,
                none(), none(), "感谢"));
        list.add(q("C4", "你是谁", Layer0Category.CHAT,
                none(), none(), "自我介绍"));
        list.add(q("C5", "你能做什么", Layer0Category.CHAT,
                none(), allTools(), "能力询问→不应调工具"));
        list.add(q("C6", "今天天气不错", Layer0Category.CHAT,
                none(), none(), "无关闲聊"));

        // ── SEARCH: knowledge-seeking (searchKnowledge only) ──
        list.add(q("K1", "PoinTr的网络结构是什么",
                Layer0Category.SEARCH,
                Set.of("searchKnowledge"), Set.of("saveMemory"),
                "方法细节→应检索"));
        list.add(q("K2", "SnowflakeNet论文里的SPD模块怎么工作的",
                Layer0Category.SEARCH,
                Set.of("searchKnowledge"), Set.of("saveMemory"),
                "模块细节→应检索"));
        list.add(q("K3", "点云补全中coarse-to-fine策略是什么",
                Layer0Category.SEARCH,
                Set.of("searchKnowledge"), Set.of("saveMemory"),
                "概念解释→应检索"));
        list.add(q("K4", "PCN和FoldingNet在补全任务上有什么差异",
                Layer0Category.SEARCH,
                Set.of("searchKnowledge"), Set.of("saveMemory"),
                "方法对比→应检索"));
        list.add(q("K5", "ShapeNet数据集在点云补全中怎么用的",
                Layer0Category.SEARCH,
                Set.of("searchKnowledge"), Set.of("saveMemory"),
                "数据集用法→应检索"));
        list.add(q("K6", "Chamfer Distance怎么计算的",
                Layer0Category.SEARCH,
                Set.of("searchKnowledge"), Set.of("saveMemory"),
                "指标定义→应检索"));
        list.add(q("K7", "SeedFormer的PC-Attn模块输入是什么维度",
                Layer0Category.SEARCH,
                Set.of("searchKnowledge"), Set.of("saveMemory"),
                "超参数细节→应检索"));
        list.add(q("K8", "点云补全模型一般用什么优化器训练",
                Layer0Category.SEARCH,
                Set.of("searchKnowledge"), Set.of("saveMemory"),
                "训练策略→应检索"));
        list.add(q("K9", "ProtoComp选了多少个prototype",
                Layer0Category.SEARCH,
                Set.of("searchKnowledge"), Set.of("saveMemory"),
                "特定超参数→应检索"));
        list.add(q("K10", "点云补全常用的损失函数有哪些",
                Layer0Category.SEARCH,
                Set.of("searchKnowledge"), Set.of("saveMemory"),
                "方法总览→应检索"));

        // ── SAVE: explicit memory save (saveMemory only) ──
        list.add(q("S1", "记住我们课题组偏好使用Chamfer Distance作为主要损失函数",
                Layer0Category.SAVE,
                Set.of("saveMemory"), Set.of("searchKnowledge"),
                "明确记住指令→应存"));
        list.add(q("S2", "存一下，我们组主要用F-score做评价指标",
                Layer0Category.SAVE,
                Set.of("saveMemory"), Set.of("searchKnowledge"),
                "偏好存储→应存"));
        list.add(q("S3", "记住：我关注推理速度而不是模型参数大小",
                Layer0Category.SAVE,
                Set.of("saveMemory"), Set.of("searchKnowledge"),
                "研究约束→应存"));
        list.add(q("S4", "保存这条偏好：我们后续实验batch size统一用32",
                Layer0Category.SAVE,
                Set.of("saveMemory"), Set.of("searchKnowledge"),
                "实验规范→应存"));
        list.add(q("S5", "记下来，我们组后续主要用ShapeNet数据集做benchmark",
                Layer0Category.SAVE,
                Set.of("saveMemory"), Set.of("searchKnowledge"),
                "数据集偏好→应存"));

        // ── RECALL: memory lookup (recallMemory only) ──
        list.add(q("R1", "我们课题组之前偏好什么损失函数来着",
                Layer0Category.RECALL,
                Set.of("recallMemory"), Set.of("searchKnowledge", "saveMemory"),
                "查询历史偏好→应召回记忆"));
        list.add(q("R2", "之前让你记住的那些实验规范是什么",
                Layer0Category.RECALL,
                Set.of("recallMemory"), Set.of("searchKnowledge", "saveMemory"),
                "查询存储规范→应召回记忆"));
        list.add(q("R3", "我们组主要用什么评价指标",
                Layer0Category.RECALL,
                Set.of("recallMemory"), Set.of("searchKnowledge", "saveMemory"),
                "指标偏好查询→应召回记忆"));
        list.add(q("R4", "之前我们讨论过的实验配置你还记得吗",
                Layer0Category.RECALL,
                Set.of("recallMemory"), Set.of("searchKnowledge", "saveMemory"),
                "历史配置查询→应召回记忆"));

        // ── SEARCH_SAVE: mixed intent (searchKnowledge + saveMemory) ──
        list.add(q("X1", "PCN用了几个MLP做coarse生成？顺便记住我们组偏好轻量模型",
                Layer0Category.SEARCH_SAVE,
                Set.of("searchKnowledge", "saveMemory"), none(),
                "检索+记忆→双工具"));
        list.add(q("X2", "查一下PoinTr的几何感知transformer，记住我关注推理延迟",
                Layer0Category.SEARCH_SAVE,
                Set.of("searchKnowledge", "saveMemory"), none(),
                "检索+偏好存储→双工具"));
        list.add(q("X3", "ProtoComp的特征融合怎么做的，记下来我们组用CD做主要指标",
                Layer0Category.SEARCH_SAVE,
                Set.of("searchKnowledge", "saveMemory"), none(),
                "方法细节+偏好→双工具"));
        list.add(q("X4", "查一下点云补全用的数据集有哪些，顺便记ShapeNet是我们主力",
                Layer0Category.SEARCH_SAVE,
                Set.of("searchKnowledge", "saveMemory"), none(),
                "meta信息+存储→双工具"));
        list.add(q("X5", "diffusion做补全有什么优势？记住我关注推理延迟",
                Layer0Category.SEARCH_SAVE,
                Set.of("searchKnowledge", "saveMemory"), none(),
                "技术路线+偏好→双工具"));

        // ── BOUNDARY: out-of-scope (should NOT call searchKnowledge) ──
        list.add(q("E1", "怎么用Python写一个快速排序",
                Layer0Category.BOUNDARY,
                none(), Set.of("searchKnowledge", "saveMemory"),
                "编程问题→不应检索"));
        list.add(q("E2", "今天晚饭吃什么好",
                Layer0Category.BOUNDARY,
                none(), Set.of("searchKnowledge", "saveMemory"),
                "生活问题→不应检索"));
        list.add(q("E3", "帮我写一封请假邮件",
                Layer0Category.BOUNDARY,
                none(), Set.of("searchKnowledge", "saveMemory"),
                "写作任务→不应检索"));
        list.add(q("E4", "3D Gaussian Splatting的原理是什么",
                Layer0Category.BOUNDARY,
                none(), Set.of("searchKnowledge", "saveMemory"),
                "越界-新视角合成→不应检索"));
        list.add(q("E5", "ICP配准算法的步骤",
                Layer0Category.BOUNDARY,
                none(), Set.of("searchKnowledge", "saveMemory"),
                "越界-传统配准→不应检索"));
        list.add(q("E6", "推荐一个好用的Python IDE",
                Layer0Category.BOUNDARY,
                none(), Set.of("searchKnowledge", "saveMemory"),
                "工具推荐→不应检索"));
        list.add(q("E7", "自动驾驶的3D目标检测用什么模型",
                Layer0Category.BOUNDARY,
                none(), Set.of("searchKnowledge", "saveMemory"),
                "越界-自动驾驶→不应检索"));
        list.add(q("E8", "怎么用蓝牙连接耳机",
                Layer0Category.BOUNDARY,
                none(), Set.of("searchKnowledge", "saveMemory"),
                "完全无关→不应调任何工具"));

        return list;
    }

    // ── helpers ──

    private static Layer0Query q(String id, String query, Layer0Category cat,
                                  Set<String> expected, Set<String> forbidden,
                                  String note) {
        return new Layer0Query(id, query, cat, expected, forbidden, note);
    }

    private static Set<String> none() { return Set.of(); }

    private static Set<String> allTools() {
        return Set.of("searchKnowledge", "saveMemory", "recallMemory");
    }
}
