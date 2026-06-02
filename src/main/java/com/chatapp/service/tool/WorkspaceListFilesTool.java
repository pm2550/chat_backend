package com.chatapp.service.tool;

import com.chatapp.dto.WorkspaceDto;
import com.chatapp.service.WorkspaceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * workspace_list_files (Phase 5b / F6 slice 2): lists files in a workspace (optionally a
 * folder) that the calling bot may access. Access is governed by the BOT's own workspace
 * permission (effectiveBotAccess) — never the triggering user's.
 */
@Component
@Slf4j
public class WorkspaceListFilesTool implements Tool {

    private final ObjectMapper objectMapper;
    private final WorkspaceService workspaceService;

    public WorkspaceListFilesTool(ObjectMapper objectMapper, WorkspaceService workspaceService) {
        this.objectMapper = objectMapper;
        this.workspaceService = workspaceService;
    }

    @Override
    public String name() {
        return "workspace_list_files";
    }

    @Override
    public String description() {
        return "List files in a workspace you have access to (optionally within a folder). "
                + "Returns file ids you can then read or edit.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("workspace_id").put("type", "integer");
        properties.putObject("folder_id").put("type", "integer")
                .put("description", "optional folder to list; omit for the workspace root");
        schema.putArray("required").add("workspace_id");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params, ToolContext context) {
        if (context.botConfigId() == null) {
            return ToolErrors.node(objectMapper, "no_bot", "workspace tools require a bot context");
        }
        Long workspaceId = ToolErrors.requiredId(params, "workspace_id");
        Long folderId = ToolErrors.optionalId(params, "folder_id");
        try {
            List<WorkspaceDto.FileDto> files =
                    workspaceService.listFilesForBot(workspaceId, context.botConfigId(), folderId);
            ObjectNode root = objectMapper.createObjectNode();
            root.put("workspace_id", workspaceId);
            ArrayNode arr = root.putArray("files");
            for (WorkspaceDto.FileDto f : files) {
                ObjectNode item = arr.addObject();
                item.put("id", f.getId());
                item.put("name", f.getDisplayName());
                if (f.getFileSize() != null) {
                    item.put("size", f.getFileSize());
                }
                if (f.getCurrentVersion() != null) {
                    item.put("version", f.getCurrentVersion());
                }
                if (f.getSourceType() != null) {
                    item.put("source", f.getSourceType().name());
                }
            }
            return root;
        } catch (AccessDeniedException e) {
            return ToolErrors.node(objectMapper, "forbidden", e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolErrors.node(objectMapper, "invalid", e.getMessage());
        }
    }
}
