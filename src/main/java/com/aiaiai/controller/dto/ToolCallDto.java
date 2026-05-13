package com.aiaiai.controller.dto;

import java.util.List;

public class ToolCallDto {

    private String toolName;         // "searchKnowledge" | "saveMemory" | "recallMemory"
    private String status;           // "request" | "result"

    // ── 兼容旧字段 ──
    private String summary;          // 保留兼容：简短的 action 描述

    // ── request 阶段 ──
    private String inputPreview;     // LLM 实际 query 前 80 字

    // ── result 阶段 ──
    private String resultSummary;    // "命中 3 条，最高置信度 HIGH"
    private int resultCount;         // 3
    private List<RetrievalSnippet> snippets;  // 前 2 条检索片段

    public ToolCallDto() {}

    public ToolCallDto(String toolName, String status, String summary) {
        this.toolName = toolName;
        this.status = status;
        this.summary = summary;
    }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getInputPreview() { return inputPreview; }
    public void setInputPreview(String inputPreview) { this.inputPreview = inputPreview; }
    public String getResultSummary() { return resultSummary; }
    public void setResultSummary(String resultSummary) { this.resultSummary = resultSummary; }
    public int getResultCount() { return resultCount; }
    public void setResultCount(int resultCount) { this.resultCount = resultCount; }
    public List<RetrievalSnippet> getSnippets() { return snippets; }
    public void setSnippets(List<RetrievalSnippet> snippets) { this.snippets = snippets; }
}
