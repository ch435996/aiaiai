package com.aiaiai.retrieval;

import java.util.List;

public record SearchResult(List<SnippetEntry> snippets, int count, double maxConfidence) {

	public record SnippetEntry(int index, double score, String confidence, String section, String text) {}
}
