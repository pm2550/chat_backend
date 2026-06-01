package com.chatapp.service.tool;

import com.chatapp.websocket.RawWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolDispatcherTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serverToolExecutesDirectly() {
        PendingClientCallRegistry registry = new PendingClientCallRegistry();
        RawWebSocketHandler raw = mock(RawWebSocketHandler.class);
        AgentToolDispatcher dispatcher = new AgentToolDispatcher(registry, raw, objectMapper);

        JsonNode result = dispatcher.dispatch(
                new TestTool(Tool.ExecutionContext.SERVER),
                objectMapper.createObjectNode().put("value", "server"),
                new ToolContext(10L, 7L, 99L),
                30000);

        assertEquals("server", result.path("value").asText());
        assertEquals(0, registry.pendingCount());
    }

    @Test
    void clientToolReturnsSuccessfulReply() throws Exception {
        PendingClientCallRegistry registry = new PendingClientCallRegistry();
        RawWebSocketHandler raw = mock(RawWebSocketHandler.class);
        when(raw.sendAgentToolRequest(eq(7L), any(UUID.class), eq("client_test"), any(JsonNode.class)))
                .thenReturn(true);
        AgentToolDispatcher dispatcher = new AgentToolDispatcher(registry, raw, objectMapper);
        Tool tool = new TestTool(Tool.ExecutionContext.CLIENT);

        CompletableFuture<JsonNode> future = CompletableFuture.supplyAsync(() ->
                dispatcher.dispatch(tool, objectMapper.createObjectNode().put("value", "client"),
                        new ToolContext(10L, 7L, 99L), 1000));

        ArgumentCaptor<UUID> callIdCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(raw, timeout(1000)).sendAgentToolRequest(eq(7L), callIdCaptor.capture(), eq("client_test"), any(JsonNode.class));
        registry.complete(callIdCaptor.getValue(), 7L, objectMapper.createObjectNode().put("answer", "ok"));

        JsonNode result = future.get(1, TimeUnit.SECONDS);
        assertEquals("ok", result.path("answer").asText());
        assertEquals(0, registry.pendingCount());
    }

    @Test
    void clientToolTimeoutReturnsErrorNode() {
        PendingClientCallRegistry registry = new PendingClientCallRegistry();
        RawWebSocketHandler raw = mock(RawWebSocketHandler.class);
        when(raw.sendAgentToolRequest(eq(7L), any(UUID.class), eq("client_test"), any(JsonNode.class)))
                .thenReturn(true);
        AgentToolDispatcher dispatcher = new AgentToolDispatcher(registry, raw, objectMapper);

        JsonNode result = dispatcher.dispatch(
                new TestTool(Tool.ExecutionContext.CLIENT),
                objectMapper.createObjectNode(),
                new ToolContext(10L, 7L, 99L),
                25);

        assertEquals("client_tool_timeout", result.path("error").path("code").asText());
    }

    @Test
    void forgedClientToolReplyIsIgnoredAndThenTimesOut() throws Exception {
        PendingClientCallRegistry registry = new PendingClientCallRegistry();
        RawWebSocketHandler raw = mock(RawWebSocketHandler.class);
        when(raw.sendAgentToolRequest(eq(7L), any(UUID.class), eq("client_test"), any(JsonNode.class)))
                .thenReturn(true);
        AgentToolDispatcher dispatcher = new AgentToolDispatcher(registry, raw, objectMapper);
        Tool tool = new TestTool(Tool.ExecutionContext.CLIENT);

        CompletableFuture<JsonNode> future = CompletableFuture.supplyAsync(() ->
                dispatcher.dispatch(tool, objectMapper.createObjectNode(),
                        new ToolContext(10L, 7L, 99L), 50));

        ArgumentCaptor<UUID> callIdCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(raw, timeout(1000)).sendAgentToolRequest(eq(7L), callIdCaptor.capture(), eq("client_test"), any(JsonNode.class));
        boolean accepted = registry.complete(callIdCaptor.getValue(), 8L,
                objectMapper.createObjectNode().put("forged", true));

        JsonNode result = future.get(1, TimeUnit.SECONDS);
        assertTrue(!accepted);
        assertEquals("client_tool_timeout", result.path("error").path("code").asText());
    }

    private class TestTool implements Tool {
        private final ExecutionContext context;

        private TestTool(ExecutionContext context) {
            this.context = context;
        }

        @Override
        public String name() {
            return "client_test";
        }

        @Override
        public String description() {
            return "test";
        }

        @Override
        public JsonNode parametersSchema() {
            return objectMapper.createObjectNode().put("type", "object");
        }

        @Override
        public ExecutionContext executionContext() {
            return context;
        }

        @Override
        public JsonNode execute(JsonNode params, ToolContext context) {
            return objectMapper.createObjectNode().put("value", params.path("value").asText());
        }
    }
}
