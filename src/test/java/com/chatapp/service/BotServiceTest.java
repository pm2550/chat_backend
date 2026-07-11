package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.AgentTask;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomBot;
import com.chatapp.entity.Message;
import com.chatapp.entity.ProviderCredential;
import com.chatapp.entity.User;
import com.chatapp.repository.BotConfigRepository;
import com.chatapp.repository.BotAllowedUserRepository;
import com.chatapp.repository.ChatRoomBotRepository;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.repository.AgentTaskRepository;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BotService")
class BotServiceTest {

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
    @Mock private BotWebhookService botWebhookService;
    @Mock private FileStorageService fileStorageService;
    @Mock private ObjectProvider<AgentExecutionLoop> agentExecutionLoopProvider;

    @InjectMocks private BotService service;

    private User alice;
    private BotConfig bot;
    private ChatRoom room;

    @BeforeEach
    void setUp() {
        alice = new User();
        alice.setId(1L);
        alice.setUsername("alice");

        bot = new BotConfig();
        bot.setId(10L);
        bot.setBotName("GPT-Helper");
        bot.setLlmProvider(BotConfig.LLMProvider.OPENAI);
        bot.setModelName("gpt-4o");
        bot.setSystemPrompt("You are helpful");
        bot.setTemperature(0.7);
        bot.setMaxTokens(2048);
        bot.setIsActive(true);
        bot.setCreatedBy(alice);

        room = new ChatRoom();
        room.setId(100L);
        room.setName("test-room");
        room.setRoomType(ChatRoom.RoomType.GROUP);
        room.setCreatedBy(alice);

        lenient().when(agentContextBuilder.buildContext(any(AgentTask.class)))
                .thenAnswer(inv -> {
                    AgentTask task = inv.getArgument(0);
                    return new AgentContextBuilder.AgentContextEnvelope(
                            new AgentContextBuilder.AgentIdentity(
                                    task.getBotConfig().getBotName(), null,
                                    task.getBotConfig().getSystemPrompt(),
                                    task.getBotConfig().getSystemPromptTemplate()),
                            new AgentContextBuilder.RoomMetadata(
                                    true,
                                    task.getChatRoom() != null ? task.getChatRoom().getName() : "",
                                    "",
                                    2,
                                    List.of("alice", "bob"),
                                    null,
                                    true),
                            List.of(new AgentContextBuilder.HistoricalMessage(
                                    "alice", "TEXT", "previous room message", "2026-07-01 00:00:00 UTC")),
                            new AgentContextBuilder.InitiatorInfo("alice", "MEMBER", true),
                            List.of("Use room context."),
                            task.getPrompt(),
                            6000,
                            120);
                });
        lenient().when(agentContextBuilder.assembleSystemPrompt(any(AgentContextBuilder.AgentContextEnvelope.class)))
                .thenAnswer(inv -> {
                    AgentContextBuilder.AgentContextEnvelope env = inv.getArgument(0);
                    return "Room: " + env.roomMetadata().name()
                            + "\nMembers (" + env.roomMetadata().memberCount() + "): "
                            + String.join(", ", env.roomMetadata().memberNames())
                            + "\nRecent: " + env.conversationHistory().get(0).content()
                            + "\nTask: " + env.taskText();
                });
    }

    @Test
    @DisplayName("createBot saves and returns DTO with default temperature/maxTokens")
    void create_defaults() {
        BotDto.CreateRequest req = new BotDto.CreateRequest();
        req.setBotName("NewBot");
        req.setLlmProvider(BotConfig.LLMProvider.CLAUDE);
        req.setApiKey("secret-key");
        req.setModelName("claude-sonnet");
        req.setSystemPrompt("helpful");
        // temperature/maxTokens intentionally null
        ProviderCredential credential = new ProviderCredential();
        credential.setId(99L);
        credential.setLlmProvider(BotConfig.LLMProvider.CLAUDE);
        credential.setLabel("NewBot CLAUDE key");
        credential.setSecretLast4("-key");
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(providerCredentialService.createForBot(eq(1L), eq(BotConfig.LLMProvider.CLAUDE), anyString(), eq("secret-key")))
                .thenReturn(credential);
        when(botConfigRepository.save(any(BotConfig.class)))
                .thenAnswer(inv -> { ((BotConfig) inv.getArgument(0)).setId(11L); return inv.getArgument(0); });

        BotDto dto = service.createBot(1L, req);

        assertNotNull(dto);
        assertEquals("NewBot", dto.getBotName());
        assertEquals(BotConfig.LLMProvider.CLAUDE, dto.getLlmProvider());

        ArgumentCaptor<BotConfig> captor = ArgumentCaptor.forClass(BotConfig.class);
        verify(botConfigRepository).save(captor.capture());
        assertEquals(0.7, captor.getValue().getTemperature()); // default
        assertEquals(2048, captor.getValue().getMaxTokens()); // default
        assertEquals(BotConfig.WorkflowMode.SINGLE_PASS, captor.getValue().getWorkflowMode());
        assertNull(captor.getValue().getApiKeyEncrypted());
        assertEquals(credential, captor.getValue().getProviderCredential());
    }

    @Test
    @DisplayName("createBot stores a NovelAI key in the credential vault")
    void create_with_novelai_image_provider() {
        BotDto.CreateRequest req = new BotDto.CreateRequest();
        req.setBotName("Novel Draw");
        req.setLlmProvider(BotConfig.LLMProvider.OPENAI);
        req.setImageGenerationProvider(BotConfig.ImageGenerationProvider.NOVELAI);
        req.setImageApiKey("novel-secret");
        req.setImageBaseUrl("https://image.novelai.net/ai/generate-image");
        req.setImageModel("nai-diffusion-3");
        ProviderCredential credential = new ProviderCredential();
        credential.setId(120L);
        credential.setLlmProvider(BotConfig.LLMProvider.NOVELAI);
        credential.setLabel("Novel Draw image key");
        credential.setSecretLast4("cret");
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(providerCredentialService.createForBot(
                eq(1L),
                eq(BotConfig.LLMProvider.NOVELAI),
                anyString(),
                eq("novel-secret"),
                eq("https://image.novelai.net/ai/generate-image"),
                eq("nai-diffusion-3")))
                .thenReturn(credential);
        when(botConfigRepository.save(any(BotConfig.class))).thenAnswer(invocation -> {
            BotConfig saved = invocation.getArgument(0);
            saved.setId(33L);
            return saved;
        });

        BotDto dto = service.createBot(1L, req);

        ArgumentCaptor<BotConfig> captor = ArgumentCaptor.forClass(BotConfig.class);
        verify(botConfigRepository).save(captor.capture());
        assertEquals(BotConfig.ImageGenerationProvider.NOVELAI,
                captor.getValue().getImageGenerationProvider());
        assertEquals(credential, captor.getValue().getImageProviderCredential());
        assertEquals("nai-diffusion-3", captor.getValue().getImageModel());
        assertEquals(120L, dto.getImageProviderCredentialId());
        assertTrue(dto.getHasImageProviderCredential());
    }

    @Test
    @DisplayName("createBot rejects when creator missing")
    void create_unknown_creator() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        BotDto.CreateRequest req = new BotDto.CreateRequest();
        req.setBotName("x");
        req.setLlmProvider(BotConfig.LLMProvider.OPENAI);
        assertThrows(RuntimeException.class, () -> service.createBot(99L, req));
    }

    @Test
    @DisplayName("updateBot merges non-null fields only")
    void update_partial() {
        when(botConfigRepository.findById(10L)).thenReturn(Optional.of(bot));
        when(botConfigRepository.save(any(BotConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        BotDto.UpdateRequest req = new BotDto.UpdateRequest();
        req.setTemperature(0.2);
        req.setIsActive(false);
        // other fields null

        service.updateBot(10L, alice.getId(), req);
        assertEquals(0.2, bot.getTemperature());
        assertEquals(false, bot.getIsActive());
        // unchanged
        assertEquals("gpt-4o", bot.getModelName());
        assertEquals("GPT-Helper", bot.getBotName());
    }

    @Test
    @DisplayName("updateBot forbids non-owner credential changes")
    void update_non_owner_forbidden() {
        when(botConfigRepository.findById(10L)).thenReturn(Optional.of(bot));
        BotDto.UpdateRequest req = new BotDto.UpdateRequest();
        req.setProviderCredentialId(123L);

        assertThrows(IllegalArgumentException.class,
                () -> service.updateBot(10L, 999L, req));
        verify(providerCredentialService, never()).getOwnedCredential(any(), any());
        verify(botConfigRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateBotAvatar stores the image and updates the owner's bot")
    void update_avatar() throws Exception {
        MockMultipartFile avatar = new MockMultipartFile(
                "avatar", "bot.png", "image/png", new byte[] {1, 2, 3});
        when(botConfigRepository.findById(10L)).thenReturn(Optional.of(bot));
        when(fileStorageService.uploadAvatar(avatar)).thenReturn("/api/files/avatar/bot.png");
        when(botConfigRepository.save(bot)).thenReturn(bot);

        BotDto dto = service.updateBotAvatar(10L, alice.getId(), avatar);

        assertEquals("/api/files/avatar/bot.png", dto.getBotAvatar());
        verify(fileStorageService).uploadAvatar(avatar);
        verify(botConfigRepository).save(bot);
    }

    @Test
    @DisplayName("updateBotAvatar rejects a non-owner before storing bytes")
    void update_avatar_non_owner_forbidden() {
        MockMultipartFile avatar = new MockMultipartFile(
                "avatar", "bot.png", "image/png", new byte[] {1, 2, 3});
        when(botConfigRepository.findById(10L)).thenReturn(Optional.of(bot));

        assertThrows(AccessDeniedException.class,
                () -> service.updateBotAvatar(10L, 999L, avatar));

        verifyNoInteractions(fileStorageService);
        verify(botConfigRepository, never()).save(any());
    }

    @Test
    @DisplayName("getMyBots returns dtos for user")
    void get_my_bots() {
        when(botConfigRepository.findByCreatedById(1L)).thenReturn(List.of(bot));
        List<BotDto> dtos = service.getMyBots(1L);
        assertEquals(1, dtos.size());
        assertEquals("GPT-Helper", dtos.get(0).getBotName());
    }

    @Test
    @DisplayName("getBotsInChatRoom uses fetched bot configs")
    void get_bots_in_chat_room() {
        ChatRoomBot crb = new ChatRoomBot();
        crb.setChatRoom(room);
        crb.setBotConfig(bot);
        crb.setIsActive(true);
        when(chatRoomBotRepository.findActiveBotsWithConfig(100L)).thenReturn(List.of(crb));

        List<BotDto> dtos = service.getBotsInChatRoom(100L);

        assertEquals(1, dtos.size());
        assertEquals("GPT-Helper", dtos.get(0).getBotName());
        verify(chatRoomBotRepository).findActiveBotsWithConfig(100L);
    }

    @Test
    @DisplayName("addBotToChatRoom persists ChatRoomBot with default trigger MENTION")
    void add_default_mention() {
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(botConfigRepository.findById(10L)).thenReturn(Optional.of(bot));
        when(chatRoomBotRepository.findByChatRoomIdAndBotConfigId(100L, 10L)).thenReturn(Optional.empty());

        service.addBotToChatRoom(100L, 10L, null);

        ArgumentCaptor<ChatRoomBot> captor = ArgumentCaptor.forClass(ChatRoomBot.class);
        verify(chatRoomBotRepository).save(captor.capture());
        assertEquals(ChatRoomBot.TriggerMode.MENTION, captor.getValue().getTriggerMode());
    }

    @Test
    @DisplayName("addBotToChatRoom rejects duplicate add")
    void add_duplicate_rejected() {
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(botConfigRepository.findById(10L)).thenReturn(Optional.of(bot));
        when(chatRoomBotRepository.findByChatRoomIdAndBotConfigId(100L, 10L))
                .thenReturn(Optional.of(new ChatRoomBot()));
        assertThrows(IllegalArgumentException.class,
                () -> service.addBotToChatRoom(100L, 10L, null));
    }

    @Test
    @DisplayName("removeBotFromChatRoom delegates to repo")
    void remove_bot_from_room() {
        service.removeBotFromChatRoom(100L, 10L);
        verify(chatRoomBotRepository).deleteByChatRoomIdAndBotConfigId(100L, 10L);
    }

    @Test
    @DisplayName("deleteBot forbids non-owner")
    void delete_non_owner_forbidden() {
        when(botConfigRepository.findById(10L)).thenReturn(Optional.of(bot));
        assertThrows(IllegalArgumentException.class,
                () -> service.deleteBot(10L, 999L));
        verify(botConfigRepository, never()).delete(any());
    }

    @Test
    @DisplayName("deleteBot removes bot when owner matches")
    void delete_owner_ok() {
        when(botConfigRepository.findById(10L)).thenReturn(Optional.of(bot));
        service.deleteBot(10L, alice.getId());
        verify(botConfigRepository).delete(bot);
    }

    @Test
    @DisplayName("processMessageForBots triggers MENTION-mode bot when @-ed")
    void process_mention_trigger() {
        ChatRoomBot crb = new ChatRoomBot();
        crb.setBotConfig(bot);
        crb.setTriggerMode(ChatRoomBot.TriggerMode.MENTION);
        crb.setRoomNickname("Deploy Bot");
        when(chatRoomBotRepository.findActiveBotsWithConfig(100L)).thenReturn(List.of(crb));
        when(llmService.chat(any(BotConfig.class), any()))
                .thenReturn(new BotDto.LLMResponse("hello back", 42, "gpt-4o"));
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        service.processMessageForBots(100L, "@Deploy Bot what's up", 1L);

        verify(llmService).chat(eq(bot), any());
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        Message saved = captor.getValue();
        assertEquals("hello back", saved.getContent());
        assertEquals(Message.MessageType.TEXT, saved.getMessageType());
        assertEquals(bot, saved.getBotConfig());
        assertEquals("Deploy Bot", saved.getBotDisplayName());
    }

    @Test
    @DisplayName("processMessageForBots saves bot media URL replies as attachment messages")
    void process_media_url_reply() {
        ChatRoomBot crb = new ChatRoomBot();
        crb.setBotConfig(bot);
        crb.setTriggerMode(ChatRoomBot.TriggerMode.ALL);
        when(chatRoomBotRepository.findActiveBotsWithConfig(100L)).thenReturn(List.of(crb));
        when(llmService.chat(any(BotConfig.class), any()))
                .thenReturn(new BotDto.LLMResponse("done https://cdn.example.com/result.png", 7, "m"));
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        service.processMessageForBots(100L, "make image", 1L);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        Message saved = captor.getValue();
        assertEquals(Message.MessageType.IMAGE, saved.getMessageType());
        assertEquals("result.png", saved.getContent());
        assertEquals("https://cdn.example.com/result.png", saved.getFileUrl());
        assertEquals("result.png", saved.getFileName());
        assertEquals("image/png", saved.getFileType());
        assertEquals(bot, saved.getBotConfig());
    }

    @Test
    @DisplayName("processMessageForBots CHUNKED reply mode saves sentence bubbles")
    void process_chunked_reply_mode_saves_multiple_messages() {
        bot.setReplyMode(BotConfig.ReplyMode.CHUNKED);
        ChatRoomBot crb = new ChatRoomBot();
        crb.setBotConfig(bot);
        crb.setTriggerMode(ChatRoomBot.TriggerMode.ALL);
        when(chatRoomBotRepository.findActiveBotsWithConfig(100L)).thenReturn(List.of(crb));
        when(llmService.chat(any(BotConfig.class), any()))
                .thenReturn(new BotDto.LLMResponse("第一句。第二句！第三句？", 9, "m"));
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Message> replies = service.processMessageForBots(100L, "anything", 1L);

        assertEquals(3, replies.size());
        assertEquals("第一句。", replies.get(0).getContent());
        assertEquals("第二句！", replies.get(1).getContent());
        assertEquals("第三句？", replies.get(2).getContent());
        verify(messageRepository, times(3)).save(any(Message.class));
    }

    @Test
    @DisplayName("processMessageForBots CHUNKED reply mode treats Kirara break markers as bubbles")
    void process_chunked_reply_mode_splits_kirara_break_markers() {
        bot.setReplyMode(BotConfig.ReplyMode.CHUNKED);
        ChatRoomBot crb = new ChatRoomBot();
        crb.setBotConfig(bot);
        crb.setTriggerMode(ChatRoomBot.TriggerMode.ALL);
        when(chatRoomBotRepository.findActiveBotsWithConfig(100L)).thenReturn(List.of(crb));
        when(llmService.chat(any(BotConfig.class), any()))
                .thenReturn(new BotDto.LLMResponse("dds<break>可惜<break>笨笨高。", 9, "m"));
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Message> replies = service.processMessageForBots(100L, "anything", 1L);

        assertEquals(3, replies.size());
        assertEquals("dds", replies.get(0).getContent());
        assertEquals("可惜", replies.get(1).getContent());
        assertEquals("笨笨高。", replies.get(2).getContent());
        verify(messageRepository, times(3)).save(any(Message.class));
    }

    @Test
    @DisplayName("processMessageForBots KIRARA_TWO_PASS analyzes context before persona reply")
    void process_kirara_two_pass_runs_analysis_then_final_reply() {
        bot.setReplyMode(BotConfig.ReplyMode.CHUNKED);
        bot.setWorkflowMode(BotConfig.WorkflowMode.KIRARA_TWO_PASS);
        ChatRoomBot crb = new ChatRoomBot();
        crb.setBotConfig(bot);
        crb.setChatRoom(room);
        crb.setTriggerMode(ChatRoomBot.TriggerMode.ALL);
        when(chatRoomBotRepository.findActiveBotsWithConfig(100L)).thenReturn(List.of(crb));
        when(llmService.chat(eq(bot), any()))
                .thenReturn(
                        new BotDto.LLMResponse("{\"mention_only\":true,\"target_speaker\":\"alice\",\"target_message\":\"previous room message\",\"reply_intent\":\"接前文吐槽\",\"tone\":\"阿雷式损友\",\"avoid\":\"不要客服问候\"}", 31, "kimi-code"),
                        new BotDto.LLMResponse("死板？<break>我明明很活泼<break>只是你们太弱了。", 52, "kimi-code"));
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Message> replies = service.processMessageForBots(100L, "@阿雷", 1L);

        assertEquals(3, replies.size());
        assertEquals("死板？", replies.get(0).getContent());
        assertEquals("我明明很活泼", replies.get(1).getContent());
        assertEquals("只是你们太弱了。", replies.get(2).getContent());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BotDto.ChatMessage>> messagesCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(llmService, times(2)).chat(eq(bot), messagesCaptor.capture());
        List<BotDto.ChatMessage> analysisMessages = messagesCaptor.getAllValues().get(0);
        assertTrue(analysisMessages.get(0).getContent().toString().contains("R1 上下文判定器"));
        assertTrue(analysisMessages.get(0).getContent().toString().contains("previous room message"));

        List<BotDto.ChatMessage> finalMessages = messagesCaptor.getAllValues().get(1);
        assertTrue(finalMessages.stream()
                .anyMatch(message -> "system".equals(message.getRole())
                        && message.getContent().toString().contains("[KIRARA R1 CONTEXT ANALYSIS]")
                        && message.getContent().toString().contains("\"mention_only\":true")));
        verify(messageRepository, times(3)).save(any(Message.class));
    }

    @Test
    @DisplayName("processMessageForBots KIRARA_TWO_PASS mention-only never saves generic model failure on empty LLM replies")
    void process_kirara_two_pass_mention_only_empty_llm_uses_contextual_fallback() {
        bot.setBotName("阿雷");
        bot.setReplyMode(BotConfig.ReplyMode.CHUNKED);
        bot.setWorkflowMode(BotConfig.WorkflowMode.KIRARA_TWO_PASS);
        ChatRoomBot crb = new ChatRoomBot();
        crb.setBotConfig(bot);
        crb.setChatRoom(room);
        crb.setTriggerMode(ChatRoomBot.TriggerMode.MENTION);

        Message source = new Message();
        source.setId(200L);
        source.setContent("@阿雷");
        source.setCreatedAt(LocalDateTime.of(2026, 7, 8, 8, 48, 57));
        source.setSender(alice);
        source.setChatRoom(room);

        Message previous = new Message();
        previous.setId(199L);
        previous.setContent("腾讯把隐秘的阿雷给封了，是不是很愚蠢");
        previous.setCreatedAt(LocalDateTime.of(2026, 7, 8, 8, 47, 57));
        previous.setSender(alice);
        previous.setChatRoom(room);

        when(chatRoomBotRepository.findActiveBotsWithConfig(100L)).thenReturn(List.of(crb));
        when(messageRepository.findContextBefore(eq(100L), eq(source.getCreatedAt()), any()))
                .thenReturn(List.of(previous));
        when(llmService.chat(eq(bot), any()))
                .thenReturn(
                        new BotDto.LLMResponse("{\"mention_only\":true,\"target_speaker\":\"alice\",\"target_message\":\"腾讯封号\",\"reply_intent\":\"接前文吐槽\",\"tone\":\"阿雷式损友\",\"avoid\":\"不要客服问候\"}", 21, "kimi-code"),
                        new BotDto.LLMResponse("", 22, "kimi-code"),
                        new BotDto.LLMResponse("", 23, "kimi-code"));
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Message> replies = service.processMessageForBots(100L, "@阿雷", 1L, source);

        assertEquals(3, replies.size());
        assertEquals("看到了。", replies.get(0).getContent());
        assertTrue(replies.get(1).getContent().contains("腾讯把隐秘的阿雷给封了"));
        assertEquals("我接这句。", replies.get(2).getContent());
        assertTrue(replies.stream().noneMatch(message -> message.getContent().contains("调用模型失败")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BotDto.ChatMessage>> messagesCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(llmService, times(3)).chat(eq(bot), messagesCaptor.capture());
        assertTrue(messagesCaptor.getAllValues().get(0).get(1).getContent().toString()
                .contains("腾讯把隐秘的阿雷给封了"));
    }

    @Test
    @DisplayName("processMessageForBots skips MENTION-mode bot when not @-ed")
    void process_mention_skip() {
        ChatRoomBot crb = new ChatRoomBot();
        crb.setBotConfig(bot);
        crb.setTriggerMode(ChatRoomBot.TriggerMode.MENTION);
        when(chatRoomBotRepository.findActiveBotsWithConfig(100L)).thenReturn(List.of(crb));

        service.processMessageForBots(100L, "just chatting, no ping", 1L);

        verify(llmService, never()).chat(any(), any());
    }

    @Test
    @DisplayName("processMessageForBots KEYWORD mode matches on comma-separated list")
    void process_keyword_trigger() {
        ChatRoomBot crb = new ChatRoomBot();
        crb.setBotConfig(bot);
        crb.setTriggerMode(ChatRoomBot.TriggerMode.KEYWORD);
        crb.setTriggerKeywords("help, urgent, now");
        when(chatRoomBotRepository.findActiveBotsWithConfig(100L)).thenReturn(List.of(crb));
        when(llmService.chat(any(), any())).thenReturn(new BotDto.LLMResponse("ack", 1, "m"));
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));

        service.processMessageForBots(100L, "I need urgent attention", 1L);
        verify(llmService).chat(any(), any());
    }

    @Test
    @DisplayName("processMessageForBots REGEX mode matches configured pattern")
    void process_regex_trigger() {
        ChatRoomBot crb = new ChatRoomBot();
        crb.setBotConfig(bot);
        crb.setTriggerMode(ChatRoomBot.TriggerMode.REGEX);
        crb.setTriggerKeywords("(?i)(画图|draw)\\s*[:：]");
        when(chatRoomBotRepository.findActiveBotsWithConfig(100L)).thenReturn(List.of(crb));
        when(llmService.chat(any(), any())).thenReturn(new BotDto.LLMResponse("ack", 1, "m"));
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));

        service.processMessageForBots(100L, "画图：猫猫", 1L);

        verify(llmService).chat(any(), any());
    }

    @Test
    @DisplayName("processMessageForBots REGEX mode skips invalid pattern safely")
    void process_regex_invalid_pattern_skips() {
        ChatRoomBot crb = new ChatRoomBot();
        crb.setChatRoom(room);
        crb.setBotConfig(bot);
        crb.setTriggerMode(ChatRoomBot.TriggerMode.REGEX);
        crb.setTriggerKeywords("[");
        when(chatRoomBotRepository.findActiveBotsWithConfig(100L)).thenReturn(List.of(crb));

        List<Message> replies = service.processMessageForBots(100L, "anything", 1L);

        assertTrue(replies.isEmpty());
        verify(llmService, never()).chat(any(), any());
    }

    @Test
    @DisplayName("processMessageForBots ALL mode always fires")
    void process_all_trigger() {
        ChatRoomBot crb = new ChatRoomBot();
        crb.setBotConfig(bot);
        crb.setTriggerMode(ChatRoomBot.TriggerMode.ALL);
        when(chatRoomBotRepository.findActiveBotsWithConfig(100L)).thenReturn(List.of(crb));
        when(llmService.chat(any(), any())).thenReturn(new BotDto.LLMResponse("hi", 1, "m"));
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));

        service.processMessageForBots(100L, "anything", 1L);
        verify(llmService).chat(any(), any());
    }

    @Test
    @DisplayName("processMessageForBots surfaces LLM errors as safe bot messages")
    void process_llm_error_saves_safe_error_message() {
        ChatRoomBot crb = new ChatRoomBot();
        crb.setBotConfig(bot);
        crb.setTriggerMode(ChatRoomBot.TriggerMode.ALL);
        when(chatRoomBotRepository.findActiveBotsWithConfig(100L)).thenReturn(List.of(crb));
        when(llmService.chat(any(), any())).thenThrow(new RuntimeException("LLM API调用失败: 429 quota exceeded"));
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Message> replies = assertDoesNotThrow(() -> service.processMessageForBots(100L, "anything", 1L));

        assertEquals(1, replies.size());
        assertTrue(replies.get(0).getContent().contains("额度不足或被限流"));
        assertFalse(replies.get(0).getContent().contains("quota exceeded"));
        assertEquals(bot, replies.get(0).getBotConfig());
    }

    @Test
    @DisplayName("importCharacterCard stores SillyTavern v2 fields")
    void import_character_card_stores_fields() {
        when(botConfigRepository.findById(10L)).thenReturn(Optional.of(bot));
        when(botConfigRepository.save(any(BotConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        BotDto dto = service.importCharacterCard(10L, 1L, sampleCard());

        assertEquals("Kirara", dto.getBotName());
        assertTrue(dto.getHasCharacterCard());
        assertTrue(dto.getCharacterPersona().contains("fox courier"));
        assertEquals(2, dto.getCharacterAlternateGreetings().size());
        assertEquals(1, dto.getCharacterBookEntryCount());
        assertNotNull(bot.getCharacterCardJson());
        assertNotNull(bot.getCharacterBookJson());
    }

    @Test
    @DisplayName("exportCharacterCard returns stored card JSON")
    void export_character_card_returns_stored_json() {
        when(botConfigRepository.findById(10L)).thenReturn(Optional.of(bot));
        bot.setCharacterCardJson("{\"spec\":\"chara_card_v2\",\"spec_version\":\"2.0\",\"data\":{\"name\":\"Kirara\"}}");

        Map<String, Object> exported = service.exportCharacterCard(10L, 1L);

        assertEquals("chara_card_v2", exported.get("spec"));
        assertTrue(exported.get("data") instanceof Map);
    }

    @Test
    @DisplayName("processMessageForBots injects room-aware context for tool-less bots")
    void process_room_aware_context_for_toolless_bot() {
        ChatRoomBot crb = new ChatRoomBot();
        crb.setChatRoom(room);
        crb.setBotConfig(bot);
        crb.setTriggerMode(ChatRoomBot.TriggerMode.ALL);
        crb.setRoomNickname("无敌高");
        bot.setCharacterPostHistoryInstructions("Post history instruction");

        Message source = new Message();
        source.setId(500L);
        source.setChatRoom(room);
        source.setSender(alice);
        source.setContent("@无敌高");
        source.setMessageType(Message.MessageType.TEXT);

        when(chatRoomBotRepository.findActiveBotsWithConfig(100L)).thenReturn(List.of(crb));
        when(llmService.chat(any(), any())).thenReturn(new BotDto.LLMResponse("ack", 1, "m"));
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        service.processMessageForBots(100L, "@无敌高", 1L, source);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BotDto.ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmService).chat(eq(bot), captor.capture());
        List<BotDto.ChatMessage> messages = captor.getValue();
        assertEquals("system", messages.get(0).getRole());
        assertTrue(messages.get(0).textContent().contains("Room: test-room"));
        assertTrue(messages.get(0).textContent().contains("Members (2): alice, bob"));
        assertTrue(messages.get(0).textContent().contains("Recent: previous room message"));
        assertTrue(messages.get(0).textContent().contains("visible bot name is \"无敌高\""));
        assertTrue(messages.get(0).textContent().contains("[MENTION_ONLY]"));
        assertEquals("system", messages.get(1).getRole());
        assertEquals("Post history instruction", messages.get(1).textContent());
        assertEquals("user", messages.get(2).getRole());
        assertTrue(messages.get(2).textContent().contains("[MENTION_ONLY]"));

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(agentContextBuilder).buildContext(taskCaptor.capture());
        assertEquals(room, taskCaptor.getValue().getChatRoom());
        assertEquals(alice, taskCaptor.getValue().getRequestedBy());
        assertEquals(bot, taskCaptor.getValue().getBotConfig());
    }

    @Test
    @DisplayName("importCharacterCard rejects non-v2 cards")
    void import_character_card_rejects_wrong_spec() {
        when(botConfigRepository.findById(10L)).thenReturn(Optional.of(bot));
        Map<String, Object> card = Map.of("spec", "legacy", "data", Map.of("name", "Old"));

        assertThrows(IllegalArgumentException.class,
                () -> service.importCharacterCard(10L, 1L, card));
        verify(botConfigRepository, never()).save(any());
    }

    private Map<String, Object> sampleCard() {
        return Map.of(
                "spec", "chara_card_v2",
                "spec_version", "2.0",
                "data", Map.ofEntries(
                        Map.entry("name", "Kirara"),
                        Map.entry("description", "A fox courier."),
                        Map.entry("personality", "Warm and energetic."),
                        Map.entry("scenario", "Guild delivery work."),
                        Map.entry("first_mes", "Package delivered!"),
                        Map.entry("mes_example", "<START>"),
                        Map.entry("creator_notes", "Test card"),
                        Map.entry("system_prompt", "Stay in character."),
                        Map.entry("post_history_instructions", "End with a question."),
                        Map.entry("alternate_greetings", List.of("Hi!", "Ready to run.")),
                        Map.entry("character_book", Map.of(
                                "entries", List.of(Map.of(
                                        "keys", List.of("parcel"),
                                        "content", "Parcels are sacred.",
                                        "enabled", true))))));
    }
}
