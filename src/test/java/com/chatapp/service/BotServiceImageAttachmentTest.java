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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotServiceImageAttachmentTest {
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
    void imageUserMessageBecomesMultimodalContent() {
        Fixture fixture = fixture();
        Message sourceImage = imageMessage("diagram.png", "/api/files/chat/stored.png");
        BotDto.ImageAttachment attachment = new BotDto.ImageAttachment(
                "diagram.png", "image/png", "data:image/png;base64,aGVsbG8=");
        when(chatRoomBotRepository.findActiveBotsWithConfig(1L)).thenReturn(List.of(fixture.crb()));
        when(agentVisionAttachmentService.resolve(sourceImage, true))
                .thenReturn(new AgentVisionAttachmentService.ImageContext(List.of(attachment), "[图片: diagram.png]", false));
        when(llmService.chat(eq(fixture.bot()), any()))
                .thenReturn(new BotDto.LLMResponse("seen", 4, "vision"));
        when(chatRoomRepository.findById(1L)).thenReturn(Optional.of(fixture.room()));
        when(userRepository.findById(10L)).thenReturn(Optional.of(fixture.user()));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        botService.processMessageForBots(1L, "@VisionBot 这是什么", 10L, sourceImage);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BotDto.ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmService).chat(eq(fixture.bot()), captor.capture());
        BotDto.ChatMessage user = captor.getValue().stream()
                .filter(message -> "user".equals(message.getRole()))
                .findFirst()
                .orElseThrow();
        assertTrue(user.hasImageContent());
        assertInstanceOf(List.class, user.getContent());
        assertTrue(user.textContent().contains("[image]"));
        assertTrue(user.textContent().contains("diagram.png"));
    }

    @Test
    void textOnlyMessageStaysStringContent() {
        Fixture fixture = fixture();
        when(chatRoomBotRepository.findActiveBotsWithConfig(1L)).thenReturn(List.of(fixture.crb()));
        when(agentVisionAttachmentService.resolve(null, true)).thenReturn(AgentVisionAttachmentService.ImageContext.empty());
        when(llmService.chat(eq(fixture.bot()), any()))
                .thenReturn(new BotDto.LLMResponse("plain", 3, "text"));
        when(chatRoomRepository.findById(1L)).thenReturn(Optional.of(fixture.room()));
        when(userRepository.findById(10L)).thenReturn(Optional.of(fixture.user()));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        botService.processMessageForBots(1L, "@VisionBot hello", 10L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BotDto.ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmService).chat(eq(fixture.bot()), captor.capture());
        BotDto.ChatMessage user = captor.getValue().stream()
                .filter(message -> "user".equals(message.getRole()))
                .findFirst()
                .orElseThrow();
        assertInstanceOf(String.class, user.getContent());
        assertFalse(user.hasImageContent());
    }

    @Test
    void historyTenImagesCapsBinaryAttachmentsAtRecentFive() {
        MessageRepository messageRepository = org.mockito.Mockito.mock(MessageRepository.class);
        ChatRoomRepository chatRoomRepository = org.mockito.Mockito.mock(ChatRoomRepository.class);
        MemoryService memoryService = org.mockito.Mockito.mock(MemoryService.class);
        AgentVisionAttachmentService vision = org.mockito.Mockito.mock(AgentVisionAttachmentService.class);
        AgentContextBuilder builder = new AgentContextBuilder(messageRepository, chatRoomRepository, memoryService, vision);

        ChatRoom room = new ChatRoom();
        room.setId(7L);
        room.setName("vision-room");
        BotConfig bot = new BotConfig();
        bot.setBotName("VisionBot");
        bot.setIncludeRoomMetadata(false);
        bot.setIncludeMemorySection(false);
        bot.setMaxHistoryMessages(10);
        AgentTask task = new AgentTask();
        task.setChatRoom(room);
        task.setBotConfig(bot);
        task.setPrompt("summarize images");
        task.setRequestedBy(user(22L));

        List<Message> newestFirst = new ArrayList<>();
        for (int i = 10; i >= 1; i--) {
            newestFirst.add(imageMessage("img" + i + ".png", "/api/files/chat/img" + i + ".png"));
        }
        when(messageRepository.findRecentMessages(anyLong(), anyInt())).thenReturn(newestFirst);
        when(vision.isImageMessage(any(Message.class))).thenReturn(true);
        when(vision.resolve(any(Message.class), eq(false))).thenAnswer(inv -> {
            Message message = inv.getArgument(0);
            return new AgentVisionAttachmentService.ImageContext(List.of(), "[图片: " + message.getFileName() + "]", false);
        });
        when(vision.resolve(any(Message.class), eq(true))).thenAnswer(inv -> {
            Message message = inv.getArgument(0);
            BotDto.ImageAttachment attachment = new BotDto.ImageAttachment(
                    message.getFileName(), "image/png", "data:image/png;base64," + message.getFileName());
            return new AgentVisionAttachmentService.ImageContext(List.of(attachment), "[图片: " + message.getFileName() + "]", false);
        });

        AgentContextBuilder.AgentContextEnvelope env = builder.buildContext(task);

        long attached = env.conversationHistory().stream()
                .filter(message -> !message.imageAttachments().isEmpty())
                .count();
        assertEquals(5, attached);
        assertTrue(env.conversationHistory().get(0).imageAttachments().isEmpty());
        assertFalse(env.conversationHistory().get(9).imageAttachments().isEmpty());
    }

    private Fixture fixture() {
        User user = user(10L);
        ChatRoom room = new ChatRoom();
        room.setId(1L);
        room.setCreatedBy(user);
        BotConfig bot = new BotConfig();
        bot.setId(99L);
        bot.setBotName("VisionBot");
        bot.setLlmProvider(BotConfig.LLMProvider.OPENAI);
        bot.setCreatedBy(user);
        ChatRoomBot crb = new ChatRoomBot();
        crb.setChatRoom(room);
        crb.setBotConfig(bot);
        crb.setTriggerMode(ChatRoomBot.TriggerMode.MENTION);
        return new Fixture(user, room, bot, crb);
    }

    private static User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        return user;
    }

    private static Message imageMessage(String fileName, String fileUrl) {
        Message message = new Message();
        message.setId((long) Math.abs(fileName.hashCode()));
        message.setContent(fileName);
        message.setFileName(fileName);
        message.setFileUrl(fileUrl);
        message.setFileType("image/png");
        message.setMessageType(Message.MessageType.IMAGE);
        message.setSender(user(20L));
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }

    private record Fixture(User user, ChatRoom room, BotConfig bot, ChatRoomBot crb) {
    }
}
