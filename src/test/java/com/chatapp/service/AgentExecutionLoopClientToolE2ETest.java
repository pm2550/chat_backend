package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.AgentTask;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.User;
import com.chatapp.service.tool.AgentToolDispatcher;
import com.chatapp.service.tool.AgentToolRegistry;
import com.chatapp.service.tool.PendingClientCallRegistry;
import com.chatapp.service.tool.Tool;
import com.chatapp.service.tool.ToolContext;
import com.chatapp.websocket.RawWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentExecutionLoopClientToolE2ETest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void clientToolRoundTripCompletesAcrossTwoIterations() throws Exception {
        LLMService llmService = mock(LLMService.class);
        AgentToolRegistry toolRegistry = mock(AgentToolRegistry.class);
        RawWebSocketHandler rawWebSocketHandler = mock(RawWebSocketHandler.class);
        PendingClientCallRegistry pending = new PendingClientCallRegistry();
        AgentToolDispatcher dispatcher = new AgentToolDispatcher(pending, rawWebSocketHandler, objectMapper);
        AgentContextBuilder contextBuilder = mock(AgentContextBuilder.class);
        AgentExecutionLoop loop = new AgentExecutionLoop(
                llmService,
                toolRegistry,
                dispatcher,
                contextBuilder,
                objectMapper);

        BotConfig bot = new BotConfig();
        bot.setId(99L);
        bot.setBotName("Agent");
        bot.setMaxAgentIterations(8);
        bot.setMaxAgentWallclockMs(30000);
        bot.setMaxAgentTotalTokens(50000);
        AgentTask task = task(bot);
        AgentContextBuilder.AgentContextEnvelope envelope = envelope();
        Tool clientTool = new FakeClientTool();

        when(contextBuilder.assembleSystemPrompt(envelope)).thenReturn("system prompt");
        when(contextBuilder.estimateTokens(any())).thenAnswer(invocation -> {
            String value = invocation.getArgument(0);
            return value == null ? 0 : Math.max(1, value.length() / 4);
        });
        when(toolRegistry.listToolsForBot(bot)).thenReturn(List.of(clientTool));
        when(toolRegistry.getTool("fake_client_tool")).thenReturn(Optional.of(clientTool));
        when(llmService.chat(any(BotConfig.class), anyList(), anyList()))
                .thenReturn(new BotDto.LLMResponse("", 5, "m",
                        List.of(new BotDto.ToolCall("call-1", "fake_client_tool", "{\"question\":\"state\"}"))))
                .thenReturn(new BotDto.LLMResponse("client said muted=false", 7, "m"));

        AtomicReference<UUID> capturedCallId = new AtomicReference<>();
        CompletableFuture<Void> replySent = new CompletableFuture<>();
        doAnswer(invocation -> {
            UUID callId = invocation.getArgument(1);
            capturedCallId.set(callId);
            CompletableFuture.runAsync(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(50);
                    pending.complete(callId, 1L,
                            objectMapper.createObjectNode().put("muted", false));
                    replySent.complete(null);
                } catch (Exception e) {
                    replySent.completeExceptionally(e);
                }
            });
            return true;
        }).when(rawWebSocketHandler).sendAgentToolRequest(
                eq(1L),
                any(UUID.class),
                eq("fake_client_tool"),
                any(JsonNode.class));

        AgentExecutionLoop.AgentLoopResult result = loop.runLoop(task, envelope);

        replySent.get(1, TimeUnit.SECONDS);
        assertEquals("client said muted=false", result.finalContent());
        assertEquals(2, result.iterations());
        assertEquals(AgentExecutionLoop.TerminationReason.FINAL_ANSWER, result.terminationReason());
        assertEquals(1, result.toolCallsMade().size());
        assertTrue(capturedCallId.get() != null);
        verify(llmService, times(2)).chat(eq(bot), anyList(), anyList());
        verify(rawWebSocketHandler).sendAgentToolRequest(
                eq(1L),
                any(UUID.class),
                eq("fake_client_tool"),
                any(JsonNode.class));
    }

    private AgentTask task(BotConfig bot) {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        ChatRoom room = new ChatRoom();
        room.setId(10L);
        AgentTask task = new AgentTask();
        task.setId(77L);
        task.setPrompt("please help");
        task.setRequestedBy(user);
        task.setChatRoom(room);
        task.setBotConfig(bot);
        return task;
    }

    private AgentContextBuilder.AgentContextEnvelope envelope() {
        return new AgentContextBuilder.AgentContextEnvelope(
                new AgentContextBuilder.AgentIdentity("Agent", "", "base", null),
                new AgentContextBuilder.RoomMetadata(true, "Room", "", 1, List.of("Alice"), null, true),
                List.of(),
                new AgentContextBuilder.InitiatorInfo("Alice", "member", false),
                List.of("Be concise"),
                "please help",
                6000,
                20);
    }

    private class FakeClientTool implements Tool {
        @Override
        public String name() {
            return "fake_client_tool";
        }

        @Override
        public String description() {
            return "fake client-only test tool";
        }

        @Override
        public JsonNode parametersSchema() {
            return objectMapper.createObjectNode().put("type", "object");
        }

        @Override
        public ExecutionContext executionContext() {
            return ExecutionContext.CLIENT;
        }

        @Override
        public JsonNode execute(JsonNode params, ToolContext context) {
            throw new AssertionError("CLIENT tool should be dispatched over WebSocket");
        }
    }
}
