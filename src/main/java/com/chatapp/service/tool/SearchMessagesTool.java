package com.chatapp.service.tool;

import com.chatapp.entity.Message;
import com.chatapp.repository.MessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SearchMessagesTool implements Tool {
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "search_messages";
    }

    @Override
    public String description() {
        return "Search text messages in the current room by keyword.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("roomId").put("type", "integer").put("description", "Ignored; server forces current room.");
        properties.putObject("keyword").put("type", "string").put("minLength", 1);
        properties.putObject("maxResults").put("type", "integer").put("minimum", 1).put("maximum", 50);
        schema.putArray("required").add("keyword");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params, ToolContext context) {
        String keyword = params.path("keyword").asText("").trim();
        if (keyword.isBlank()) {
            throw new ToolExecutionException("invalid_params", "keyword is required");
        }
        int maxResults = Math.max(1, Math.min(params.path("maxResults").asInt(20), 50));

        ObjectNode root = objectMapper.createObjectNode();
        root.put("roomId", context.roomId());
        root.put("keyword", keyword);
        ArrayNode matches = root.putArray("matches");
        messageRepository.searchInChatRoom(context.roomId(), keyword, PageRequest.of(0, maxResults))
                .getContent()
                .forEach(message -> matches.add(formatMessage(message)));
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
