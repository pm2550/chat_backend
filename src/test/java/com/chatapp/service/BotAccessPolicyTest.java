package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.BotAllowedUser;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomBot;
import com.chatapp.entity.User;
import com.chatapp.repository.AgentTaskRepository;
import com.chatapp.repository.BotAllowedUserRepository;
import com.chatapp.repository.BotConfigRepository;
import com.chatapp.repository.ChatRoomBotRepository;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.tool.AgentToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BotAccessPolicyTest {

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
    @Mock private AgentVisionAttachmentService agentVisionAttachmentService;
    @Mock private ObjectProvider<AgentExecutionLoop> agentExecutionLoopProvider;

    @InjectMocks private BotService botService;

    @Test
    void updateBotStoresAllowlistUsers() {
        User owner = user(1L, "owner");
        User allowed = user(2L, "guest");
        BotConfig bot = bot(10L, owner, BotConfig.AccessPolicy.PRIVATE);

        when(botConfigRepository.findById(10L)).thenReturn(Optional.of(bot));
        when(botConfigRepository.save(any(BotConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findByIdIn(List.of(2L))).thenReturn(List.of(allowed));
        when(botAllowedUserRepository.findByBotConfigIdOrderByUserUsernameAsc(10L))
                .thenReturn(List.of(allowedUser(bot, allowed)));

        BotDto.UpdateRequest request = new BotDto.UpdateRequest();
        request.setAccessPolicy(BotConfig.AccessPolicy.ALLOWLIST);
        request.setAllowedUserIds(List.of(2L));

        BotDto dto = botService.updateBot(10L, 1L, request);

        assertEquals(BotConfig.AccessPolicy.ALLOWLIST, bot.getAccessPolicy());
        assertEquals(BotConfig.AccessPolicy.ALLOWLIST, dto.getAccessPolicy());
        assertEquals(1, dto.getAllowedUsers().size());
        assertEquals("guest", dto.getAllowedUsers().get(0).getUsername());

        ArgumentCaptor<BotAllowedUser> captor = ArgumentCaptor.forClass(BotAllowedUser.class);
        verify(botAllowedUserRepository).deleteByBotConfigId(10L);
        verify(botAllowedUserRepository).save(captor.capture());
        assertEquals(10L, captor.getValue().getBotConfig().getId());
        assertEquals(2L, captor.getValue().getUser().getId());
    }

    @Test
    void roomAdminCannotInstallSomeoneElsesPrivateBot() {
        User owner = user(1L, "owner");
        BotConfig privateBot = bot(10L, owner, BotConfig.AccessPolicy.PRIVATE);
        ChatRoom room = new ChatRoom();
        room.setId(20L);

        when(chatRoomRepository.findById(20L)).thenReturn(Optional.of(room));
        when(botConfigRepository.findById(10L)).thenReturn(Optional.of(privateBot));
        when(chatRoomRepository.isAdmin(20L, 2L)).thenReturn(true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> botService.addBotToChatRoom(20L, 10L, new BotDto.AddToChatRoomRequest(), 2L));

        assertEquals("无权限管理该聊天室机器人", error.getMessage());
        verify(chatRoomBotRepository, never()).save(any());
    }

    @Test
    void privateBotDoesNotRespondToNonOwnerMention() {
        User owner = user(1L, "owner");
        BotConfig privateBot = bot(10L, owner, BotConfig.AccessPolicy.PRIVATE);
        ChatRoomBot binding = new ChatRoomBot();
        binding.setBotConfig(privateBot);
        binding.setTriggerMode(ChatRoomBot.TriggerMode.MENTION);
        binding.setEnabledInRoom(true);
        binding.setIsActive(true);

        when(chatRoomBotRepository.findActiveBotsWithConfig(20L)).thenReturn(List.of(binding));

        assertTrue(botService.processMessageForBots(20L, "@PrivateBot hi", 2L).isEmpty());

        verifyNoInteractions(llmService);
        verifyNoInteractions(agentExecutionLoopProvider);
        verify(messageRepository, never()).save(any());
    }

    private BotConfig bot(Long id, User owner, BotConfig.AccessPolicy policy) {
        BotConfig bot = new BotConfig();
        bot.setId(id);
        bot.setBotName("PrivateBot");
        bot.setLlmProvider(BotConfig.LLMProvider.HERMES);
        bot.setAccessPolicy(policy);
        bot.setCreatedBy(owner);
        bot.setIsActive(true);
        return bot;
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setDisplayName(username);
        user.setEmail(username + "@example.test");
        return user;
    }

    private BotAllowedUser allowedUser(BotConfig bot, User user) {
        BotAllowedUser allowed = new BotAllowedUser();
        allowed.setBotConfig(bot);
        allowed.setUser(user);
        return allowed;
    }
}
