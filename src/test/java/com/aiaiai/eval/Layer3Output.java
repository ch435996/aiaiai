package com.aiaiai.eval;

import java.util.List;

/**
 * Output formatting for Layer 3 eval results.
 * Extracted to keep Layer3AntihallucinationEval under 400 lines.
 */
final class Layer3Output {

    static final String SYSTEM_PROMPT = """
            你是三维重建/点云补全领域的智能科研助手。

            工具使用指南：
            - searchKnowledge：仅在用户询问三维重建/点云补全领域的论文事实、方法细节、实验结果、
              数据集或指标时调用。用户问题明显超出此范围时，严禁调用 searchKnowledge，直接简要回答并提示领域边界。
            - 闲聊、寒暄或你能自信回答的问题，直接回复即可

            核心回答规则（必须严格遵守）：

            一、来源限定规则
            你只能使用[知识库检索结果]中提供的证据来回答。严禁使用训练时学到的任何论文细节、
            数值、方法描述——即使你确信自己知道，如果检索结果中没有，就不能当作事实来陈述。

            二、引用规则
            每个事实性陈述之后，必须标注其所依据的检索结果编号。格式：
            > 来源：[引用来源 #N]
            其中 N 对应检索结果中各 chunk 的编号。多个事实必须逐个标注引用，不得在末尾集中标注。
            即使只有一个事实，也必须标注引用来源。

            三、不确定规则（以下任一情况触发，禁止编造答案）
            - 检索结果中不包含问题的直接答案
            - 检索结果提到了相关论文但没有所需的具体细节（如具体数值、参数、配置）
            - 检索结果为空或全部为 LOW 置信度
            触发时使用此模板：
            "根据目前知识库中的资料，暂时无法回答这个问题。[说明缺少的具体信息]。"
            使用此模板时，你的回答必须以句号结束，不得追加任何其他内容。
            禁止追加推测、补充说明、"可能""通常""估计"等任何措辞。

            特别规则——数值查询：
            当用户询问具体数值（维度、F-score、batch size、GPU 数量、学习率、epoch 数等），
            你必须逐一检查每个检索结果中是否明确写有该数值。如果所有检索结果都只有
            方法描述而没有给出精确数值，即使它们讨论了相关方法或模块，也必须使用不确定模板。
            严禁基于"常见配置"或"典型取值"等推理进行推测。

            四、领域边界规则
            用户问题超出三维重建/点云补全领域时，简短回答并严禁调用 searchKnowledge：
            "这是[具体领域]的内容，不在本课题组的论文知识库范围内。建议查阅该领域专业资料。"

            五、证据不足模板
            当检索结果不足以完整回答时：
            "根据目前知识库中的资料，暂时无法完整回答这个问题。以下是我基于现有信息能提供的部分——"
            之后仅回答有检索依据的部分，且必须标注引用来源。

            六、置信度标签
            HIGH=高度相关可放心引用，MEDIUM=中度相关可参考，LOW=弱相关仅供参考不可作为核心论据。
            """;

    static final String JUDGE_PROMPT = """
            你是事实核查器。给定一个主张（Claim）和几条检索到的证据（Evidence），判断该主张是否直接由证据支持。

            规则：
            - 如果证据明确包含主张中的所有关键事实（数字、方法名、机制描述），回答 YES
            - 如果证据不包含或仅部分包含，或者需要推理跳跃才能得到主张，回答 NO
            - 只回答 YES 或 NO，不要任何解释""";

    private Layer3Output() {}

    static void printDetailTable(List<Layer3AntihallucinationEval.Layer3Result> results) {
        System.out.printf("%-5s %-40s %-16s %6s %6s %6s %6s %6s %6s%n",
                "ID", "QUERY", "CATEGORY", "FAITH", "HEP", "CR", "CP", "UNC", "GRND");
        System.out.println("-".repeat(105));
        for (var r : results) {
            System.out.printf("%-5s %-40s %-16s %6s %6s %6s %6s %6s %6s%n",
                    r.id(), trunc(r.query(), 40), r.categoryLabel(),
                    fmt(r.faithfulnessLower()), fmt(r.hep()),
                    fmt(r.citationRecall()), fmt(r.citationPrecision()),
                    r.correctUncertain() ? "OK" : "FAIL",
                    fmt(r.groundedness()));
        }
        for (var r : results) {
            if (!r.warnings().isEmpty()) {
                System.out.printf("  ⚠ %s: %s%n", r.id(), r.warnings());
            }
        }
    }

    static void printCategoryBreakdown(List<Layer3AntihallucinationEval.Layer3Result> results) {
        System.out.println("\n=== Per-Category Breakdown ===");
        System.out.printf("%-18s %5s %8s %8s %8s %8s %8s%n",
                "CATEGORY", "N", "FAITH", "HEP", "CR", "CP", "GRND");
        System.out.println("-".repeat(70));

        for (var cat : Layer3EvalQueries.Layer3Category.values()) {
            double f = 0, h = 0, cr = 0, cp = 0, g = 0;
            int n = 0;
            for (var r : results) {
                if (r.categoryLabel().equals(cat.name()) && r.numClaims() > 0) {
                    f += r.faithfulnessLower(); h += r.hep();
                    cr += r.citationRecall(); cp += r.citationPrecision();
                    g += r.groundedness(); n++;
                }
            }
            if (n == 0) continue;
            System.out.printf("%-18s %5d %8s %8s %8s %8s %8s%n",
                    "[" + cat + "]", n, fmt(f / n), fmt(h / n),
                    fmt(cr / n), fmt(cp / n), fmt(g / n));
        }

        double dba = 0; int bn = 0;
        for (var r : results) {
            if (r.categoryLabel().equals("BOUNDARY")) { bn++; if (r.correctBoundary()) dba++; }
        }
        if (bn > 0) {
            System.out.printf("%-18s %5d %8s %8s %8s %8s %8s%n",
                    "[BOUNDARY]", bn, "—", "—", "—", "—",
                    bn > 0 ? fmt(dba / bn) : "—");
        }
    }

    static void printAggregate(List<Layer3AntihallucinationEval.Layer3Result> results,
                                int boundaryTotal, int boundaryCorrect) {
        double fSum = 0, hSum = 0, crSum = 0, cpSum = 0, gSum = 0;
        int n = 0, uncCorrect = 0, uncTotal = 0;
        for (var r : results) {
            if (r.categoryLabel().equals("BOUNDARY")) continue;
            if (r.numClaims() > 0) {
                fSum += r.faithfulnessLower(); hSum += r.hep();
                crSum += r.citationRecall(); cpSum += r.citationPrecision();
                gSum += r.groundedness(); n++;
            }
        }
        for (var r : results) {
            if (r.categoryLabel().equals("TRAP_UNCERTAINTY")) {
                uncTotal++;
                if (r.correctUncertain()) uncCorrect++;
            }
        }

        double faith = n > 0 ? fSum / n : 0;
        double hep = n > 0 ? hSum / n : 0;
        double cr = n > 0 ? crSum / n : 0;
        double cp = n > 0 ? cpSum / n : 0;
        double citationF1 = cr + cp > 0 ? 2 * cr * cp / (cr + cp) : 0;
        double uc = uncTotal > 0 ? (double) uncCorrect / uncTotal : 1.0;
        double dba = boundaryTotal > 0 ? (double) boundaryCorrect / boundaryTotal : 1.0;
        double grnd = n > 0 ? gSum / n : 0;

        System.out.println("\n=== Aggregate Metrics ===");
        System.out.printf("%-28s %8s %10s %8s%n", "METRIC", "VALUE", "THRESHOLD", "VERDICT");
        System.out.println("-".repeat(58));
        printMetricRow("Faithfulness", faith, 0.90, 0.75);
        printMetricRow("Hard Entity Precision", hep, 0.95, 0.85);
        printMetricRow("Citation Recall", cr, 0.80, 0.60);
        printMetricRow("Citation Precision", cp, 0.90, 0.70);
        printMetricRow("Citation F1", citationF1, 0.80, 0.60);
        printMetricRow("Uncertainty Calib.", uc, 0.85, 0.65);
        printMetricRow("Domain Boundary Adh.", dba, 0.90, 0.75);
        printMetricRow("Answer Groundedness", grnd, 0.75, 0.65);

        System.out.printf("%nComposite Score (weighted): ");
        double composite = faith * 0.25 + hep * 0.20 + citationF1 * 0.15
                + uc * 0.15 + dba * 0.15 + grnd * 0.10;
        System.out.printf("%.3f%n", composite);
        if (composite >= 0.85) System.out.println("PASS — production-ready generation");
        else if (composite >= 0.70)
            System.out.println("WARN — targeted prompt/retrieval fixes needed");
        else System.out.println("FAIL — systemic hallucination problem");
    }

    static void printCost(int genCalls, int judgeCalls, int totalClaimsJudged) {
        System.out.println("\n=== Cost Report ===");
        System.out.printf("Generation API calls: %d%n", genCalls);
        System.out.printf("LLM Judge calls: %d (%d claims)%n", judgeCalls, totalClaimsJudged);
        double cost = genCalls * 0.0005 + judgeCalls * 0.0001;
        System.out.printf("Estimated cost: ~$%.3f%n", cost);
    }

    private static void printMetricRow(String name, double value, double pass, double warn) {
        String verdict = value >= pass ? "PASS" : value >= warn ? "WARN" : "FAIL";
        System.out.printf("%-28s %8s %10s %8s%n", name, fmt(value), "≥" + fmt(pass), verdict);
    }

    static String trunc(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    static String fmt(double v) { return String.format("%.3f", v); }
}
