package com.chatapp.service.tool;

import com.chatapp.dto.WorkspaceDto;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.Message;
import com.chatapp.entity.WorkspaceFile;
import com.chatapp.repository.BotConfigRepository;
import com.chatapp.service.BotGatewayService;
import com.chatapp.service.WorkspaceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceToolsTest {

    private final ObjectMapper om = new ObjectMapper();
    @Mock private WorkspaceService workspaceService;
    @Mock private BotGatewayService botGatewayService;
    @Mock private BotConfigRepository botConfigRepository;

    private ToolContext ctx(Long roomId, Long botId) {
        return new ToolContext(roomId, 1L, null, botId);
    }

    private static WorkspaceDto.FileDto fileDto(Long id, String name, Integer version) {
        WorkspaceDto.FileDto f = new WorkspaceDto.FileDto();
        f.setId(id);
        f.setDisplayName(name);
        f.setFileSize(3L);
        f.setCurrentVersion(version);
        f.setSourceType(WorkspaceFile.SourceType.BOT);
        return f;
    }

    // ---- workspace_list_files ----

    @Test
    void listFilesReturnsAccessibleFiles() {
        WorkspaceListFilesTool tool = new WorkspaceListFilesTool(om, workspaceService);
        when(workspaceService.listFilesForBot(100L, 5L, null))
                .thenReturn(List.of(fileDto(7L, "a.txt", 1)));
        ObjectNode p = om.createObjectNode();
        p.put("workspace_id", 100);

        JsonNode out = tool.execute(p, ctx(10L, 5L));

        assertEquals(1, out.path("files").size());
        assertEquals(7L, out.path("files").get(0).path("id").asLong());
        assertEquals("a.txt", out.path("files").get(0).path("name").asText());
        assertEquals("BOT", out.path("files").get(0).path("source").asText());
    }

    @Test
    void listFilesWithoutBotContextErrors() {
        WorkspaceListFilesTool tool = new WorkspaceListFilesTool(om, workspaceService);
        ObjectNode p = om.createObjectNode();
        p.put("workspace_id", 100);

        JsonNode out = tool.execute(p, ctx(10L, null));

        assertEquals("no_bot", out.path("error").path("code").asText());
        verifyNoInteractions(workspaceService);
    }

    @Test
    void listFilesMapsAccessDeniedToForbidden() {
        WorkspaceListFilesTool tool = new WorkspaceListFilesTool(om, workspaceService);
        when(workspaceService.listFilesForBot(any(), any(), any()))
                .thenThrow(new AccessDeniedException("机器人没有该工作区资源的权限"));
        ObjectNode p = om.createObjectNode();
        p.put("workspace_id", 100);

        JsonNode out = tool.execute(p, ctx(10L, 5L));

        assertEquals("forbidden", out.path("error").path("code").asText());
    }

    // ---- workspace_read_file ----

    @Test
    void readFileReturnsTextContent() throws Exception {
        WorkspaceReadFileTool tool = new WorkspaceReadFileTool(om, workspaceService);
        when(workspaceService.readTextForBot(100L, 7L, 5L))
                .thenReturn(new WorkspaceDto.TextContent(7L, "a.txt", "text/plain", 2, "hello"));
        ObjectNode p = om.createObjectNode();
        p.put("workspace_id", 100);
        p.put("file_id", 7);

        JsonNode out = tool.execute(p, ctx(10L, 5L));

        assertEquals("hello", out.path("content").asText());
        assertEquals(2, out.path("version").asInt());
        assertEquals("a.txt", out.path("name").asText());
    }

    @Test
    void readFileMissingFileIdThrowsInvalidParams() {
        WorkspaceReadFileTool tool = new WorkspaceReadFileTool(om, workspaceService);
        ObjectNode p = om.createObjectNode();
        p.put("workspace_id", 100); // no file_id
        assertThrows(ToolExecutionException.class, () -> tool.execute(p, ctx(10L, 5L)));
    }

    // ---- workspace_write_file ----

    @Test
    void writeCreatesNewFileWhenFileIdOmitted() throws Exception {
        WorkspaceWriteFileTool tool = new WorkspaceWriteFileTool(om, workspaceService);
        when(workspaceService.createTextFileForBot(eq(100L), eq(5L), isNull(), eq("new.txt"), eq("body"), isNull()))
                .thenReturn(fileDto(9L, "new.txt", 1));
        ObjectNode p = om.createObjectNode();
        p.put("workspace_id", 100);
        p.put("content", "body");
        p.put("file_name", "new.txt");

        JsonNode out = tool.execute(p, ctx(10L, 5L));

        assertTrue(out.path("saved").asBoolean());
        assertEquals(9L, out.path("file_id").asLong());
        verify(workspaceService).createTextFileForBot(100L, 5L, null, "new.txt", "body", null);
    }

    @Test
    void writeSavesNewVersionWhenFileIdGiven() throws Exception {
        WorkspaceWriteFileTool tool = new WorkspaceWriteFileTool(om, workspaceService);
        when(workspaceService.saveTextVersionForBot(eq(100L), eq(7L), eq(5L), eq("updated"), eq("note")))
                .thenReturn(fileDto(7L, "a.txt", 2));
        ObjectNode p = om.createObjectNode();
        p.put("workspace_id", 100);
        p.put("content", "updated");
        p.put("file_id", 7);
        p.put("version_note", "note");

        JsonNode out = tool.execute(p, ctx(10L, 5L));

        assertEquals(2, out.path("version").asInt());
        verify(workspaceService).saveTextVersionForBot(100L, 7L, 5L, "updated", "note");
    }

    @Test
    void writeWithoutFileIdOrNameThrowsInvalidParams() {
        WorkspaceWriteFileTool tool = new WorkspaceWriteFileTool(om, workspaceService);
        ObjectNode p = om.createObjectNode();
        p.put("workspace_id", 100);
        p.put("content", "x"); // neither file_id nor file_name
        assertThrows(ToolExecutionException.class, () -> tool.execute(p, ctx(10L, 5L)));
    }

    @Test
    void writeWithInvalidFileIdIsRejectedNotSilentlyCreated() {
        // A present-but-invalid file_id (0) must NOT be coerced to "absent" (which would
        // silently create a new file). It is an invalid_params error.
        WorkspaceWriteFileTool tool = new WorkspaceWriteFileTool(om, workspaceService);
        ObjectNode p = om.createObjectNode();
        p.put("workspace_id", 100);
        p.put("content", "edited");
        p.put("file_id", 0);
        p.put("file_name", "decoy.txt");
        assertThrows(ToolExecutionException.class, () -> tool.execute(p, ctx(10L, 5L)));
        verifyNoInteractions(workspaceService);
    }

    @Test
    void requiredIdRejectsNonNumericValue() {
        WorkspaceReadFileTool tool = new WorkspaceReadFileTool(om, workspaceService);
        ObjectNode p = om.createObjectNode();
        p.put("workspace_id", "not-a-number");
        p.put("file_id", 7);
        assertThrows(ToolExecutionException.class, () -> tool.execute(p, ctx(10L, 5L)));
    }

    // ---- send_file_to_room ----

    @Test
    void sendFileToRoomReadsThenPostsAsBot() throws Exception {
        SendFileToRoomTool tool =
                new SendFileToRoomTool(om, workspaceService, botGatewayService, botConfigRepository);
        when(workspaceService.readTextForBot(100L, 7L, 5L))
                .thenReturn(new WorkspaceDto.TextContent(7L, "a.txt", "text/plain", 1, "hello world"));
        BotConfig bot = new BotConfig();
        bot.setId(5L);
        when(botConfigRepository.findById(5L)).thenReturn(Optional.of(bot));
        Message posted = new Message();
        posted.setId(321L);
        when(botGatewayService.postAsBot(eq(bot), eq(10L), anyString())).thenReturn(posted);

        ObjectNode p = om.createObjectNode();
        p.put("workspace_id", 100);
        p.put("file_id", 7);

        JsonNode out = tool.execute(p, ctx(10L, 5L));

        assertTrue(out.path("sent").asBoolean());
        assertEquals(321L, out.path("message_id").asLong());
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(botGatewayService).postAsBot(eq(bot), eq(10L), body.capture());
        assertTrue(body.getValue().contains("hello world"));
        assertTrue(body.getValue().contains("a.txt"));
    }

    @Test
    void sendFileToRoomWithoutRoomErrors() {
        SendFileToRoomTool tool =
                new SendFileToRoomTool(om, workspaceService, botGatewayService, botConfigRepository);
        ObjectNode p = om.createObjectNode();
        p.put("workspace_id", 100);
        p.put("file_id", 7);

        JsonNode out = tool.execute(p, ctx(null, 5L));

        assertEquals("no_room", out.path("error").path("code").asText());
        verifyNoInteractions(botGatewayService);
    }
}
