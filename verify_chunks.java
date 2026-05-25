import com.aiaiai.ingestion.*;
import dev.langchain4j.data.document.*;
import dev.langchain4j.data.segment.TextSegment;
import java.io.*;
import java.util.*;
import java.util.regex.*;

public class verify_chunks {
    public static void main(String[] args) throws Exception {
        File pdf = new File("papers/Repurposing_2D_Diffusion_3D_Shape_Completion.pdf");
        if (!pdf.exists()) { System.out.println("PDF not found"); return; }

        PdfExtractionService svc = new PdfExtractionService();
        String text = svc.extract(pdf);
        System.out.println("=== 提取文本总长度: " + text.length() + " chars ===\n");

        // 段落统计
        String[] paragraphs = text.split("\n\n");
        int[] paraSizes = new int[paragraphs.length];
        for (int i = 0; i < paragraphs.length; i++) paraSizes[i] = paragraphs[i].trim().length();
        Arrays.sort(paraSizes);
        System.out.println("段落数: " + paragraphs.length);
        System.out.println("段落大小: min=" + paraSizes[0] + " p50=" + paraSizes[paraSizes.length/2]
            + " p90=" + paraSizes[(int)(paraSizes.length*0.9)] + " max=" + paraSizes[paraSizes.length-1]);

        // SectionAwareSplitter 切分
        SectionAwareSplitter splitter = new SectionAwareSplitter(1000, 80);
        Metadata docMeta = new Metadata();
        docMeta.put("title", pdf.getName());
        List<TextSegment> chunks = splitter.split(Document.from(text, docMeta));

        System.out.println("\n=== 分块结果: " + chunks.size() + " 个 chunk ===\n");

        // token 估算 (英文 ~4 chars/token, 中文 ~1.5 chars/token)
        int totalChars = 0;
        List<Integer> charSizes = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            TextSegment seg = chunks.get(i);
            String chunkText = seg.text();
            int chars = chunkText.length();
            totalChars += chars;
            charSizes.add(chars);

            String section = seg.metadata().getString("section");
            if (section == null || section.isEmpty()) section = "(无章节)";

            // 估算 token: 统计中文字符 vs 英文
            int cnChars = 0;
            for (char c : chunkText.toCharArray()) {
                if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) cnChars++;
            }
            int enChars = chars - cnChars;
            int estTokens = (int)(cnChars / 1.5 + enChars / 4.0);

            System.out.printf("--- Chunk %d/%d | %d chars | ~%d tokens | %s ---\n",
                i+1, chunks.size(), chars, estTokens, section);
            // 显示前 150 字符
            String preview = chunkText.length() > 150 ? chunkText.substring(0, 150) + "…" : chunkText;
            System.out.println(preview);
            System.out.println();
        }

        Collections.sort(charSizes);
        System.out.println("=== 统计 ===");
        System.out.println("chunk 字符数: min=" + charSizes.get(0) + " p25=" + charSizes.get(charSizes.size()/4)
            + " p50=" + charSizes.get(charSizes.size()/2) + " p75=" + charSizes.get(charSizes.size()*3/4)
            + " max=" + charSizes.get(charSizes.size()-1));
        System.out.println("总字符: " + totalChars + " (原文 " + text.length() + ")");
        System.out.println("平均每 chunk: " + (totalChars/chunks.size()) + " chars");

        svc.shutdown();
    }
}
