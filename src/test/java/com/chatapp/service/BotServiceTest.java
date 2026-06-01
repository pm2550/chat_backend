package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomBot;
import com.chatapp.entity.Message;
import com.chatapp.entity.ProviderCredential;
import com.chatapp.entity.User;
import com.chatapp.repository.BotConfigRepository;
import com.chatapp.repository.ChatRoomBotRepository;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BotService")
class BotServiceTest {

    @Mock private BotConfigRepository botConfigRepository;
    @Mock private ChatRoomBotRepository chatRoomBotRepository;
    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private UserRepository userRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private LLMService llmService;
    @Mock private ProviderCredentialService providerCredentialService;

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
        room.setCreatedBy(alice);
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
        assertNull(captor.getValue().getApiKeyEncrypted());
        assertEquals(credential, captor.getValue().getProviderCredential());
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
        when(chatRoomBotRepository.findActiveBotsWithConfig(100L)).thenReturn(List.of(crb));
        when(llmService.chat(any(BotConfig.class), any()))
                .thenReturn(new BotDto.LLMResponse("hello back", 42, "gpt-4o"));
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        service.processMessageForBots(100L, "@GPT-Helper what's up", 1L);

        verify(llmService).chat(eq(bot), any());
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        Message saved = captor.getValue();
        assertEquals("hello back", saved.getContent());
        assertEquals(Message.MessageType.TEXT, saved.getMessageType());
        assertEquals(bot, saved.getBotConfig());
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
    @DisplayName("processMessageForBots swallows LLM errors without failing")
    void process_llm_error_swallowed() {
        ChatRoomBot crb = new ChatRoomBot();
        crb.setBotConfig(bot);
        crb.setTriggerMode(ChatRoomBot.TriggerMode.ALL);
        when(chatRoomBotRepository.findActiveBotsWithConfig(100L)).thenReturn(List.of(crb));
        when(llmService.chat(any(), any())).thenThrow(new RuntimeException("LLM down"));

        // should not propagate
        assertDoesNotThrow(() -> service.processMessageForBots(100L, "anything", 1L));
    }
}
