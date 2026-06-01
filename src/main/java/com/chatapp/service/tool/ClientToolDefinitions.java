package com.chatapp.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClientToolDefinitions {
    @Bean
    Tool getLocalRoomSettingsClientTool(ObjectMapper objectMapper) {
        return clientTool(
                objectMapper,
                "get_local_room_settings",
                "Read local room notification preferences from the user's active client.",
                objectSchema(objectMapper));
    }

    @Bean
    Tool getOpenChatPanelsClientTool(ObjectMapper objectMapper) {
        return clientTool(
                objectMapper,
                "get_open_chat_panels",
                "Read which PM chat panels are currently open in the user's active client.",
                objectSchema(objectMapper));
    }

    @Bean
    Tool getRecentAttachmentsClientTool(ObjectMapper objectMapper) {
        ObjectNode schema = objectSchema(objectMapper);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("roomId").put("type", "integer");
        properties.putObject("n").put("type", "integer").put("maximum", 20);
        return clientTool(
                objectMapper,
                "get_recent_attachments",
                "Read recent locally cached attachment metadata for the current room.",
                schema);
    }

    @Bean
    Tool promptUserConfirmationClientTool(ObjectMapper objectMapper) {
        ObjectNode schema = objectSchema(objectMapper);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("question").put("type", "string");
        properties.putObject("yes_label").put("type", "string");
        properties.putObject("no_label").put("type", "string");
        ArrayNode required = schema.putArray("required");
        required.add("question");
        return clientTool(
                objectMapper,
                "prompt_user_confirmation",
                "Ask the user for explicit yes/no/dismissed confirmation in the active client.",
                schema);
    }

    @Bean
    Tool readClipboardClientTool(ObjectMapper objectMapper) {
        return clientTool(
                objectMapper,
                "read_clipboard",
                "Read text currently available in the user's local clipboard.",
                objectSchema(objectMapper));
    }

    private Tool clientTool(ObjectMapper objectMapper, String name, String description, JsonNode schema) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public JsonNode parametersSchema() {
                return schema;
            }

            @Override
            public ExecutionContext executionContext() {
                return ExecutionContext.CLIENT;
            }

            @Override
            public JsonNode execute(JsonNode params, ToolContext context) {
                throw new ToolExecutionException(
                        "client_tool_requires_dispatch",
                        "Client tool " + name + " must be executed through AgentToolDispatcher");
            }
        };
    }

    private static ObjectNode objectSchema(ObjectMapper objectMapper) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        return schema;
    }
}
