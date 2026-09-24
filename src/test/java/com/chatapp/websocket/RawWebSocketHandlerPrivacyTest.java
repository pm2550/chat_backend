package com.chatapp.websocket;

import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.service.BotReplyDeliveryService;
import com.chatapp.service.BotService;
import com.chatapp.service.MessageService;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.RoomTypingAggregator;
import com.chatapp.service.MessageReactionService;
import com.chatapp.service.UserPresenceService;
import com.chatapp.service.UserPrivacyService;
import com.chatapp.service.tool.PendingClientCallRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 消息通知 / 已读回执 / 在线状态三个开关在 WebSocket 出口上的效果。 */
class RawWebSocketHandlerPrivacyTest {

    private final ChatRoomRepository chatRoomRepository = mock(ChatRoomRepository.class);
    private final PushNotificationService pushNotificationService = mock(PushNotificationService.class);
    private final UserPrivacyService userPrivacyService = mock(UserPrivacyService.class);
    private final UserPresenceService userPresenceService = mock(UserPresenceService.class);
    private RawWebSocketHandler handler;
    private final Map<Long, List<String>> received = new HashMap<>();

    @BeforeEach
    void setUp() {
        handler = new RawWebSocketHandler(
                new ObjectMapper().findAndRegisterModules(),
                mock(MessageService.class),
                mock(BotService.class),
                mock(BotReplyDeliveryService.class),
                chatRoomRepository,
                pushNotificationService,
                mock(RoomTypingAggregator.class),
                new CallRoomRegistry(),
                new PendingClientCallRegistry(),
                mock(MessageReactionService.class),
                userPrivacyService,
                userPresenceService);
        when(chatRoomRepository.findMember(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(userPresenceService.markConnected(anyLong())).thenReturn(User.OnlineStatus.ONLINE);
        when(userPresenceService.chosenPresence(anyLong())).thenReturn(User.OnlineStatus.ONLINE);
    }

    @Test
    void offlineMemberWithMessageNotificationsOffGetsNoPush() {
        when(chatRoomRepository.findMemberUserIdsByRoomId(5L)).thenReturn(List.of(1L, 2L, 3L));
        when(userPrivacyService.usersWithMessageNotificationsDisabled(List.of(1L, 2L, 3L)))
                .thenReturn(Set.of(3L));

        handler.broadcastMessage(message(1L, 5L));

        verify(pushNotificationService).sendPushNotification(eq(2L), anyString(), anyString(), anyString());
        verify(pushNotificationService, never()).sendPushNotification(eq(3L), any(), any(), any());
        verify(pushNotificationService, never()).sendPushNotification(eq(1L), any(), any(), any());
    }

    @Test
    void mentionDoesNotOverrideGlobalNotificationSwitch() {
        when(chatRoomRepository.findMemberUserIdsByRoomId(5L)).thenReturn(List.of(1L, 3L));
        when(userPrivacyService.usersWithMessageNotificationsDisabled(List.of(1L, 3L))).thenReturn(Set.of(3L));
        Message mention = message(1L, 5L);
        mention.setMentionedUserIds(Set.of(3L));

        handler.broadcastMessage(mention);

        verify(pushNotificationService, never()).sendPushNotification(eq(3L), any(), any(), any());
    }

    @Test
    void readReceiptOfUserWithReceiptsOffIsNotBroadcast() {
        connect(2L);
        when(chatRoomRepository.findMemberUserIdsByRoomId(5L)).thenReturn(List.of(1L, 2L));
        when(userPrivacyService.readReceiptsDisabled(1L)).thenReturn(true);

        handler.broadcastReadReceipt(5L, 1L, 99L);

        assertTrue(received.get(2L).isEmpty());
    }

    @Test
    void userWithReceiptsOffDoesNotReceiveOthersReadReceipts() {
        connect(2L);
        connect(3L);
        received.values().forEach(List::clear); // 忽略上线广播
        when(chatRoomRepository.findMemberUserIdsByRoomId(5L)).thenReturn(List.of(1L, 2L, 3L));
        when(userPrivacyService.usersWithReadReceiptsDisabled(List.of(1L, 2L, 3L))).thenReturn(Set.of(3L));

        handler.broadcastReadReceipt(5L, 1L, 99L);

        assertEquals(1, received.get(2L).size());
        assertTrue(received.get(2L).get(0).contains("\"read_receipt\""));
        assertTrue(received.get(3L).isEmpty());
    }

    @Test
    void hiddenUserComingOnlineIsNotAnnounced() {
        connect(2L);
        when(userPrivacyService.hidesOnlineStatus(1L)).thenReturn(true);

        connect(1L);

        assertTrue(received.get(2L).isEmpty());
    }

    @Test
    void visibleUserComingOnlineIsAnnounced() {
        connect(2L);

        connect(1L);

        assertEquals(1, received.get(2L).size());
        assertTrue(received.get(2L).get(0).contains("\"onlineStatus\":\"ONLINE\""));
    }

    @Test
    void togglingVisibilityWhileOnlineAnnouncesOfflineThenOnline() {
        connect(2L);
        when(userPrivacyService.hidesOnlineStatus(1L)).thenReturn(true);
        connect(1L);

        handler.onPresenceVisibilityChanged(new UserPrivacyService.PresenceVisibilityChanged(1L, true));
        handler.onPresenceVisibilityChanged(new UserPrivacyService.PresenceVisibilityChanged(1L, false));

        assertEquals(2, received.get(2L).size());
        assertTrue(received.get(2L).get(0).contains("\"onlineStatus\":\"ONLINE\""));
        assertTrue(received.get(2L).get(1).contains("\"onlineStatus\":\"OFFLINE\""));
    }

    @Test
    void turningVisibilityBackOnAnnouncesTheChosenStatusNotOnline() {
        connect(2L);
        when(userPrivacyService.hidesOnlineStatus(1L)).thenReturn(true);
        connect(1L);
        when(userPresenceService.chosenPresence(1L)).thenReturn(User.OnlineStatus.BUSY);

        handler.onPresenceVisibilityChanged(new UserPrivacyService.PresenceVisibilityChanged(1L, true));

        assertEquals(1, received.get(2L).size());
        assertTrue(received.get(2L).get(0).contains("\"onlineStatus\":\"BUSY\""), received.get(2L).get(0));
    }

    @Test
    void visibilityChangeOfAUserWithOnlyABackgroundConnectionIsNotAnnounced() {
        connect(2L);
        connect(1L, true);

        handler.onPresenceVisibilityChanged(new UserPrivacyService.PresenceVisibilityChanged(1L, true));

        assertTrue(received.get(2L).isEmpty());
    }

    private void connect(Long userId) {
        connect(userId, false);
    }

    private void connect(Long userId, boolean background) {
        User user = new User();
        user.setId(userId);
        user.setUsername("u" + userId);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(RawWebSocketHandler.ATTR_USER, user);
        if (background) {
            attributes.put(RawWebSocketHandler.ATTR_BACKGROUND, Boolean.TRUE);
        }
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("s" + userId);
        List<String> inbox = received.computeIfAbsent(userId, id -> new ArrayList<>());
        try {
            doAnswer(inv -> {
                inbox.add(((TextMessage) inv.getArgument(0)).getPayload());
                return null;
            }).when(session).sendMessage(any());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        handler.afterConnectionEstablished(session);
    }

    private Message message(Long senderId, Long roomId) {
        User sender = new User();
        sender.setId(senderId);
        sender.setUsername("u" + senderId);
        ChatRoom room = new ChatRoom();
        room.setId(roomId);
        room.setName("群");
        room.setRoomType(ChatRoom.RoomType.GROUP);
        Message message = new Message();
        message.setId(42L);
        message.setChatRoom(room);
        message.setSender(sender);
        message.setContent("hi");
        message.setMessageType(Message.MessageType.TEXT);
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }
}
