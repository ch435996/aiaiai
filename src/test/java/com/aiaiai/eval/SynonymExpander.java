package com.aiaiai.eval;

import java.util.*;

/**
 * Domain synonym expansion for citation precision verification.
 * Without this, CP is systematically underestimated because answers
 * use Chinese/abbreviations while chunks use English/full names.
 *
 * Three data sources:
 *   1. Abbreviation ↔ full name (mirrors application.yml typoCorrections)
 *   2. CN ↔ EN domain high-frequency pairs (~30 pairs)
 *   3. Method name ↔ paper title aliases
 */
public class SynonymExpander {

    private final Map<String, Set<String>> synMap = new LinkedHashMap<>();

    public SynonymExpander() {
        // 1. Abbreviation ↔ full name
        add("PointTr", "PoinTr");
        add("SNet", "SnowflakeNet");
        add("pct", "point cloud");
        add("CD", "Chamfer Distance");
        add("EMD", "Earth Mover's Distance");
        add("SPD", "Snowflake Point Deconvolution");
        add("SN", "SnowflakeNet");

        // 2. Domain CN ↔ EN pairs
        add("点云补全", "point cloud completion");
        add("点云", "point cloud");
        add("上采样", "upsampling", "upsample");
        add("下采样", "downsampling", "downsample");
        add("编码器", "encoder");
        add("解码器", "decoder");
        add("损失函数", "loss function", "loss");
        add("损失", "loss");
        add("特征融合", "feature fusion", "fusion");
        add("融合", "fusion");
        add("特征", "feature");
        add("注意力", "attention");
        add("去噪", "denoising", "denoise");
        add("扩散", "diffusion");
        add("补全", "completion");
        add("重建", "reconstruction");
        add("模块", "module", "block");
        add("网络", "network");
        add("架构", "architecture");
        add("训练", "training", "train");
        add("推理", "inference", "infer");
        add("生成", "generation", "generate");
        add("分割", "segmentation");
        add("分类", "classification", "classify");
        add("锚点", "anchor");
        add("代理", "proxy");
        add("原型", "prototype");
        add("粗粒度", "coarse");
        add("细粒度", "fine");
        add("几何", "geometry", "geometric");
        add("阶段", "stage", "phase");
        add("策略", "strategy");
        add("指标", "metric", "metrics");

        // 3. Method name ↔ paper title aliases
        add("PCN", "Point Completion Network");
        add("PoinTr", "Point Transformer", "geometry-aware transformer");
        add("SeedFormer", "Patch-Conditioned Attention", "PC-Attn");
        add("SnowflakeNet", "Snowflake Point Deconvolution", "SPD");
        add("ShapeFormer", "ShapeFormer", "Transformer for Shape Completion");
        add("ProxyFormer", "ProxyFormer");
        add("AnchorFormer", "AnchorFormer");
        add("ProtoComp", "ProtoComp", "Prototype Completion");
        add("DiPT", "DiPT", "Diffusion Point Transformer");
        add("PMP", "PMP", "Point Patch Masking");
        add("AdaPoinTr", "Adaptive PoinTr");

        add("Chamfer Distance", "CD");
        add("Earth Mover's Distance", "EMD");
        add("F-score", "F1 score", "F1");
        add("coarse-to-fine", "C2F", "coarse to fine");
        add("transformer", "Transformer");
        add("diffusion", "Diffusion", "diffusion model");
    }

    private void add(String... terms) {
        Set<String> set = new LinkedHashSet<>();
        for (String t : terms) set.add(t.strip());
        for (String t : terms) {
            String key = t.strip().toLowerCase();
            Set<String> existing = synMap.getOrDefault(key, new LinkedHashSet<>());
            existing.addAll(set);
            synMap.put(key, existing);
        }
    }

    /**
     * Returns the term plus all known synonyms, each in lower/upper/capitalized variants.
     */
    public Set<String> expand(String term) {
        Set<String> result = new LinkedHashSet<>();
        result.add(term);
        String lower = term.toLowerCase();
        Set<String> synonyms = synMap.get(lower);
        if (synonyms != null) {
            for (String syn : synonyms) {
                result.add(syn);
                result.add(syn.toLowerCase());
                result.add(syn.toUpperCase());
                if (syn.length() > 1) {
                    result.add(syn.substring(0, 1).toUpperCase() + syn.substring(1));
                }
            }
        }
        return result;
    }
}
