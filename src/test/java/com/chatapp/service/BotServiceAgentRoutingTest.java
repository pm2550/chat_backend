package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.AgentTask;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomBot;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.AgentTaskRepository;
import com.chatapp.repository.BotAllowedUserRepository;
import com.chatapp.repository.BotConfigRepository;
import com.chatapp.repository.ChatRoomBotRepository;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.tool.AgentToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase-0 unification: a tool-enabled room bot (explicit enabled_tools whitelist) is
 * routed through the multi-turn AgentExecutionLoop instead of the one-shot LLM call,
 * and is gated by the per-(room,bot) rate limiter.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BotService agent-loop routing")
class BotServiceAgentRoutingTest {

    @Mock private BotConfigRepository botConfigRepository;
    @Mock private BotAllowedUserRepository botAllowedUserRepository;
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
    @Mock private BotVisionAttachmentSelector botVisionAttachmentSelector;
    @Mock private ObjectProvider<AgentExecutionLoop> agentExecutionLoopProvider;
    @Mock private AgentExecutionLoop agentExecutionLoop;

    @InjectMocks private BotService service;

    private User alice;
    private BotConfig bot;
    private ChatRoom room;
    private ChatRoomBot crb;

    @BeforeEach
    void setUp() {
        alice = new User();
        alice.setId(1L);
        alice.setUsername("alice");

        bot = new BotConfig();
        bot.setId(10L);
        bot.setBotName("Searcher");
        bot.setLlmProvider(BotConfig.LLMProvider.DASHSCOPE);
        bot.setEnabledTools("[\"web_search\"]");
        bot.setCreatedBy(alice);

        room = new ChatRoom();
        room.setId(100L);
        room.setName("test-room");
        room.setCreatedBy(alice);

        crb = new ChatRoomBot();
        crb.setBotConfig(bot);
        crb.setTriggerMode(ChatRoomBot.TriggerMode.ALL);
    }

    @Test
    @DisplayName("tool-enabled bot runs the agent loop, not the one-shot chat")
    void routesToolEnabledBotThroughAgentLoop() {
        when(chatRoomBotRepository.findActiveBotsWithConfig(100L)).thenReturn(List.of(crb));
        when(agentToolRegistry.hasExplicitToolWhitelist(bot)).thenReturn(true);
        when(botRateLimitService.tryAcquireAgentRun(100L, 10L)).thenReturn(true);
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(agentTaskRepository.save(any(AgentTask.class))).thenAnswer(inv -> {
            AgentTask t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(500L);
            }
            return t;
        });
        when(agentContextBuilder.buildContext(any(AgentTask.class))).thenReturn(null);
        when(agentExecutionLoopProvider.getObject()).thenReturn(agentExecutionLoop);
        when(agentExecutionLoop.runLoop(any(), any())).thenReturn(new AgentExecutionLoop.AgentLoopResult(
                "final answer from loop",
                1,
                List.of(),
                AgentExecutionLoop.TerminationReason.FINAL_ANSWER,
                new AgentExecutionLoop.BudgetSnapshot(0, 0)));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Message> result = service.processMessageForBots(100L, "search the news", 1L);

        // One-shot path was NOT used.
        verify(llmService, never()).chat(any(), any());
        verify(agentExecutionLoopProvider).getObject();
        // The loop's answer was posted as the bot message.
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        assertEquals("final answer from loop", captor.getValue().getContent());
        assertEquals(bot, captor.getValue().getBotConfig());
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("webhook-subscribed bot forwards externally and skips the LLM")
    void webhookSubscribedBotForwardsAndSkipsLlm() {
        when(chatRoomBotRepository.findActiveBotsWithConfig(100L)).thenReturn(List.of(crb));
        when(botWebhookService.dispatchIfSubscribed(eq(bot), eq(100L), any(), eq(1L))).thenReturn(true);

        List<Message> result = service.processMessageForBots(100L, "hey bot", 1L);

        verify(botWebhookService).dispatchIfSubscribed(eq(bot), eq(100L), any(), eq(1L));
        verify(llmService, never()).chat(any(), any());
        verify(agentExecutionLoopProvider, never()).getObject();
        verify(messageRepository, never()).save(any()); // external bot replies via the inbound gateway
        assertEquals(0, result.size());
    }

    @Test
    @DisplayName("rate-limited agent run posts a visible retry message and never invokes the loop")
    void rateLimitedAgentRunIsVisible() {
        when(chatRoomBotRepository.findActiveBotsWithConfig(100L)).thenReturn(List.of(crb));
        when(agentToolRegistry.hasExplicitToolWhitelist(bot)).thenReturn(true);
        when(botRateLimitService.tryAcquireAgentRun(100L, 10L)).thenReturn(false);
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Message> result = service.processMessageForBots(100L, "search the news", 1L);

        verify(agentExecutionLoopProvider, never()).getObject();
        verify(llmService, never()).chat(any(), any());
        verify(messageRepository).save(any(Message.class));
        assertEquals(1, result.size());
        assertEquals("⚠️ 请求太密集，我需要缓一下。请稍等几秒再试。", result.get(0).getContent());
    }

    @Test
    @DisplayName("a markdown-shaped bot reply is sanitized and marked MARKDOWN")
    void markdownBotReplyGetsContentFormat() {
        when(chatRoomBotRepository.findActiveBotsWithConfig(100L)).thenReturn(List.of(crb));
        when(agentToolRegistry.hasExplicitToolWhitelist(bot)).thenReturn(false);
        String md = "| a | b |\n|---|---|\n| 1 | 2 |";
        when(llmService.chat(eq(bot), any())).thenReturn(new BotDto.LLMResponse(md, 5, "m"));
        when(richContentSanitizer.sanitizeMarkdown(md)).thenReturn(md);
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        service.processMessageForBots(100L, "table please", 1L);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        assertEquals(Message.ContentFormat.MARKDOWN, captor.getValue().getContentFormat());
    }

    @Test
    @DisplayName("createBot persists enabled_tools and exposes them in the DTO")
    void createBotPersistsEnabledTools() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(botConfigRepository.save(any(BotConfig.class))).thenAnswer(inv -> {
            BotConfig b = inv.getArgument(0);
            b.setId(7L);
            return b;
        });

        BotDto.CreateRequest req = new BotDto.CreateRequest();
        req.setBotName("Searcher");
        req.setLlmProvider(BotConfig.LLMProvider.DASHSCOPE);
        req.setEnabledTools(List.of("web_search"));

        BotDto dto = service.createBot(1L, req);

        assertEquals(List.of("web_search"), dto.getEnabledTools());
    }

    @Test
    @DisplayName("bot without tool whitelist keeps the one-shot path")
    void nonToolBotUsesOneShot() {
        when(chatRoomBotRepository.findActiveBotsWithConfig(100L)).thenReturn(List.of(crb));
        when(agentToolRegistry.hasExplicitToolWhitelist(bot)).thenReturn(false);
        when(llmService.chat(eq(bot), any())).thenReturn(new BotDto.LLMResponse("one-shot reply", 5, "m"));
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        service.processMessageForBots(100L, "hello", 1L);

        verify(llmService).chat(eq(bot), any());
        verify(agentExecutionLoopProvider, never()).getObject();
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        assertEquals("one-shot reply", captor.getValue().getContent());
    }
}
