package com.aiaiai.eval;

import java.util.*;

/**
 * Layer 3 generation anti-hallucination evaluation queries.
 *
 * Each query includes expected/forbidden entities, uncertainty expectation,
 * and citation expectation — the ground truth for generation quality,
 * NOT retrieval quality (that's EvalQueries.java).
 */
public class Layer3EvalQueries {

    public enum Layer3Category {
        FACTUAL_DETAIL,    // Answer IS in KB — tests faithfulness
        TRAP_UNCERTAINTY,  // Answer NOT in KB — tests calibration
        CITATION_CHECK,    // Multi-source — tests citation precision/recall
        COMPARISON         // Cross-paper — tests multi-chunk grounding
    }

    public record Layer3Query(
            String id,
            String query,
            Layer3Category category,
            Set<String> expectedEntities,
            Set<String> forbiddenPhrases,
            boolean expectUncertain,
            boolean expectCitations,
            String note
    ) {}

    public static List<Layer3Query> all() {
        List<Layer3Query> list = new ArrayList<>();

        // ── FACTUAL_DETAIL: 12 queries ──
        // Answers knowable from the 20+-paper knowledge base

        list.add(new Layer3Query("F01", "SnowflakeNet的SPD模块是怎么工作的？",
                Layer3Category.FACTUAL_DETAIL,
                Set.of("SPD", "SnowflakeNet", "反卷积", "deconvolution", "上采样"),
                Set.of(),
                false, true,
                "SPD机制 — answer grounded in SnowflakeNet chunks"));

        list.add(new Layer3Query("F02", "PoinTr是用哪种transformer架构的？",
                Layer3Category.FACTUAL_DETAIL,
                Set.of("PoinTr", "transformer", "proxy"),
                Set.of(),
                false, true,
                "PoinTr架构 — geometry-aware transformer描述"));

        list.add(new Layer3Query("F03", "PCN的coarse-to-fine分为哪几个阶段？",
                Layer3Category.FACTUAL_DETAIL,
                Set.of("PCN", "coarse", "fine"),
                Set.of(),
                false, true,
                "PCN阶段划分 — 应引用PCN chunk"));

        list.add(new Layer3Query("F04", "SeedFormer在ShapeNet数据集上的F-score是多少？",
                Layer3Category.FACTUAL_DETAIL,
                Set.of("SeedFormer", "ShapeNet"),
                Set.of(),
                false, true,
                "数值事实 — 答案需要cite实验结果chunk"));

        list.add(new Layer3Query("F05", "ProtoComp的特征融合用了什么机制？",
                Layer3Category.FACTUAL_DETAIL,
                Set.of("ProtoComp", "prototype", "特征"),
                Set.of(),
                false, true,
                "ProtoComp融合机制 — 必须来自ProtoComp chunk"));

        list.add(new Layer3Query("F06", "AnchorFormer如何处理点云锚点的？",
                Layer3Category.FACTUAL_DETAIL,
                Set.of("AnchorFormer", "anchor"),
                Set.of(),
                false, true,
                "AnchorFormer机制"));

        list.add(new Layer3Query("F07", "SeedFormer的PC-Attn输入维度具体是多少？",
                Layer3Category.FACTUAL_DETAIL,
                Set.of("SeedFormer", "attn", "attention"),
                Set.of(),
                false, true,
                "维度数值 — 硬实体关键测试"));

        list.add(new Layer3Query("F08", "ShapeFormer用什么损失函数？",
                Layer3Category.FACTUAL_DETAIL,
                Set.of("ShapeFormer", "损失", "loss"),
                Set.of(),
                false, true,
                "损失函数 — 必须来自ShapeFormer chunk"));

        list.add(new Layer3Query("F09", "Repurposing 2D Diffusion如何把2D diffusion用到3D补全的？",
                Layer3Category.FACTUAL_DETAIL,
                Set.of("Repurposing", "diffusion", "2D", "3D"),
                Set.of(),
                false, true,
                "Repurposing方法 — cross-modality transfer"));

        list.add(new Layer3Query("F10", "DiPT用什么去噪策略？",
                Layer3Category.FACTUAL_DETAIL,
                Set.of("DiPT", "去噪", "denoising"),
                Set.of(),
                false, true,
                "DiPT去噪 — 去噪策略细节"));

        list.add(new Layer3Query("F11", "PMP在点云补全中的核心创新是什么？",
                Layer3Category.FACTUAL_DETAIL,
                Set.of("PMP"),
                Set.of(),
                false, true,
                "PMP创新 — answer grounded in PMP chunk"));

        list.add(new Layer3Query("F12", "PoinTr和普通transformer在点云处理上有什么关键差异？",
                Layer3Category.FACTUAL_DETAIL,
                Set.of("PoinTr", "transformer", "proxy", "几何"),
                Set.of(),
                false, true,
                "PoinTr独特性 — 与generic transformer对比"));

        // ── TRAP_UNCERTAINTY: 5 queries ──
        // Questions whose EXACT answers are NOT in the knowledge base

        list.add(new Layer3Query("U01", "PCN最初是用SGD还是Adam训练的？",
                Layer3Category.TRAP_UNCERTAINTY,
                Set.of("PCN"),
                Set.of("SGD", "Adam"),
                true, false,
                "陷阱-优化器: PCN chunk大概率不包含训练优化器细节"));

        list.add(new Layer3Query("U02", "SnowflakeNet论文用了几个GPU训练？",
                Layer3Category.TRAP_UNCERTAINTY,
                Set.of("SnowflakeNet"),
                Set.of("GPU", "A100", "V100", "3090", "4090", "个", "块"),
                true, false,
                "陷阱-GPU: 硬件配置不在论文body中"));

        list.add(new Layer3Query("U03", "ProtoComp的作者用的代码是PyTorch还是TensorFlow？",
                Layer3Category.TRAP_UNCERTAINTY,
                Set.of("ProtoComp"),
                Set.of("PyTorch", "TensorFlow", "JAX"),
                true, false,
                "陷阱-框架: 代码框架信息不在chunk中"));

        list.add(new Layer3Query("U04", "赵敏课题组做点云补全用的batch size是多大？",
                Layer3Category.TRAP_UNCERTAINTY,
                Set.of(),
                Set.of("32", "64", "128", "256"),
                true, false,
                "陷阱-课题组: 不在已发表论文中"));

        list.add(new Layer3Query("U05", "ShapeFormer和SeedFormer哪个推理速度更快？",
                Layer3Category.TRAP_UNCERTAINTY,
                Set.of("ShapeFormer", "SeedFormer"),
                Set.of("更快", "faster"),
                true, true,
                "陷阱-速度对比: 两篇论文不包含对方信息"));

        // ── CITATION_CHECK: 5 queries ──
        // Designed to produce multi-source answers requiring multiple citations

        list.add(new Layer3Query("C01", "比较SnowflakeNet和PoinTr的point splitting机制有什么异同",
                Layer3Category.CITATION_CHECK,
                Set.of("SnowflakeNet", "PoinTr", "SPD", "proxy"),
                Set.of(),
                false, true,
                "多源对比: 应同时引用SnowflakeNet和PoinTr chunk"));

        list.add(new Layer3Query("C02", "点云补全中基于transformer的方法有哪些？各自核心创新是什么？",
                Layer3Category.CITATION_CHECK,
                Set.of("transformer", "PoinTr", "SeedFormer"),
                Set.of(),
                false, true,
                "广度引用: 应覆盖多篇transformer论文"));

        list.add(new Layer3Query("C03", "coarse-to-fine策略在不同补全方法中各自如何实现？",
                Layer3Category.CITATION_CHECK,
                Set.of("coarse-to-fine", "PCN", "SeedFormer", "SnowflakeNet"),
                Set.of(),
                false, true,
                "跨方法引用: 应引用不同C2F实现"));

        list.add(new Layer3Query("C04", "PoinTr的point proxy和ProxyFormer的anchor机制有什么区别？",
                Layer3Category.CITATION_CHECK,
                Set.of("PoinTr", "ProxyFormer", "proxy", "anchor"),
                Set.of(),
                false, true,
                "双源精确: 两篇论文对比"));

        list.add(new Layer3Query("C05", "SeedFormer和AnchorFormer在特征聚合上各有什么特点？",
                Layer3Category.CITATION_CHECK,
                Set.of("SeedFormer", "AnchorFormer", "特征", "聚合"),
                Set.of(),
                false, true,
                "双源特征: 两篇论文的特征聚合对比"));

        // ── COMPARISON: 5 queries ──
        // Cross-paper comparison, tests multi-chunk grounding

        list.add(new Layer3Query("P01", "Chamfer Distance和Earth Mover's Distance在点云补全中各有什么优劣？",
                Layer3Category.COMPARISON,
                Set.of("Chamfer Distance", "Earth Mover's Distance", "CD", "EMD"),
                Set.of(),
                false, true,
                "损失函数对比"));

        list.add(new Layer3Query("P02", "基于diffusion的补全方法和基于transformer的补全方法主要差异是什么？",
                Layer3Category.COMPARISON,
                Set.of("diffusion", "transformer"),
                Set.of(),
                false, true,
                "技术路线对比"));

        list.add(new Layer3Query("P03", "PCN、PoinTr、SeedFormer三代方法的演进逻辑是什么？",
                Layer3Category.COMPARISON,
                Set.of("PCN", "PoinTr", "SeedFormer"),
                Set.of(),
                false, true,
                "三代演进"));

        list.add(new Layer3Query("P04", "点云补全中voxel-based方法和point-based方法各有什么优缺点？",
                Layer3Category.COMPARISON,
                Set.of("voxel", "point", "点云", "补全"),
                Set.of(),
                false, true,
                "表示形式对比"));

        list.add(new Layer3Query("P05", "单阶段补全和coarse-to-fine两阶段补全哪种效果更好？",
                Layer3Category.COMPARISON,
                Set.of("coarse-to-fine", "single-stage", "阶段"),
                Set.of(),
                false, true,
                "阶段策略对比"));

        return list;
    }
}
