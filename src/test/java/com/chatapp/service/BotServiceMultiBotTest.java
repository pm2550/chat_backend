package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.AgentTask;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomBot;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.AgentTaskRepository;
import com.chatapp.repository.BotConfigRepository;
import com.chatapp.repository.ChatRoomBotRepository;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.AgentExecutionLoop.AgentLoopResult;
import com.chatapp.service.AgentExecutionLoop.BudgetSnapshot;
import com.chatapp.service.AgentExecutionLoop.TerminationReason;
import com.chatapp.service.tool.AgentToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotServiceMultiBotTest {

    @Mock private BotConfigRepository botConfigRepository;
    @Mock private ChatRoomBotRepository chatRoomBotRepository;
    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private UserRepository userRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private LLMService llmService;
    @Mock private ProviderCredentialService providerCredentialService;
    @Mock private AgentToolRegistry agentToolRegistry;
    @Mock private AgentContextBuilder agentContextBuilder;
    @Mock private AgentTaskRepository agentTaskRepository;
    @Mock private BotRateLimitService botRateLimitService;
    @Mock private RichContentSanitizer richContentSanitizer;
    @Mock private BotWebhookService botWebhookService;
    @Mock private ObjectProvider<AgentExecutionLoop> agentExecutionLoopProvider;
    @Mock private AgentExecutionLoop agentExecutionLoop;

    @InjectMocks private BotService service;

    private User alice;
    private ChatRoom roomA;
    private ChatRoom roomB;
    private ChatRoom roomC;
    private BotConfig auroraConfig;
    private BotConfig echoConfig;
    private ChatRoomBot auroraInA;
    private ChatRoomBot auroraInC;
    private ChatRoomBot echoInB;
    private ChatRoomBot echoInC;

    @BeforeEach
    void setUp() {
        alice = new User();
        alice.setId(1L);
        alice.setUsername("alice");

        roomA = room(200L);
        roomB = room(201L);
        roomC = room(202L);

        auroraConfig = bot(10L, "Aurora", "[\"read_recent_messages\"]",
                "Aurora persona - analytical");
        echoConfig = bot(11L, "Echo", null, "Echo persona - poetic");

        auroraInA = bind(roomA, auroraConfig);
        auroraInC = bind(roomC, auroraConfig);
        echoInB = bind(roomB, echoConfig);
        echoInC = bind(roomC, echoConfig);
    }

    @Test
    void mentioning_tool_enabled_bot_runs_agent_loop() {
        when(agentExecutionLoopProvider.getObject()).thenReturn(agentExecutionLoop);
        when(chatRoomBotRepository.findActiveBotsWithConfig(200L)).thenReturn(List.of(auroraInA));
        when(agentToolRegistry.hasExplicitToolWhitelist(auroraConfig)).thenReturn(true);
        when(botRateLimitService.tryAcquireAgentRun(200L, 10L)).thenReturn(true);
        when(chatRoomRepository.findById(200L)).thenReturn(Optional.of(roomA));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(agentTaskRepository.save(any(AgentTask.class))).thenAnswer(inv -> {
            AgentTask task = inv.getArgument(0);
            task.setId(7777L);
            return task;
        });
        when(agentContextBuilder.buildContext(any(AgentTask.class))).thenReturn(null);
        when(agentExecutionLoop.runLoop(any(), any())).thenReturn(new AgentLoopResult(
                "aurora answer",
                1,
                List.of(),
                TerminationReason.FINAL_ANSWER,
                new BudgetSnapshot(0, 0)));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Message> out = service.processMessageForBots(200L, "@Aurora summarize", 1L);

        verify(agentExecutionLoop, times(1)).runLoop(any(), any());
        verify(llmService, never()).chat(any(), any());
        ArgumentCaptor<Message> cap = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, atLeastOnce()).save(cap.capture());
        Message saved = cap.getAllValues().stream()
                .filter(m -> m.getBotConfig() != null && m.getBotConfig().getId().equals(10L))
                .findFirst()
                .orElseThrow();
        assertEquals("aurora answer", saved.getContent());
        assertEquals(auroraConfig, saved.getBotConfig());
        assertEquals(1, out.size());
    }

    @Test
    void mentioning_persona_bot_runs_one_shot() {
        when(chatRoomBotRepository.findActiveBotsWithConfig(201L)).thenReturn(List.of(echoInB));
        when(agentToolRegistry.hasExplicitToolWhitelist(echoConfig)).thenReturn(false);
        when(chatRoomRepository.findById(201L)).thenReturn(Optional.of(roomB));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(llmService.chat(eq(echoConfig), any()))
                .thenReturn(new BotDto.LLMResponse("echo poem", 7, "echo-model"));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Message> out = service.processMessageForBots(201L, "@Echo poem about rain", 1L);

        verify(llmService, times(1)).chat(eq(echoConfig), any());
        verify(agentExecutionLoopProvider, never()).getObject();
        verify(agentExecutionLoop, never()).runLoop(any(), any());

        ArgumentCaptor<Message> cap = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, atLeastOnce()).save(cap.capture());
        Message saved = cap.getAllValues().stream()
                .filter(m -> m.getBotConfig() != null && m.getBotConfig().getId().equals(11L))
                .findFirst()
                .orElseThrow();
        assertEquals("echo poem", saved.getContent());
        assertEquals(echoConfig, saved.getBotConfig());
        assertEquals("Echo", saved.getBotDisplayName());
        assertEquals(1, out.size());
    }

    @Test
    void each_bot_uses_own_persona_character_card() {
        when(agentExecutionLoopProvider.getObject()).thenReturn(agentExecutionLoop);
        when(chatRoomBotRepository.findActiveBotsWithConfig(202L))
                .thenReturn(List.of(auroraInC, echoInC));
        when(agentToolRegistry.hasExplicitToolWhitelist(auroraConfig)).thenReturn(true);
        when(agentToolRegistry.hasExplicitToolWhitelist(echoConfig)).thenReturn(false);
        when(botRateLimitService.tryAcquireAgentRun(202L, 10L)).thenReturn(true);
        when(chatRoomRepository.findById(202L)).thenReturn(Optional.of(roomC));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(agentTaskRepository.save(any(AgentTask.class))).thenAnswer(inv -> {
            AgentTask task = inv.getArgument(0);
            task.setId(8888L);
            return task;
        });
        when(agentContextBuilder.buildContext(any(AgentTask.class))).thenReturn(null);
        when(agentExecutionLoop.runLoop(any(), any())).thenReturn(new AgentLoopResult(
                "aurora reply",
                1,
                List.of(),
                TerminationReason.FINAL_ANSWER,
                new BudgetSnapshot(0, 0)));
        when(llmService.chat(eq(echoConfig), any()))
                .thenReturn(new BotDto.LLMResponse("echo reply", 5, "echo-model"));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        service.processMessageForBots(202L, "@Aurora hi @Echo hi", 1L);

        ArgumentCaptor<AgentTask> taskCap = ArgumentCaptor.forClass(AgentTask.class);
        verify(agentExecutionLoop, times(1)).runLoop(taskCap.capture(), any());
        BotConfig loopConfig = taskCap.getValue().getBotConfig();
        assertEquals(auroraConfig.getId(), loopConfig.getId());
        assertTrue(loopConfig.getCharacterPersona().contains("Aurora persona"),
                "loop must receive Aurora's character card, not Echo's");

        ArgumentCaptor<BotConfig> chatCap = ArgumentCaptor.forClass(BotConfig.class);
        verify(llmService, times(1)).chat(chatCap.capture(), any());
        BotConfig oneShotConfig = chatCap.getValue();
        assertEquals(echoConfig.getId(), oneShotConfig.getId());
        assertTrue(oneShotConfig.getCharacterPersona().contains("Echo persona"),
                "one-shot must receive Echo's character card, not Aurora's");
    }

    @Test
    void mentioning_bot_not_in_room_is_silent_noop() {
        when(chatRoomBotRepository.findActiveBotsWithConfig(200L)).thenReturn(List.of(auroraInA));

        List<Message> out = service.processMessageForBots(200L, "@Echo are you there?", 1L);

        verify(agentExecutionLoop, never()).runLoop(any(), any());
        verify(llmService, never()).chat(any(), any());
        verify(messageRepository, never()).save(any());
        verify(agentExecutionLoopProvider, never()).getObject();
        verify(agentTaskRepository, never()).save(any());
        assertTrue(out.isEmpty(), "no bot in this room may forge a reply for @<other-room bot>");
    }

    private ChatRoom room(Long id) {
        ChatRoom room = new ChatRoom();
        room.setId(id);
        room.setCreatedBy(alice);
        return room;
    }

    private BotConfig bot(Long id, String name, String enabledTools, String persona) {
        BotConfig config = new BotConfig();
        config.setId(id);
        config.setBotName(name);
        config.setCreatedBy(alice);
        config.setLlmProvider(BotConfig.LLMProvider.DASHSCOPE);
        config.setEnabledTools(enabledTools);
        config.setCharacterPersona(persona);
        return config;
    }

    private ChatRoomBot bind(ChatRoom room, BotConfig config) {
        ChatRoomBot crb = new ChatRoomBot();
        crb.setChatRoom(room);
        crb.setBotConfig(config);
        crb.setIsActive(true);
        crb.setEnabledInRoom(true);
        crb.setTriggerMode(ChatRoomBot.TriggerMode.MENTION);
        crb.setRoomNickname(null);
        return crb;
    }
}
