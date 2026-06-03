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
import com.chatapp.websocket.RawWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

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
        ChatRoomBot crb = new ChatRoomBot();
        crb.setBotConfig(bot);
        crb.setChatRoom(room);
        crb.setIsActive(true);
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
        ChatRoomBot crb = new ChatRoomBot();
        crb.setBotConfig(bot);
        crb.setChatRoom(room);
        crb.setIsActive(true);
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
        ChatRoomBot crb = new ChatRoomBot();
        crb.setBotConfig(bot);
        crb.setChatRoom(room);
        crb.setIsActive(true);
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
        ChatRoomBot crb = new ChatRoomBot();
        crb.setBotConfig(bot);
        crb.setChatRoom(room);
        crb.setIsActive(true);
        when(chatRoomBotRepository.findByBotConfigIdAndIsActiveTrue(5L)).thenReturn(List.of(crb));
        assertEquals(List.of(100L), service.boundRoomIds(bot));
    }
}
