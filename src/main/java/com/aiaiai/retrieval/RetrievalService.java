package com.aiaiai.retrieval;

import com.aiaiai.controller.dto.RetrievalSnippet;

import java.util.List;

public interface RetrievalService {
    String search(String query);

    /** 从 search() 返回的文本中解析出结构化检索片段 */
    List<RetrievalSnippet> extractSnippets(String resultText);

    /** 将原始相关度归一化为 HIGH / MEDIUM / LOW */
    String normalizeConfidence(double score);
}
