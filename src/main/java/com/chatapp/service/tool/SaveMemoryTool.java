package com.chatapp.service.tool;

import com.chatapp.entity.MemoryEntry;
import com.chatapp.service.MemoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * save_memory (Phase 5a / F2): lets a tool-enabled bot persist a durable fact into the
 * room's shared memory library, retrievable later via {@link RecallMemoryTool}. Writes
 * are always ROOM-visible — a bot never touches a user's private memories.
 */
@Component
@Slf4j
public class SaveMemoryTool implements Tool {

    private final ObjectMapper objectMapper;
    private final MemoryService memoryService;

    public SaveMemoryTool(ObjectMapper objectMapper, MemoryService memoryService) {
        this.objectMapper = objectMapper;
        this.memoryService = memoryService;
    }

    @Override
    public String name() {
        return "save_memory";
    }

    @Override
    public String description() {
        return "Save a durable fact into this room's shared memory so it can be recalled "
                + "in later conversations. Use a short title and put the fact in content.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("title").put("type", "string").put("minLength", 1).put("maxLength", 200);
        properties.putObject("content").put("type", "string").put("minLength", 1);
        properties.putObject("keywords").put("type", "string")
                .put("description", "optional comma/space-separated keywords to aid recall");
        schema.putArray("required").add("title").add("content");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params, ToolContext context) {
        String title = params.path("title").asText("").trim();
        String content = params.path("content").asText("").trim();
        String keywords = params.path("keywords").asText("").trim();
        if (title.isBlank() || content.isBlank()) {
            throw new ToolExecutionException("invalid_params", "title and content are required");
        }
        if (context.roomId() == null) {
            return error("no_room", "memory can only be saved within a room");
        }
        try {
            MemoryEntry saved = memoryService.saveForBot(
                    context.roomId(), context.botConfigId(), title, content,
                    keywords.isBlank() ? null : keywords);
            ObjectNode root = objectMapper.createObjectNode();
            root.put("saved", true);
            root.put("id", saved.getId());
            root.put("title", saved.getTitle());
            return root;
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("save_memory failed room={} bot={}: {}",
                    context.roomId(), context.botConfigId(), e.getMessage());
            return error("save_failed", e.getMessage());
        }
    }

    private ObjectNode error(String code, String message) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode error = root.putObject("error");
        error.put("code", code);
        error.put("message", message != null ? message : "save failed");
        return root;
    }
}
