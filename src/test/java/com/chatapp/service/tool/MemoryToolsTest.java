package com.chatapp.service.tool;

import com.chatapp.entity.MemoryEntry;
import com.chatapp.service.MemoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryToolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    @Mock private MemoryService memoryService;

    private ToolContext ctx(Long roomId, Long botId) {
        return new ToolContext(roomId, 1L, null, botId);
    }

    private static MemoryEntry savedEntry() {
        MemoryEntry m = new MemoryEntry();
        m.setId(42L);
        m.setTitle("fact");
        m.setContent("the sky is blue");
        m.setSourceType(MemoryEntry.SourceType.BOT);
        m.setVisibility(MemoryEntry.Visibility.ROOM);
        m.setPinned(false);
        m.setUpdatedAt(LocalDateTime.of(2026, 6, 2, 12, 0));
        return m;
    }

    // ---- save_memory ----

    @Test
    void saveMemoryDelegatesToServiceAndReturnsId() {
        SaveMemoryTool tool = new SaveMemoryTool(objectMapper, memoryService);
        when(memoryService.saveForBot(eq(100L), eq(5L), eq("fact"), eq("the sky is blue"), isNull()))
                .thenReturn(savedEntry());

        ObjectNode params = objectMapper.createObjectNode();
        params.put("title", "fact");
        params.put("content", "the sky is blue");

        JsonNode out = tool.execute(params, ctx(100L, 5L));

        assertTrue(out.path("saved").asBoolean());
        assertEquals(42L, out.path("id").asLong());
        assertEquals("fact", out.path("title").asText());
    }

    @Test
    void saveMemoryRejectsBlankParams() {
        SaveMemoryTool tool = new SaveMemoryTool(objectMapper, memoryService);
        ObjectNode params = objectMapper.createObjectNode();
        params.put("title", "   ");
        params.put("content", "");
        assertThrows(ToolExecutionException.class, () -> tool.execute(params, ctx(100L, 5L)));
        verify(memoryService, never()).saveForBot(any(), any(), any(), any(), any());
    }

    @Test
    void saveMemoryWithoutRoomReturnsError() {
        SaveMemoryTool tool = new SaveMemoryTool(objectMapper, memoryService);
        ObjectNode params = objectMapper.createObjectNode();
        params.put("title", "fact");
        params.put("content", "x");
        JsonNode out = tool.execute(params, ctx(null, 5L));
        assertEquals("no_room", out.path("error").path("code").asText());
        verify(memoryService, never()).saveForBot(any(), any(), any(), any(), any());
    }

    @Test
    void saveMemorySurfacesServiceFailureAsError() {
        SaveMemoryTool tool = new SaveMemoryTool(objectMapper, memoryService);
        when(memoryService.saveForBot(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("cap reached"));
        ObjectNode params = objectMapper.createObjectNode();
        params.put("title", "fact");
        params.put("content", "x");
        JsonNode out = tool.execute(params, ctx(100L, 5L));
        assertEquals("save_failed", out.path("error").path("code").asText());
    }

    // ---- recall_memory ----

    @Test
    void recallMemoryReturnsRoomVisibleResults() {
        RecallMemoryTool tool = new RecallMemoryTool(objectMapper, memoryService);
        // userId must be null so private memories never leak through a bot.
        when(memoryService.recall(eq(100L), isNull(), eq("blue"), eq(5)))
                .thenReturn(List.of(savedEntry()));

        ObjectNode params = objectMapper.createObjectNode();
        params.put("query", "blue");

        JsonNode out = tool.execute(params, ctx(100L, 5L));

        assertEquals("blue", out.path("query").asText());
        assertEquals(1, out.path("results").size());
        JsonNode first = out.path("results").get(0);
        assertEquals(42L, first.path("id").asLong());
        assertEquals("the sky is blue", first.path("content").asText());
        assertEquals("BOT", first.path("source").asText());
        verify(memoryService).recall(eq(100L), isNull(), eq("blue"), eq(5));
    }

    @Test
    void recallMemoryWithoutRoomReturnsError() {
        RecallMemoryTool tool = new RecallMemoryTool(objectMapper, memoryService);
        JsonNode out = tool.execute(objectMapper.createObjectNode(), ctx(null, 5L));
        assertEquals("no_room", out.path("error").path("code").asText());
        assertFalse(out.has("results"));
    }
}
