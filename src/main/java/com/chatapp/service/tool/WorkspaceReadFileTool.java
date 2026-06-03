package com.chatapp.service.tool;

import com.chatapp.dto.WorkspaceDto;
import com.chatapp.service.WorkspaceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * workspace_read_file (Phase 5b / F6 slice 2): read a text file's current content from a
 * workspace the calling bot may access. Binary/oversized files are rejected.
 */
@Component
@Slf4j
public class WorkspaceReadFileTool implements Tool {

    private final ObjectMapper objectMapper;
    private final WorkspaceService workspaceService;

    public WorkspaceReadFileTool(ObjectMapper objectMapper, WorkspaceService workspaceService) {
        this.objectMapper = objectMapper;
        this.workspaceService = workspaceService;
    }

    @Override
    public String name() {
        return "workspace_read_file";
    }

    @Override
    public String description() {
        return "Read the current text content of a workspace file you have access to.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("workspace_id").put("type", "integer");
        properties.putObject("file_id").put("type", "integer");
        schema.putArray("required").add("workspace_id").add("file_id");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params, ToolContext context) {
        if (context.botConfigId() == null) {
            return ToolErrors.node(objectMapper, "no_bot", "workspace tools require a bot context");
        }
        Long workspaceId = ToolErrors.requiredId(params, "workspace_id");
        Long fileId = ToolErrors.requiredId(params, "file_id");
        try {
            WorkspaceDto.TextContent text =
                    workspaceService.readTextForBot(workspaceId, fileId, context.botConfigId());
            ObjectNode root = objectMapper.createObjectNode();
            root.put("file_id", text.getFileId());
            root.put("name", text.getDisplayName());
            if (text.getCurrentVersion() != null) {
                root.put("version", text.getCurrentVersion());
            }
            root.put("content", text.getContent());
            return root;
        } catch (AccessDeniedException e) {
            return ToolErrors.node(objectMapper, "forbidden", e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolErrors.node(objectMapper, "invalid", e.getMessage());
        } catch (IOException e) {
            log.warn("workspace_read_file io error ws={} file={}: {}", workspaceId, fileId, e.getMessage());
            return ToolErrors.node(objectMapper, "io_error", "could not read file content");
        }
    }
}
