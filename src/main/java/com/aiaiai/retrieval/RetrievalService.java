package com.aiaiai.retrieval;

import com.aiaiai.controller.dto.RetrievalSnippet;

import java.util.List;

public interface RetrievalService {
    String search(String query);

    String search(String query, int topK);

    String rerankAndTruncate(String resultText, String query, int finalTopK);

    SearchResult searchStructured(String query, int topK);

    SearchResult rerankStructured(SearchResult result, String query, int finalTopK);

    List<RetrievalSnippet> extractSnippets(String resultText);

    String normalizeConfidence(double score);
}
