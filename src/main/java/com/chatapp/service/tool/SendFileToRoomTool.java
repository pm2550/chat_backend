package com.chatapp.service.tool;

import com.chatapp.dto.WorkspaceDto;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.Message;
import com.chatapp.repository.BotConfigRepository;
import com.chatapp.service.BotGatewayService;
import com.chatapp.service.WorkspaceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * send_file_to_room (Phase 5b / F6 slice 2): post a workspace text file's content into the
 * bot's current chat room as a message from the bot. Reading is gated by the bot's workspace
 * access; posting is gated by {@link BotGatewayService#postAsBot} (the bot must be bound to
 * the room). Content is capped for chat readability.
 */
@Component
@Slf4j
public class SendFileToRoomTool implements Tool {

    private static final int MAX_SEND_CHARS = 4000;

    private final ObjectMapper objectMapper;
    private final WorkspaceService workspaceService;
    private final BotGatewayService botGatewayService;
    private final BotConfigRepository botConfigRepository;

    // @Lazy on BotGatewayService breaks the bean cycle
    // sendFileToRoomTool -> botGatewayService -> rawWebSocketHandler -> botService ->
    // agentToolRegistry -> sendFileToRoomTool (all constructor injection). The proxy
    // resolves the real bean on first call, long after the context is built.
    public SendFileToRoomTool(ObjectMapper objectMapper, WorkspaceService workspaceService,
                              @Lazy BotGatewayService botGatewayService, BotConfigRepository botConfigRepository) {
        this.objectMapper = objectMapper;
        this.workspaceService = workspaceService;
        this.botGatewayService = botGatewayService;
        this.botConfigRepository = botConfigRepository;
    }

    @Override
    public String name() {
        return "send_file_to_room";
    }

    @Override
    public String description() {
        return "Send a workspace text file's content into the current chat room as a message "
                + "from you. Use to share a document/notes you read or edited.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("workspace_id").put("type", "integer");
        properties.putObject("file_id").put("type", "integer");
        properties.putObject("note").put("type", "string")
                .put("description", "optional message to prefix before the file content");
        schema.putArray("required").add("workspace_id").add("file_id");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params, ToolContext context) {
        if (context.botConfigId() == null) {
            return ToolErrors.node(objectMapper, "no_bot", "workspace tools require a bot context");
        }
        if (context.roomId() == null) {
            return ToolErrors.node(objectMapper, "no_room", "this tool can only send within a room");
        }
        Long workspaceId = ToolErrors.requiredId(params, "workspace_id");
        Long fileId = ToolErrors.requiredId(params, "file_id");
        String note = params.path("note").asText("").trim();
        try {
            WorkspaceDto.TextContent text =
                    workspaceService.readTextForBot(workspaceId, fileId, context.botConfigId());
            BotConfig bot = botConfigRepository.findById(context.botConfigId()).orElse(null);
            if (bot == null) {
                return ToolErrors.node(objectMapper, "no_bot", "bot not found");
            }
            StringBuilder body = new StringBuilder();
            if (!note.isBlank()) {
                body.append(note).append("\n\n");
            }
            body.append("📄 ").append(text.getDisplayName());
            if (text.getCurrentVersion() != null) {
                body.append(" (v").append(text.getCurrentVersion()).append(")");
            }
            body.append("\n\n").append(text.getContent());
            String content = body.toString();
            boolean truncated = content.length() > MAX_SEND_CHARS;
            if (truncated) {
                content = content.substring(0, MAX_SEND_CHARS) + "\n…（已截断）";
            }
            Message message = botGatewayService.postAsBot(bot, context.roomId(), content);
            ObjectNode root = objectMapper.createObjectNode();
            root.put("sent", true);
            root.put("file_id", fileId);
            root.put("room_id", context.roomId());
            root.put("message_id", message.getId());
            root.put("truncated", truncated);
            return root;
        } catch (AccessDeniedException e) {
            return ToolErrors.node(objectMapper, "forbidden", e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            // IllegalStateException covers postAsBot's "sender not found" path.
            return ToolErrors.node(objectMapper, "invalid", e.getMessage());
        } catch (IOException e) {
            log.warn("send_file_to_room io error ws={} file={}: {}", workspaceId, fileId, e.getMessage());
            return ToolErrors.node(objectMapper, "io_error", "could not read file content");
        }
    }
}
