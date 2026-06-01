package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.AgentTask;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.User;
import com.chatapp.service.tool.AgentToolRegistry;
import com.chatapp.service.tool.Tool;
import com.chatapp.service.tool.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentExecutionLoopTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private LLMService llmService;
    private AgentToolRegistry registry;
    private AgentContextBuilder contextBuilder;
    private AgentExecutionLoop loop;
    private Tool echoTool;
    private BotConfig bot;
    private AgentTask task;
    private AgentContextBuilder.AgentContextEnvelope envelope;

    @BeforeEach
    void setUp() {
        llmService = mock(LLMService.class);
        registry = mock(AgentToolRegistry.class);
        contextBuilder = mock(AgentContextBuilder.class);
        loop = new AgentExecutionLoop(llmService, registry, contextBuilder, objectMapper);
        echoTool = new EchoTool();
        bot = new BotConfig();
        bot.setId(99L);
        bot.setBotName("Agent");
        bot.setMaxAgentIterations(8);
        bot.setMaxAgentWallclockMs(30000);
        bot.setMaxAgentTotalTokens(50000);
        task = task(bot);
        envelope = new AgentContextBuilder.AgentContextEnvelope(
                new AgentContextBuilder.AgentIdentity("Agent", "", "base", null),
                new AgentContextBuilder.RoomMetadata(true, "Room", "", 1, List.of("Alice"), null),
                List.of(),
                new AgentContextBuilder.InitiatorInfo("Alice", "member", false),
                List.of("Be concise"),
                "please help",
                6000,
                20);

        when(contextBuilder.assembleSystemPrompt(envelope)).thenReturn("system prompt");
        when(contextBuilder.estimateTokens(any())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return text == null ? 0 : Math.max(1, text.length() / 4);
        });
        when(registry.listToolsForBot(bot)).thenReturn(List.of(echoTool));
        when(registry.getTool("echo")).thenReturn(Optional.of(echoTool));
    }

    @Test
    void finalAnswerTerminatesAtFirstIteration() {
        when(llmService.chat(any(BotConfig.class), anyList(), anyList()))
                .thenReturn(new BotDto.LLMResponse("done", 5, "m"));

        AgentExecutionLoop.AgentLoopResult result = loop.runLoop(task, envelope);

        assertEquals("done", result.finalContent());
        assertEquals(1, result.iterations());
        assertEquals(AgentExecutionLoop.TerminationReason.FINAL_ANSWER, result.terminationReason());
    }

    @Test
    void toolCallExecutesAndSecondLlmAnswerIsFinal() {
        when(llmService.chat(any(BotConfig.class), anyList(), anyList()))
                .thenReturn(new BotDto.LLMResponse("", 5, "m",
                        List.of(new BotDto.ToolCall("call-1", "echo", "{\"value\":\"hello\"}"))))
                .thenReturn(new BotDto.LLMResponse("tool said hello", 7, "m"));

        AgentExecutionLoop.AgentLoopResult result = loop.runLoop(task, envelope);

        assertEquals("tool said hello", result.finalContent());
        assertEquals(2, result.iterations());
        assertEquals(1, result.toolCallsMade().size());
        verify(llmService, times(2)).chat(any(BotConfig.class), anyList(), anyList());
    }

    @Test
    void iterationBudgetStopsRunawayToolLoop() {
        bot.setMaxAgentIterations(2);
        when(llmService.chat(any(BotConfig.class), anyList(), anyList()))
                .thenReturn(new BotDto.LLMResponse("", 5, "m",
                        List.of(new BotDto.ToolCall("call-1", "echo", "{\"value\":\"again\"}"))));

        AgentExecutionLoop.AgentLoopResult result = loop.runLoop(task, envelope);

        assertTrue(result.finalContent().contains("stopped: budget exhausted"));
        assertEquals(AgentExecutionLoop.TerminationReason.ITERATION_BUDGET, result.terminationReason());
        verify(llmService, times(2)).chat(any(BotConfig.class), anyList(), anyList());
    }

    @Test
    void tokenBudgetStopsOnServerSideCap() {
        bot.setMaxAgentTotalTokens(2);
        when(llmService.chat(any(BotConfig.class), anyList(), anyList()))
                .thenReturn(new BotDto.LLMResponse("this is too long for the budget", 100, "m"));

        AgentExecutionLoop.AgentLoopResult result = loop.runLoop(task, envelope);

        assertEquals(AgentExecutionLoop.TerminationReason.TOKEN_BUDGET, result.terminationReason());
        assertTrue(result.finalContent().contains("stopped: budget exhausted"));
    }

    @Test
    void toolErrorIsWrappedAndLoopContinues() {
        when(registry.getTool("missing")).thenReturn(Optional.empty());
        when(llmService.chat(any(BotConfig.class), anyList(), anyList()))
                .thenReturn(new BotDto.LLMResponse("", 5, "m",
                        List.of(new BotDto.ToolCall("call-1", "missing", "{}"))))
                .thenReturn(new BotDto.LLMResponse("handled error", 7, "m"));

        AgentExecutionLoop.AgentLoopResult result = loop.runLoop(task, envelope);

        assertEquals("handled error", result.finalContent());
        assertTrue(result.toolCallsMade().get(0).error());
        verify(llmService, times(2)).chat(any(BotConfig.class), anyList(), anyList());
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

    private class EchoTool implements Tool {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "echo test tool";
        }

        @Override
        public JsonNode parametersSchema() {
            return objectMapper.createObjectNode().put("type", "object");
        }

        @Override
        public JsonNode execute(JsonNode params, ToolContext context) {
            return objectMapper.createObjectNode().put("value", params.path("value").asText());
        }
    }
}
