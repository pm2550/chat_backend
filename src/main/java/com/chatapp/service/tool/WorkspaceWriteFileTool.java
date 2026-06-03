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
 * workspace_write_file (Phase 5b / F6 slice 2): create a new text file or save a new version
 * of an existing one. Pass file_id to add a version (preserving the name), or file_name to
 * create. Requires the bot to have EDIT access on the workspace/folder/file.
 */
@Component
@Slf4j
public class WorkspaceWriteFileTool implements Tool {

    private final ObjectMapper objectMapper;
    private final WorkspaceService workspaceService;

    public WorkspaceWriteFileTool(ObjectMapper objectMapper, WorkspaceService workspaceService) {
        this.objectMapper = objectMapper;
        this.workspaceService = workspaceService;
    }

    @Override
    public String name() {
        return "workspace_write_file";
    }

    @Override
    public String description() {
        return "Write text to a workspace. Provide file_id to save a new version of an existing "
                + "file, or file_name to create a new one. Requires edit access.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("workspace_id").put("type", "integer");
        properties.putObject("content").put("type", "string");
        properties.putObject("file_id").put("type", "integer")
                .put("description", "edit this existing file (new version); omit to create");
        properties.putObject("file_name").put("type", "string")
                .put("description", "name for a new file when file_id is omitted");
        properties.putObject("folder_id").put("type", "integer")
                .put("description", "optional folder for a newly created file");
        properties.putObject("version_note").put("type", "string");
        schema.putArray("required").add("workspace_id").add("content");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params, ToolContext context) {
        if (context.botConfigId() == null) {
            return ToolErrors.node(objectMapper, "no_bot", "workspace tools require a bot context");
        }
        Long workspaceId = ToolErrors.requiredId(params, "workspace_id");
        if (!params.hasNonNull("content")) {
            throw new ToolExecutionException("invalid_params", "content is required");
        }
        String content = params.path("content").asText("");
        String versionNote = params.path("version_note").asText("").trim();
        versionNote = versionNote.isBlank() ? null : versionNote;
        Long fileId = ToolErrors.optionalId(params, "file_id");
        Long folderId = ToolErrors.optionalId(params, "folder_id");
        try {
            WorkspaceDto.FileDto result;
            if (fileId != null) {
                result = workspaceService.saveTextVersionForBot(
                        workspaceId, fileId, context.botConfigId(), content, versionNote);
            } else {
                String fileName = params.path("file_name").asText("").trim();
                if (fileName.isBlank()) {
                    throw new ToolExecutionException("invalid_params",
                            "file_name is required when file_id is omitted");
                }
                result = workspaceService.createTextFileForBot(
                        workspaceId, context.botConfigId(), folderId, fileName, content, versionNote);
            }
            ObjectNode root = objectMapper.createObjectNode();
            root.put("saved", true);
            root.put("file_id", result.getId());
            root.put("name", result.getDisplayName());
            if (result.getCurrentVersion() != null) {
                root.put("version", result.getCurrentVersion());
            }
            return root;
        } catch (AccessDeniedException e) {
            return ToolErrors.node(objectMapper, "forbidden", e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ToolErrors.node(objectMapper, "invalid", e.getMessage());
        } catch (IOException e) {
            log.warn("workspace_write_file io error ws={} file={}: {}", workspaceId, fileId, e.getMessage());
            return ToolErrors.node(objectMapper, "io_error", "could not write file");
        }
    }
}
