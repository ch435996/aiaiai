package com.aiaiai.controller.dto;

public class IngestRequest {

    private String content;
    private String title;
    private String source;

    public IngestRequest() {}

    public IngestRequest(String content, String title, String source) {
        this.content = content;
        this.title = title;
        this.source = source;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
