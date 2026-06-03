package com.chatapp.service.tool;

import com.chatapp.entity.MemoryEntry;
import com.chatapp.service.MemoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * recall_memory (Phase 5a / F2): searches the room's shared memory library for facts saved
 * earlier (by this bot or others). Keyword/substring match in v1; a blank query returns the
 * most relevant pinned/recent entries. Only ROOM-visible entries are returned.
 */
@Component
@Slf4j
public class RecallMemoryTool implements Tool {

    private final ObjectMapper objectMapper;
    private final MemoryService memoryService;

    public RecallMemoryTool(ObjectMapper objectMapper, MemoryService memoryService) {
        this.objectMapper = objectMapper;
        this.memoryService = memoryService;
    }

    @Override
    public String name() {
        return "recall_memory";
    }

    @Override
    public String description() {
        return "Search this room's shared memory for facts saved earlier. Provide a query to "
                + "match titles/content/keywords, or leave it blank for the most relevant entries.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("query").put("type", "string")
                .put("description", "keywords to search for; blank returns pinned/recent entries");
        properties.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", 20);
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params, ToolContext context) {
        if (context.roomId() == null) {
            return error("no_room", "memory can only be recalled within a room");
        }
        String query = params.path("query").asText("");
        int limit = Math.max(1, Math.min(params.path("limit").asInt(5), 20));
        // userId = null -> ROOM-visible only; a bot never surfaces a user's private memories.
        List<MemoryEntry> entries = memoryService.recall(context.roomId(), null, query, limit);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("query", query);
        ArrayNode results = root.putArray("results");
        for (MemoryEntry entry : entries) {
            ObjectNode item = results.addObject();
            item.put("id", entry.getId());
            item.put("title", entry.getTitle());
            item.put("content", entry.getContent());
            if (entry.getKeywords() != null) {
                item.put("keywords", entry.getKeywords());
            }
            item.put("source", entry.getSourceType().name());
            item.put("pinned", Boolean.TRUE.equals(entry.getPinned()));
            if (entry.getUpdatedAt() != null) {
                item.put("updated_at", entry.getUpdatedAt().toString());
            }
        }
        return root;
    }

    private ObjectNode error(String code, String message) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode error = root.putObject("error");
        error.put("code", code);
        error.put("message", message != null ? message : "recall failed");
        return root;
    }
}
