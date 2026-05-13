package com.aiaiai.controller.dto;

import java.util.List;

public class ChatResponse {

    private String sessionId;
    private String reply;
    private List<ToolCallDto> toolCalls;

    public ChatResponse() {}

    public ChatResponse(String sessionId, String reply, List<ToolCallDto> toolCalls) {
        this.sessionId = sessionId;
        this.reply = reply;
        this.toolCalls = toolCalls;
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getReply() { return reply; }
    public void setReply(String reply) { this.reply = reply; }
    public List<ToolCallDto> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCallDto> toolCalls) { this.toolCalls = toolCalls; }
}
