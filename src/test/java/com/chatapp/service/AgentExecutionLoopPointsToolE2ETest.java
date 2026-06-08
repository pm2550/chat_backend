package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.dto.PointsDto;
import com.chatapp.entity.AgentTask;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.User;
import com.chatapp.service.tool.AgentToolDispatcher;
import com.chatapp.service.tool.AgentToolRegistry;
import com.chatapp.service.tool.PointsBalanceTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentExecutionLoopPointsToolE2ETest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void pointsToolReceivesInitiatingUserIdNotBotConfigId() {
        PointsService pointsService = mock(PointsService.class);
        Map<String, Integer> free = new LinkedHashMap<>();
        free.put("ai_image_gen", 3);
        when(pointsService.getBalance(42L))
                .thenReturn(new PointsDto.BalanceResponse(free, 88));

        PointsBalanceTool pointsTool = new PointsBalanceTool(pointsService, objectMapper);
        AgentToolRegistry registry = new AgentToolRegistry(List.of(pointsTool), objectMapper);
        AgentToolDispatcher dispatcher = new AgentToolDispatcher(null, null, objectMapper);
        LLMService llmService = mock(LLMService.class);
        AgentContextBuilder contextBuilder = mock(AgentContextBuilder.class);
        AgentExecutionLoop loop = new AgentExecutionLoop(
                llmService, registry, dispatcher, contextBuilder, objectMapper);

        AgentContextBuilder.AgentContextEnvelope envelope = envelope();
        when(contextBuilder.assembleSystemPrompt(envelope)).thenReturn("system prompt");
        when(contextBuilder.estimateTokens(any())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return text == null ? 0 : Math.max(1, text.length() / 4);
        });
        when(llmService.chat(any(BotConfig.class), anyList(), anyList()))
                .thenReturn(new BotDto.LLMResponse("", 5, "m",
                        List.of(new BotDto.ToolCall("call-1", "lookup_my_points_balance", "{}"))))
                .thenReturn(new BotDto.LLMResponse("You have 88 points.", 7, "m"));

        AgentExecutionLoop.AgentLoopResult result = loop.runLoop(task(), envelope);

        assertEquals("You have 88 points.", result.finalContent());
        assertEquals(1, result.toolCallsMade().size());
        verify(pointsService).getBalance(42L);
        verify(pointsService, never()).getBalance(99L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BotDto.ChatMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(2)).chat(any(BotConfig.class), messagesCaptor.capture(), anyList());
        List<BotDto.ChatMessage> secondCall = messagesCaptor.getAllValues().get(1);
        assertTrue(secondCall.stream()
                .anyMatch(message -> "tool".equals(message.getRole())
                        && String.valueOf(message.getContent()).contains("\"paid_points\":88")));
    }

    private AgentTask task() {
        BotConfig bot = new BotConfig();
        bot.setId(99L);
        bot.setBotName("Agent");
        bot.setEnabledTools("[\"lookup_my_points_balance\"]");
        bot.setMaxAgentIterations(4);
        bot.setMaxAgentWallclockMs(30000);
        bot.setMaxAgentTotalTokens(50000);

        User caller = new User();
        caller.setId(42L);
        caller.setUsername("caller");

        ChatRoom room = new ChatRoom();
        room.setId(10L);

        AgentTask task = new AgentTask();
        task.setId(77L);
        task.setPrompt("我现在有多少积分");
        task.setRequestedBy(caller);
        task.setChatRoom(room);
        task.setBotConfig(bot);
        return task;
    }

    private AgentContextBuilder.AgentContextEnvelope envelope() {
        return new AgentContextBuilder.AgentContextEnvelope(
                new AgentContextBuilder.AgentIdentity("Agent", "", "base", null),
                new AgentContextBuilder.RoomMetadata(true, "Room", "", 1, List.of("Caller"), null, true),
                List.of(),
                new AgentContextBuilder.InitiatorInfo("Caller", "member", false),
                List.of("Be concise"),
                "我现在有多少积分",
                6000,
                20);
    }
}
