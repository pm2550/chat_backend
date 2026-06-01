package com.chatapp.service.tool;

import com.fasterxml.jackson.databind.JsonNode;

public interface Tool {
    String name();

    String description();

    JsonNode parametersSchema();

    default ExecutionContext executionContext() {
        return ExecutionContext.SERVER;
    }

    JsonNode execute(JsonNode params, ToolContext context);

    enum ExecutionContext {
        SERVER,
        CLIENT
    }
}
