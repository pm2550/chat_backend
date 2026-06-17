package com.chatapp.service;

import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomBot;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomBotRepository;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.tool.InspectRoomImageTool;
import com.chatapp.websocket.RawWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotGatewayServiceTest {

    @Mock private ChatRoomBotRepository chatRoomBotRepository;
    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private UserRepository userRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private RawWebSocketHandler rawWebSocketHandler;
    @Mock private FileStorageService fileStorageService;
    @Mock private InspectRoomImageTool inspectRoomImageTool;
    @Mock private ObjectMapper objectMapper;
    @Mock private WorkspaceService workspaceService;
    @Mock private ChatRoomService chatRoomService;
    @Mock private BotService botService;
    @Mock private ModerationService moderationService;
    @Mock private FriendshipService friendshipService;

    @InjectMocks private BotGatewayService service;

    private User owner;
    private BotConfig bot;
    private ChatRoom room;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setId(1L);
        bot = new BotConfig();
        bot.setId(5L);
        bot.setBotName("bridge-bot");
        bot.setCreatedBy(owner);
        room = new ChatRoom();
        room.setId(100L);
        room.setCreatedBy(owner);
    }

    @Test
    void postAsBotSavesAndBroadcastsWhenBound() {
        ChatRoomBot crb = activeBinding();
        when(chatRoomBotRepository.findByChatRoomIdAndBotConfigId(100L, 5L)).thenReturn(Optional.of(crb));
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        Message m = service.postAsBot(bot, 100L, "hello from openclaw");

        assertEquals("hello from openclaw", m.getContent());
        assertEquals(bot, m.getBotConfig());
        assertEquals(Message.MessageType.TEXT, m.getMessageType());
        verify(chatRoomRepository).incrementUnreadForRoomMembersExcept(100L, 1L);
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(rawWebSocketHandler).broadcastMessage(captor.capture());
        assertEquals("hello from openclaw", captor.getValue().getContent());
    }

    @Test
    void postAsBotSetsRoomNicknameAsDisplayName() {
        ChatRoomBot crb = activeBinding();
        crb.setRoomNickname("  助手小蓝  ");
        when(chatRoomBotRepository.findByChatRoomIdAndBotConfigId(100L, 5L)).thenReturn(Optional.of(crb));
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        Message m = service.postAsBot(bot, 100L, "hi");

        // Room nickname (trimmed) overrides the bot's global name.
        assertEquals("助手小蓝", m.getBotDisplayName());
    }

    @Test
    void postAsBotFallsBackToBotNameWhenNoRoomNickname() {
        ChatRoomBot crb = activeBinding();
        when(chatRoomBotRepository.findByChatRoomIdAndBotConfigId(100L, 5L)).thenReturn(Optional.of(crb));
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        Message m = service.postAsBot(bot, 100L, "hi");

        assertEquals("bridge-bot", m.getBotDisplayName());
    }

    @Test
    void postAsBotRejectsWhenNotBound() {
        when(chatRoomBotRepository.findByChatRoomIdAndBotConfigId(100L, 5L)).thenReturn(Optional.empty());
        assertThrows(AccessDeniedException.class, () -> service.postAsBot(bot, 100L, "hi"));
        verify(messageRepository, never()).save(any());
        verify(rawWebSocketHandler, never()).broadcastMessage(any());
    }

    @Test
    void postAsBotRejectsBlankContent() {
        assertThrows(IllegalArgumentException.class, () -> service.postAsBot(bot, 100L, "   "));
        verify(messageRepository, never()).save(any());
    }

    @Test
    void boundRoomIdsMapsBindings() {
        ChatRoomBot crb = activeBinding();
        when(chatRoomBotRepository.findByBotConfigIdAndIsActiveTrue(5L)).thenReturn(List.of(crb));
        assertEquals(List.of(100L), service.boundRoomIds(bot));
    }

    @Test
    void postAsBotWithReplySetsReplyMessage() {
        ChatRoomBot crb = activeBinding();
        when(chatRoomBotRepository.findByChatRoomIdAndBotConfigId(100L, 5L)).thenReturn(Optional.of(crb));
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        Message reply = new Message();
        reply.setId(77L);
        reply.setChatRoom(room);
        when(messageRepository.findWithSenderById(77L)).thenReturn(Optional.of(reply));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        Message m = service.postAsBot(bot, 100L, "replying", 77L, null);

        assertEquals(reply, m.getReplyToMessage());
    }

    @Test
    void postFileAsBotUploadsAndInfersImageMessage() throws Exception {
        ChatRoomBot crb = activeBinding();
        when(chatRoomBotRepository.findByChatRoomIdAndBotConfigId(100L, 5L)).thenReturn(Optional.of(crb));
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(fileStorageService.uploadChatFile(any())).thenReturn("/api/files/chat/stored.png");
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        MockMultipartFile file = new MockMultipartFile(
                "file", "picture.png", "image/png", "png".getBytes(StandardCharsets.UTF_8));

        Message m = service.postFileAsBot(bot, 100L, file, null, null);

        assertEquals(Message.MessageType.IMAGE, m.getMessageType());
        assertEquals("/api/files/chat/stored.png", m.getFileUrl());
        assertEquals("picture.png", m.getFileName());
        assertEquals("image/png", m.getFileType());
        verify(fileStorageService).uploadChatFile(file);
        verify(rawWebSocketHandler).broadcastMessage(m);
    }

    @Test
    void postExistingFileAsBotRequiresSourceRoomBindingAndClonesAttachment() {
        ChatRoomBot crb = activeBinding();
        when(chatRoomBotRepository.findByChatRoomIdAndBotConfigId(100L, 5L)).thenReturn(Optional.of(crb));
        when(chatRoomRepository.findById(100L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        Message source = new Message();
        source.setId(88L);
        source.setChatRoom(room);
        source.setMessageType(Message.MessageType.FILE);
        source.setFileUrl("/api/files/chat/doc.pdf");
        source.setFileName("doc.pdf");
        source.setFileType("application/pdf");
        source.setFileSize(42L);
        when(messageRepository.findWithSenderById(88L)).thenReturn(Optional.of(source));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        Message m = service.postAsBot(bot, 100L, "forwarded", null, 88L);

        assertEquals(Message.MessageType.FILE, m.getMessageType());
        assertEquals("/api/files/chat/doc.pdf", m.getFileUrl());
        assertEquals("doc.pdf", m.getFileName());
        assertEquals("forwarded", m.getContent());
    }

    @Test
    void downloadFileChecksBindingAndReadsStorageService() throws Exception {
        ChatRoomBot crb = activeBinding();
        when(chatRoomBotRepository.findByChatRoomIdAndBotConfigId(100L, 5L)).thenReturn(Optional.of(crb));
        Message fileMessage = new Message();
        fileMessage.setId(88L);
        fileMessage.setChatRoom(room);
        fileMessage.setFileUrl("/api/files/chat/stored.png");
        fileMessage.setFileName("original.png");
        fileMessage.setFileType("image/png");
        when(messageRepository.findWithSenderById(88L)).thenReturn(Optional.of(fileMessage));
        when(fileStorageService.getFile("chat", "stored.png")).thenReturn(new byte[] {1, 2, 3});

        BotGatewayService.GatewayFileDownload file = service.downloadFile(bot, 88L);

        assertEquals("original.png", file.fileName());
        assertEquals("image/png", file.contentType());
        assertEquals(100L, file.roomId());
        assertEquals(3, file.bytes().length);
    }

    private ChatRoomBot activeBinding() {
        ChatRoomBot crb = new ChatRoomBot();
        crb.setBotConfig(bot);
        crb.setChatRoom(room);
        crb.setIsActive(true);
        return crb;
    }
}
