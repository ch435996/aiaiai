package com.aiaiai.memory;

import java.util.List;

public record MemoryRecallResult(List<Entry> memories, int count) {

	public record Entry(int index, double score, String text) {}
}
