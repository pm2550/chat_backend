package com.chatapp.service.tool;

import com.chatapp.entity.Message;
import com.chatapp.repository.MessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ReadRecentMessagesTool implements Tool {
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "read_recent_messages";
    }

    @Override
    public String description() {
        return "Read more recent messages from the current room in chronological order.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("roomId").put("type", "integer").put("description", "Ignored; server forces current room.");
        properties.putObject("n").put("type", "integer").put("minimum", 1).put("maximum", 100)
                .put("description", "Number of recent messages to read.");
        schema.putArray("required").add("n");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params, ToolContext context) {
        int n = Math.max(1, Math.min(params.path("n").asInt(20), 100));
        List<Message> latestDesc = new ArrayList<>(messageRepository.findRecentMessages(context.roomId(), n));
        Collections.reverse(latestDesc);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("roomId", context.roomId());
        ArrayNode messages = root.putArray("messages");
        for (Message message : latestDesc) {
            messages.add(formatMessage(message));
        }
        return root;
    }

    private ObjectNode formatMessage(Message message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", message.getId());
        node.put("sender", displayName(message));
        node.put("messageType", message.getMessageType() != null ? message.getMessageType().name() : "TEXT");
        node.put("content", message.getContent() != null ? message.getContent() : "");
        if (message.getCreatedAt() != null) {
            node.put("timestamp", message.getCreatedAt().toString());
        }
        return node;
    }

    private String displayName(Message message) {
        if (message.getBotConfig() != null && message.getBotConfig().getBotName() != null) {
            return message.getBotConfig().getBotName();
        }
        if (message.getAnonymousIdentity() != null && message.getAnonymousIdentity().getAnonymousName() != null) {
            return message.getAnonymousIdentity().getAnonymousName();
        }
        if (message.getSender() == null) {
            return "Unknown";
        }
        if (message.getSender().getDisplayName() != null && !message.getSender().getDisplayName().isBlank()) {
            return message.getSender().getDisplayName();
        }
        return message.getSender().getUsername();
    }
}
