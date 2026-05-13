package com.aiaiai.orchestrator;

import com.aiaiai.controller.dto.ToolCallDto;

import java.util.List;

public class ProcessResult {

    private final String reply;
    private final List<ToolCallDto> toolCalls;

    public ProcessResult(String reply, List<ToolCallDto> toolCalls) {
        this.reply = reply;
        this.toolCalls = toolCalls;
    }

    public String getReply() { return reply; }
    public List<ToolCallDto> getToolCalls() { return toolCalls; }
}
