package com.aiaiai.eval;

import java.util.*;

/**
 * 50-query evaluation set with category labels and ground truth.
 *
 * Ground truth: for each in-scope query, list of paper title substrings
 * that identify the correct source papers. Matching is case-insensitive
 * substring match against chunk metadata.title.
 *
 * Boundary queries (out-of-scope): no ground truth — expected to return
 * no strong matches. Used for Layer 0 routing evaluation.
 */
public class EvalQueries {

    public enum Category {
        METHOD_NAME,       // Exact method/paper name lookup
        CONCEPT,           // Conceptual/comparative question
        VAGUE_REFERENCE,   // Fuzzy reference with missing details
        MIXED_INTENT,      // Multi-tool trigger (retrieve + remember)
        TYPO_VARIANT,      // Misspelling or abbreviation
        BROAD,             // Overly broad/generic question
        SPECIFIC,          // Highly specific detail question
        ORAL_EMOTIONAL,    // Colloquial or emotionally-laden phrasing
        BOUNDARY           // Out-of-domain, should trigger gatekeeping
    }

    public record EvalQuery(
            String id,
            String query,
            Category category,
            String[] groundTruth,  // null for boundary queries
            boolean multiTarget,   // true → Capped Recall@K; false → Hit@K
            String note            // what this query is testing
    ) {}

    public static List<EvalQuery> all() {
        List<EvalQuery> list = new ArrayList<>();

        // ── Method name lookup (4) — single-target ──
        list.add(new EvalQuery("H1", "SnowflakeNet 的核心思想",
                Category.METHOD_NAME,
                new String[]{"SnowflakeNet"}, false,
                "直接方法名 → 对应论文 top-1"));
        list.add(new EvalQuery("H2", "PoinTr 的网络结构",
                Category.METHOD_NAME,
                new String[]{"PoinTr"}, false,
                "直接方法名 → 对应论文 top-1"));
        list.add(new EvalQuery("H3", "FoldingNet 的点云补全方法",
                Category.METHOD_NAME,
                new String[]{"Survey_of_Point_Cloud_Completion", "PCN", "PoinTr"}, false,
                "知识库无FoldingNet论文,仅有baseline引用"));
        list.add(new EvalQuery("H4", "PCN 点云补全",
                Category.METHOD_NAME,
                new String[]{"PCN"}, false,
                "简称匹配 → PCN论文"));

        // ── Concept / comparative (3) — multi-target ──
        list.add(new EvalQuery("M1", "点云补全的损失函数有哪些",
                Category.CONCEPT,
                new String[]{"Survey_of_Point_Cloud_Completion", "PCN", "PoinTr",
                        "SeedFormer", "SnowflakeNet", "ProtoComp"}, true,
                "概念覆盖 → 多篇相关论文"));
        list.add(new EvalQuery("M2", "Transformer 在点云补全中的应用",
                Category.CONCEPT,
                new String[]{"Survey_of_Point_Cloud_Completion", "PoinTr",
                        "SeedFormer", "ShapeFormer", "Point_Transformer", "SnowflakeNet",
                        "ProxyFormer", "AnchorFormer", "PMP", "DiPT", "AdaPoinTr"}, true,
                "跨方法概念 → 穷举KB中所有使用Transformer的补全论文"));
        list.add(new EvalQuery("M3", "coarse-to-fine 策略在点云补全中",
                Category.CONCEPT,
                new String[]{"Survey_of_Point_Cloud_Completion", "PCN", "SeedFormer", "ProtoComp",
                        "SnowflakeNet", "PoinTr", "PMP", "ShapeFormer", "AdaPoinTr",
                        "ProxyFormer", "AnchorFormer"}, true,
                "策略模式 → 经典C2F+基于代理/锚点演进的现代C2F(不含Point_Transformer)"));

        // ── Meta-concept (3) — multi-target ──
        list.add(new EvalQuery("L1", "三维重建的评价指标",
                Category.CONCEPT,
                new String[]{"Survey_of_Point_Cloud_Completion"}, true,
                "元概念 → 主要在Survey中讨论"));
        list.add(new EvalQuery("L2", "点云上采样和补全有什么区别",
                Category.CONCEPT,
                new String[]{"Survey_of_Point_Cloud_Completion", "PCN", "SeedFormer"}, true,
                "概念辨析 → Survey + 同时涉及两者的论文"));
        list.add(new EvalQuery("L3", "自监督学习在点云处理中的应用",
                Category.CONCEPT,
                new String[]{"Survey_of_Point_Cloud_Completion"}, true,
                "窄概念 → Survey中涉及"));

        // ── 模糊指代 (8) — single-target ──
        list.add(new EvalQuery("V1", "之前那篇讲点云的，不是PCN，是另一个什么Net来着？",
                Category.VAGUE_REFERENCE,
                new String[]{"SnowflakeNet"}, false,
                "排除式模糊指代,应定位SnowflakeNet"));
        list.add(new EvalQuery("V2", "上次说的那个用diffusion做补全的论文",
                Category.VAGUE_REFERENCE,
                new String[]{"DiPT", "PCDreamer", "PointDiffuse", "Repurposing", "Simba"}, false,
                "模糊技术路线指代 → diffusion方法论文"));
        list.add(new EvalQuery("V3", "不是PoinTr，是后面改进的那个版本",
                Category.VAGUE_REFERENCE,
                new String[]{"AdaPoinTr"}, false,
                "排除+时间关系指代 → AdaPoinTr"));
        list.add(new EvalQuery("V4", "那个把2D diffusion用到3D补全的方法",
                Category.VAGUE_REFERENCE,
                new String[]{"Repurposing"}, false,
                "技术特征描述 → Repurposing 2D Diffusion"));
        list.add(new EvalQuery("V5", "有个用transformer做补全的，CVPR的",
                Category.VAGUE_REFERENCE,
                new String[]{"CVPR 2021", "ProxyFormer", "AnchorFormer", "ShapeFormer", "PMP"}, false,
                "会议+技术路线 → CVPR transformer论文(CVPR 2021精确定位CVPR版PoinTr,避免ICCV泄漏)"));
        list.add(new EvalQuery("V6", "还有一个什么former，做形状补全的",
                Category.VAGUE_REFERENCE,
                new String[]{"ShapeFormer", "AnchorFormer", "ProxyFormer", "SeedFormer"}, false,
                "后缀模糊 → 多个*Former论文"));
        list.add(new EvalQuery("V7", "不是seed那个，是另一个做稀疏表示的",
                Category.VAGUE_REFERENCE,
                new String[]{"ShapeFormer"}, false,
                "排除+特征描述 → ShapeFormer"));
        list.add(new EvalQuery("V8", "那个anchor开头的补全方法",
                Category.VAGUE_REFERENCE,
                new String[]{"AnchorFormer"}, false,
                "前缀提示 → AnchorFormer"));

        // ── 混合意图 (6) — X1/X3/X5/X6 multi, X2/X4 single ──
        list.add(new EvalQuery("X1", "比较 SnowflakeNet 和 PoinTr 的损失函数，记住我偏好轻量模型",
                Category.MIXED_INTENT,
                new String[]{"SnowflakeNet", "PoinTr", "Survey_of_Point_Cloud_Completion"}, true,
                "检索+记忆 → 应检索两篇论文"));
        list.add(new EvalQuery("X2", "ProtoComp的特征融合怎么做的？顺便记住我们组用CD做主要指标",
                Category.MIXED_INTENT,
                new String[]{"ProtoComp"}, false,
                "检索+记忆 → ProtoComp"));
        list.add(new EvalQuery("X3", "PoinTr和PCN哪个效果更好？存一下我关注推理速度",
                Category.MIXED_INTENT,
                new String[]{"PoinTr", "PCN", "Survey_of_Point_Cloud_Completion"}, true,
                "对比+记忆 → 两篇论文"));
        list.add(new EvalQuery("X4", "SeedFormer的上采样模块怎么设计的，记下来F-score比CD重要",
                Category.MIXED_INTENT,
                new String[]{"SeedFormer"}, false,
                "具体模块+记忆 → SeedFormer"));
        list.add(new EvalQuery("X5", "查一下点云补全用的数据集，顺便记ShapeNet是我们主力",
                Category.MIXED_INTENT,
                new String[]{"Survey_of_Point_Cloud_Completion"}, true,
                "元信息+记忆 → Survey"));
        list.add(new EvalQuery("X6", "diffusion做补全有什么优势？记住我关注推理延迟",
                Category.MIXED_INTENT,
                new String[]{"DiPT", "PCDreamer", "PointDiffuse", "Repurposing", "Simba"}, true,
                "技术路线+记忆 → diffusion论文"));

        // ── 拼写/缩写变异 (6) — single-target ──
        list.add(new EvalQuery("T1", "SNet的SPD模块怎么工作的",
                Category.TYPO_VARIANT,
                new String[]{"SnowflakeNet"}, false,
                "缩写SNet+模块缩写SPD → SnowflakeNet"));
        list.add(new EvalQuery("T2", "pct补全有什么方法",
                Category.BROAD,
                new String[]{"Survey_of_Point_Cloud_Completion"}, false,
                "「有什么方法」为broad信号，pct缩写不主导意图"));
        list.add(new EvalQuery("T3", "PointTr的几何感知transformer",
                Category.TYPO_VARIANT,
                new String[]{"PoinTr"}, false,
                "拼写错误PointTr → PoinTr"));
        list.add(new EvalQuery("T4", "CD损失和EMD损失的区别是什么",
                Category.TYPO_VARIANT,
                new String[]{"Survey_of_Point_Cloud_Completion", "PCN", "PoinTr", "ProtoComp"}, false,
                "缩写CD/EMD → 损失函数讨论"));
        list.add(new EvalQuery("T5", "PCN里的coarse-to-fine是怎么分的",
                Category.SPECIFIC,
                new String[]{"PCN"}, false,
                "PCN是全论文名+细节提问 → SPECIFIC"));
        list.add(new EvalQuery("T6", "SN的skip-transformer是干什么的",
                Category.TYPO_VARIANT,
                new String[]{"SnowflakeNet"}, false,
                "缩写SN → SnowflakeNet"));

        // ── 极度宽泛 (5) — multi-target ──
        list.add(new EvalQuery("B1", "点云领域最新进展",
                Category.BROAD,
                new String[]{"Survey_of_Point_Cloud_Completion"}, true,
                "极宽泛 → 应命中Survey"));
        list.add(new EvalQuery("B2", "最近有什么好的补全方法",
                Category.BROAD,
                new String[]{"Survey_of_Point_Cloud_Completion"}, true,
                "口语化宽泛 → Survey"));
        list.add(new EvalQuery("B3", "3D补全的SOTA",
                Category.BROAD,
                new String[]{"Survey_of_Point_Cloud_Completion"}, true,
                "缩写SOTA → Survey"));
        list.add(new EvalQuery("B4", "点云补全综述",
                Category.BROAD,
                new String[]{"Survey_of_Point_Cloud_Completion"}, true,
                "直接要综述 → Survey top-1"));
        list.add(new EvalQuery("B5", "现在做补全都用什么技术路线",
                Category.BROAD,
                new String[]{"Survey_of_Point_Cloud_Completion"}, true,
                "技术路线全景 → Survey"));

        // ── 极度具体 (5) — single-target ──
        list.add(new EvalQuery("S1", "SeedFormer的PC-Attn模块输入是什么维度",
                Category.SPECIFIC,
                new String[]{"SeedFormer"}, false,
                "模块级细粒度 → SeedFormer具体chunk"));
        list.add(new EvalQuery("S2", "PoinTr的几何感知transformer和普通transformer有什么区别",
                Category.SPECIFIC,
                new String[]{"PoinTr"}, false,
                "架构细节对比 → PoinTr"));
        list.add(new EvalQuery("S3", "PCN用了几个MLP做coarse生成",
                Category.SPECIFIC,
                new String[]{"PCN"}, false,
                "实现细节 → PCN"));
        list.add(new EvalQuery("S4", "SnowflakeNet每个雪花点有多少个邻居",
                Category.SPECIFIC,
                new String[]{"SnowflakeNet"}, false,
                "参数级细节 → SnowflakeNet"));
        list.add(new EvalQuery("S5", "ProtoComp选了多少个prototype",
                Category.SPECIFIC,
                new String[]{"ProtoComp"}, false,
                "超参数细节 → ProtoComp"));

        // ── 口语化/情绪化 (4) — O1/O2 multi, O3/O4 single ──
        list.add(new EvalQuery("O1", "哎点云补全的模型一般都是怎么训练的来着，太难懂了",
                Category.ORAL_EMOTIONAL,
                new String[]{"Survey_of_Point_Cloud_Completion"}, true,
                "情绪化口语 → 至少命中Survey(保底)"));
        list.add(new EvalQuery("O2", "帮我看看那个什么diffusion的补全方法",
                Category.VAGUE_REFERENCE,
                new String[]{"DiPT", "PCDreamer", "PointDiffuse", "Repurposing", "Simba"}, true,
                "模糊指代主导，口语前缀为次要信号"));
        list.add(new EvalQuery("O3", "讲讲PCN吧懒得看论文了",
                Category.ORAL_EMOTIONAL,
                new String[]{"PCN"}, false,
                "情绪化+方法名 → PCN"));
        list.add(new EvalQuery("O4", "这个seed什么的补全效果到底行不行啊",
                Category.ORAL_EMOTIONAL,
                new String[]{"SeedFormer"}, false,
                "口语化+质疑 → SeedFormer"));

        // ── 边界/越界 (8) ──
        list.add(new EvalQuery("E1", "ICP 点云配准算法",
                Category.BOUNDARY, null, false,
                "越界-传统配准 → 应拒答或低分"));
        list.add(new EvalQuery("E2", "3D Gaussian Splatting",
                Category.BOUNDARY, null, false,
                "越界-新视角合成 → 应拒答"));
        list.add(new EvalQuery("E3", "医学影像的点云配准怎么做",
                Category.BOUNDARY, null, false,
                "越界-医疗领域 → 应拒答"));
        list.add(new EvalQuery("E4", "自动驾驶的3D目标检测用什么模型",
                Category.BOUNDARY, null, false,
                "越界-自动驾驶 → 应拒答"));
        list.add(new EvalQuery("E5", "SLAM中的点云地图构建和定位",
                Category.BOUNDARY, null, false,
                "越界-机器人SLAM → 应拒答"));
        list.add(new EvalQuery("E6", "B超图像怎么做三维重建",
                Category.BOUNDARY, null, false,
                "越界-超声医学影像 → 应拒答"));
        list.add(new EvalQuery("E7", "激光雷达点云的去噪和滤波算法",
                Category.BOUNDARY, null, false,
                "越界-LiDAR信号处理 → 应拒答"));
        list.add(new EvalQuery("E8", "Mesh和点云之间怎么互相转换",
                Category.BOUNDARY, null, false,
                "越界-几何处理 → 应拒答"));

        return list;
    }
}
