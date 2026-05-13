package com.aiaiai.controller.dto;

public class RetrievalSnippet {

    private double score;
    private String confidence;   // HIGH | MEDIUM | LOW
    private String preview;      // 前 120 字文本预览
    private String source;       // 来源（文档名/标题）

    public RetrievalSnippet() {}

    public RetrievalSnippet(double score, String confidence, String preview, String source) {
        this.score = score;
        this.confidence = confidence;
        this.preview = preview;
        this.source = source;
    }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public String getConfidence() { return confidence; }
    public void setConfidence(String confidence) { this.confidence = confidence; }
    public String getPreview() { return preview; }
    public void setPreview(String preview) { this.preview = preview; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
