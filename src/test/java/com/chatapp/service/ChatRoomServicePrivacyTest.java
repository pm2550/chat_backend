package com.chatapp.service;

import com.chatapp.dto.ChatRoomParticipantDto;
import com.chatapp.dto.ChatRoomSummaryDto;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.User;
import com.chatapp.repository.BotConfigRepository;
import com.chatapp.repository.ChatRoomBotRepository;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.FriendshipRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** "允许私聊"和"显示在线状态"两个设置在 ChatRoomService 里的落地。 */
@ExtendWith(MockitoExtension.class)
class ChatRoomServicePrivacyTest {

    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private UserRepository userRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private BotConfigRepository botConfigRepository;
    @Mock private ChatRoomBotRepository chatRoomBotRepository;
    @Mock private FriendshipRepository friendshipRepository;
    @Mock private UserPrivacyService userPrivacyService;

    @InjectMocks private ChatRoomService chatRoomService;

    @Test
    void strangerCannotStartNewPrivateChatWhenTargetOnlyAllowsFriends() {
        when(chatRoomRepository.findPrivateChatBetween(1L, 2L)).thenReturn(Optional.empty());
        when(userPrivacyService.rejectsDirectMessagesFromStrangers(2L)).thenReturn(true);
        when(friendshipRepository.areFriends(1L, 2L)).thenReturn(false);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> chatRoomService.createPrivateChat(1L, 2L));

        assertEquals("对方只接受好友发起私聊，请先添加好友", error.getMessage());
        verify(chatRoomRepository, never()).save(any(ChatRoom.class));
    }

    @Test
    void friendCanStillStartPrivateChatWhenTargetOnlyAllowsFriends() {
        when(chatRoomRepository.findPrivateChatBetween(1L, 2L)).thenReturn(Optional.empty());
        when(userPrivacyService.rejectsDirectMessagesFromStrangers(2L)).thenReturn(true);
        when(friendshipRepository.areFriends(1L, 2L)).thenReturn(true);
        User alice = user(1L, "alice");
        User bob = user(2L, "bob");
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(userRepository.findById(2L)).thenReturn(Optional.of(bob));
        when(chatRoomRepository.save(any(ChatRoom.class))).thenAnswer(inv -> {
            ChatRoom room = inv.getArgument(0);
            room.setId(100L);
            return room;
        });
        when(chatRoomRepository.findById(100L)).thenAnswer(inv -> {
            ChatRoom room = new ChatRoom();
            room.setId(100L);
            room.setRoomType(ChatRoom.RoomType.PRIVATE);
            room.setMaxMembers(2);
            return Optional.of(room);
        });

        ChatRoom room = chatRoomService.createPrivateChat(1L, 2L);

        assertEquals(ChatRoom.RoomType.PRIVATE, room.getRoomType());
    }

    @Test
    void existingPrivateChatKeepsWorkingAfterTargetStopsAcceptingStrangers() {
        ChatRoom existing = new ChatRoom();
        existing.setId(20L);
        when(chatRoomRepository.findPrivateChatBetween(1L, 2L)).thenReturn(Optional.of(existing));

        assertSame(existing, chatRoomService.createPrivateChat(1L, 2L));
        verifyNoInteractions(userPrivacyService);
    }

    @Test
    void chatListHidesPresenceOfPeerWhoTurnedOffOnlineStatus() {
        PageRequest pageable = PageRequest.of(0, 30);
        ChatRoom privateRoom = new ChatRoom();
        privateRoom.setId(20L);
        privateRoom.setRoomType(ChatRoom.RoomType.PRIVATE);
        when(chatRoomRepository.findByUserIdWithDisplayState(1L, false, false, null, pageable))
                .thenReturn(new PageImpl<>(List.of(privateRoom), pageable, 1));
        ChatRoomRepository.PrivateRoomParticipantProjection self =
                mock(ChatRoomRepository.PrivateRoomParticipantProjection.class);
        when(self.getRoomId()).thenReturn(20L);
        when(self.getUserId()).thenReturn(1L);
        when(self.getOnlineStatus()).thenReturn(User.OnlineStatus.ONLINE);
        ChatRoomRepository.PrivateRoomParticipantProjection peer =
                mock(ChatRoomRepository.PrivateRoomParticipantProjection.class);
        when(peer.getRoomId()).thenReturn(20L);
        when(peer.getUserId()).thenReturn(2L);
        // 对方其实在线；隐藏后这些值根本不该被读出来。
        lenient().when(peer.getOnlineStatus()).thenReturn(User.OnlineStatus.ONLINE);
        lenient().when(peer.getLastSeen()).thenReturn(LocalDateTime.of(2026, 9, 24, 10, 0));
        when(chatRoomRepository.findPrivateParticipantsByRoomIds(List.of(20L))).thenReturn(List.of(self, peer));
        when(userPrivacyService.usersHidingOnlineStatus(List.of(2L))).thenReturn(Set.of(2L));

        ChatRoomSummaryDto summary = chatRoomService
                .getUserChatRoomSummaries(1L, pageable, false, false, null)
                .getContent().get(0);

        ChatRoomParticipantDto me = summary.getParticipants().get(0);
        ChatRoomParticipantDto other = summary.getParticipants().get(1);
        assertEquals(User.OnlineStatus.ONLINE, me.getOnlineStatus());
        assertEquals(User.OnlineStatus.OFFLINE, other.getOnlineStatus());
        assertNull(other.getLastSeen());
    }

    private User user(Long id, String name) {
        User user = new User();
        user.setId(id);
        user.setUsername(name);
        user.setDisplayName(name);
        return user;
    }
}
