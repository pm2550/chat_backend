package com.chatapp.service.tool;

import com.chatapp.service.ModerationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * mute_member (F5 Slice 2): a bot granted MUTE moderation in the room can mute/unmute a
 * non-privileged member. The grant + target protection are enforced by ModerationService.
 */
@Component
@Slf4j
public class MuteMemberTool implements Tool {

    private final ObjectMapper objectMapper;
    private final ModerationService moderationService;

    public MuteMemberTool(ObjectMapper objectMapper, ModerationService moderationService) {
        this.objectMapper = objectMapper;
        this.moderationService = moderationService;
    }

    @Override
    public String name() {
        return "mute_member";
    }

    @Override
    public String description() {
        return "Mute (or unmute) a member of the current room. Requires that the room owner has "
                + "granted you mute permission. You cannot mute the owner or admins.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("user_id").put("type", "integer");
        properties.putObject("mute").put("type", "boolean")
                .put("description", "true to mute (default), false to unmute");
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
        boolean mute = params.path("mute").asBoolean(true);
        try {
            moderationService.muteByBot(context.botConfigId(), context.roomId(), userId, mute);
            ObjectNode root = objectMapper.createObjectNode();
            root.put("muted", mute);
            root.put("user_id", userId);
            return root;
        } catch (AccessDeniedException e) {
            return ToolErrors.node(objectMapper, "forbidden", e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolErrors.node(objectMapper, "invalid", e.getMessage());
        }
    }
}
