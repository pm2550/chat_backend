package com.chatapp.service.tool;

import com.chatapp.service.ModerationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * kick_member (F5 Slice 2): a bot granted KICK moderation in the room can remove a
 * non-privileged member. The grant + target protection are enforced by ModerationService.
 */
@Component
@Slf4j
public class KickMemberTool implements Tool {

    private final ObjectMapper objectMapper;
    private final ModerationService moderationService;

    public KickMemberTool(ObjectMapper objectMapper, ModerationService moderationService) {
        this.objectMapper = objectMapper;
        this.moderationService = moderationService;
    }

    @Override
    public String name() {
        return "kick_member";
    }

    @Override
    public String description() {
        return "Remove a member from the current room. Requires that the room owner has granted "
                + "you kick permission. You cannot kick the owner or admins.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("user_id").put("type", "integer");
        properties.putObject("reason").put("type", "string");
        schema.putArray("required").add("user_id");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params, ToolContext context) {
        if (context.botConfigId() == null) {
            return ToolErrors.node(objectMapper, "no_bot", "moderation requires a bot context");
        }
        if (context.roomId() == null) {
            return ToolErrors.node(objectMapper, "no_room", "moderation only works within a room");
        }
        Long userId = ToolErrors.requiredId(params, "user_id");
        try {
            moderationService.kickByBot(context.botConfigId(), context.roomId(), userId);
            ObjectNode root = objectMapper.createObjectNode();
            root.put("kicked", true);
            root.put("user_id", userId);
            return root;
        } catch (AccessDeniedException e) {
            return ToolErrors.node(objectMapper, "forbidden", e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolErrors.node(objectMapper, "invalid", e.getMessage());
        }
    }
}
