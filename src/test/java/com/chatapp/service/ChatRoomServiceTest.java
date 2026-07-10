package com.chatapp.service;

import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomBot;
import com.chatapp.entity.ChatRoomMember;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.dto.ChatRoomSummaryDto;
import com.chatapp.repository.BotConfigRepository;
import com.chatapp.repository.ChatRoomBotRepository;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatRoomServiceTest {

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private BotConfigRepository botConfigRepository;

    @Mock
    private ChatRoomBotRepository chatRoomBotRepository;

    @InjectMocks
    private ChatRoomService chatRoomService;

    private User user1;
    private User user2;
    private ChatRoom groupRoom;
    private ChatRoom privateRoom;

    @BeforeEach
    void setUp() {
        user1 = new User();
        user1.setId(1L);
        user1.setUsername("user1");
        user1.setDisplayName("User One");

        user2 = new User();
        user2.setId(2L);
        user2.setUsername("user2");
        user2.setDisplayName("User Two");

        groupRoom = new ChatRoom();
        groupRoom.setId(10L);
        groupRoom.setName("Test Group");
        groupRoom.setRoomType(ChatRoom.RoomType.GROUP);
        groupRoom.setCreatedBy(user1);
        groupRoom.setIsPrivate(false);
        groupRoom.setMaxMembers(500);
        groupRoom.setMembers(new HashSet<>());

        privateRoom = new ChatRoom();
        privateRoom.setId(20L);
        privateRoom.setName("User One & User Two");
        privateRoom.setRoomType(ChatRoom.RoomType.PRIVATE);
        privateRoom.setCreatedBy(user1);
        privateRoom.setIsPrivate(true);
        privateRoom.setMaxMembers(2);
        privateRoom.setMembers(new HashSet<>());
    }

    // ---- createPrivateChat ----

    @Test
    void testCreatePrivateChat_New() {
        BotConfig agent = new BotConfig();
        agent.setId(7L);
        agent.setBotName("Agent");
        when(chatRoomRepository.findPrivateChatBetween(1L, 2L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(botConfigRepository.findFirstByBotNameAndCreatedByIsNullOrderByIdAsc("Agent"))
                .thenReturn(Optional.of(agent));
        when(chatRoomBotRepository.findByChatRoomIdAndBotConfigId(100L, 7L))
                .thenReturn(Optional.empty());
        when(chatRoomBotRepository.save(any(ChatRoomBot.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chatRoomRepository.save(any(ChatRoom.class))).thenAnswer(invocation -> {
            ChatRoom room = invocation.getArgument(0);
            if (room.getId() == null) {
                room.setId(100L);
            }
            return room;
        });
        // addMemberToRoom internally calls findById for both the room and user
        when(chatRoomRepository.findById(100L)).thenAnswer(inv -> {
            ChatRoom room = new ChatRoom();
            room.setId(100L);
            room.setName("Test");
            room.setRoomType(ChatRoom.RoomType.PRIVATE);
            room.setIsPrivate(true);
            room.setMaxMembers(2);
            room.setMembers(new HashSet<>());
            return Optional.of(room);
        });

        ChatRoom result = chatRoomService.createPrivateChat(1L, 2L);

        assertNotNull(result);
        assertEquals(ChatRoom.RoomType.PRIVATE, result.getRoomType());
        assertTrue(result.getIsPrivate());
        assertEquals(2, result.getMaxMembers());
        verify(chatRoomRepository, atLeastOnce()).save(any(ChatRoom.class));
        ArgumentCaptor<ChatRoomBot> binding = ArgumentCaptor.forClass(ChatRoomBot.class);
        verify(chatRoomBotRepository).save(binding.capture());
        assertEquals(100L, binding.getValue().getChatRoom().getId());
        assertEquals("Agent", binding.getValue().getBotConfig().getBotName());
        assertTrue(binding.getValue().getIsActive());
        assertEquals(ChatRoomBot.TriggerMode.MENTION, binding.getValue().getTriggerMode());
    }

    @Test
    void testCreatePrivateChat_AlreadyExists() {
        when(chatRoomRepository.findPrivateChatBetween(1L, 2L)).thenReturn(Optional.of(privateRoom));

        ChatRoom result = chatRoomService.createPrivateChat(1L, 2L);

        assertSame(privateRoom, result);
        verify(chatRoomRepository, never()).save(any(ChatRoom.class));
        verify(userRepository, never()).findById(anyLong());
    }

    // ---- createGroupChat ----

    @Test
    void testCreateGroupChat() {
        BotConfig agent = new BotConfig();
        agent.setId(7L);
        agent.setBotName("Agent");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(botConfigRepository.findFirstByBotNameAndCreatedByIsNullOrderByIdAsc("Agent"))
                .thenReturn(Optional.of(agent));
        when(chatRoomBotRepository.findByChatRoomIdAndBotConfigId(200L, 7L))
                .thenReturn(Optional.empty());
        when(chatRoomBotRepository.save(any(ChatRoomBot.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chatRoomRepository.save(any(ChatRoom.class))).thenAnswer(invocation -> {
            ChatRoom room = invocation.getArgument(0);
            if (room.getId() == null) {
                room.setId(200L);
            }
            return room;
        });
        // addMemberToRoom internally calls findById for the room
        when(chatRoomRepository.findById(200L)).thenAnswer(inv -> {
            ChatRoom room = new ChatRoom();
            room.setId(200L);
            room.setName("My Group");
            room.setRoomType(ChatRoom.RoomType.GROUP);
            room.setMembers(new HashSet<>());
            return Optional.of(room);
        });

        ChatRoom result = chatRoomService.createGroupChat(1L, "My Group", "A description", Arrays.asList(1L, 2L));

        assertNotNull(result);
        assertEquals(ChatRoom.RoomType.GROUP, result.getRoomType());
        assertEquals("My Group", result.getName());
        verify(chatRoomRepository, atLeastOnce()).save(any(ChatRoom.class));
        ArgumentCaptor<ChatRoomBot> binding = ArgumentCaptor.forClass(ChatRoomBot.class);
        verify(chatRoomBotRepository).save(binding.capture());
        assertEquals(ChatRoomBot.TriggerMode.MENTION, binding.getValue().getTriggerMode());
        assertEquals("Agent", binding.getValue().getRoomNickname());
    }

    // ---- joinChatRoom ----

    @Test
    void testJoinChatRoom_Success() {
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(groupRoom));
        when(chatRoomRepository.isMember(10L, 2L)).thenReturn(false);
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(chatRoomRepository.save(any(ChatRoom.class))).thenReturn(groupRoom);

        assertDoesNotThrow(() -> chatRoomService.joinChatRoom(10L, 2L));

        verify(chatRoomRepository, atLeastOnce()).save(any(ChatRoom.class));
    }

    @Test
    void testJoinChatRoom_AlreadyMember() {
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(groupRoom));
        when(chatRoomRepository.isMember(10L, 2L)).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> chatRoomService.joinChatRoom(10L, 2L));

        assertTrue(ex.getMessage().contains("已经是聊天室成员"));
    }

    @Test
    void testJoinChatRoom_PrivateRoom() {
        when(chatRoomRepository.findById(20L)).thenReturn(Optional.of(privateRoom));
        when(chatRoomRepository.isMember(20L, 2L)).thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> chatRoomService.joinChatRoom(20L, 2L));

        assertTrue(ex.getMessage().contains("私有聊天室"));
    }

    // ---- leaveChatRoom ----

    @Test
    void testLeaveChatRoom_Success() {
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(groupRoom));
        when(chatRoomRepository.isMember(10L, 2L)).thenReturn(true);

        assertDoesNotThrow(() -> chatRoomService.leaveChatRoom(10L, 2L));

        verify(chatRoomRepository).removeMember(10L, 2L);
    }

    @Test
    void testLeaveChatRoom_PrivateChat() {
        when(chatRoomRepository.findById(20L)).thenReturn(Optional.of(privateRoom));
        when(chatRoomRepository.isMember(20L, 1L)).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> chatRoomService.leaveChatRoom(20L, 1L));

        assertTrue(ex.getMessage().contains("私聊"));
    }

    // ---- getChatRoomDetails ----

    @Test
    void testGetChatRoomDetails_MemberAccess() {
        when(chatRoomRepository.findById(20L)).thenReturn(Optional.of(privateRoom));
        when(chatRoomRepository.isMember(20L, 1L)).thenReturn(true);

        ChatRoom result = chatRoomService.getChatRoomDetails(20L, 1L);

        assertSame(privateRoom, result);
    }

    @Test
    void testGetChatRoomDetails_NonMemberPrivate() {
        when(chatRoomRepository.findById(20L)).thenReturn(Optional.of(privateRoom));
        when(chatRoomRepository.isMember(20L, 99L)).thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> chatRoomService.getChatRoomDetails(20L, 99L));

        assertTrue(ex.getMessage().contains("无权限"));
    }

    @Test
    void getUserChatRooms_defaultsToVisibleMessageStreamOnly() {
        Page<ChatRoom> page = new PageImpl<>(List.of(groupRoom));
        PageRequest pageable = PageRequest.of(0, 20);
        when(chatRoomRepository.findByUserIdWithDisplayState(1L, false, false, null, pageable))
                .thenReturn(page);

        Page<ChatRoom> result = chatRoomService.getUserChatRooms(1L, pageable);

        assertSame(page, result);
        verify(chatRoomRepository).findByUserIdWithDisplayState(1L, false, false, null, pageable);
    }

    @Test
    void getUserChatRooms_canIncludeHiddenBlockedAndFilterTypeForContactsTab() {
        Page<ChatRoom> page = new PageImpl<>(List.of(groupRoom));
        PageRequest pageable = PageRequest.of(0, 20);
        when(chatRoomRepository.findByUserIdWithDisplayState(
                1L,
                true,
                true,
                ChatRoom.RoomType.GROUP,
                pageable)).thenReturn(page);

        Page<ChatRoom> result = chatRoomService.getUserChatRooms(
                1L,
                pageable,
                true,
                true,
                ChatRoom.RoomType.GROUP);

        assertSame(page, result);
        verify(chatRoomRepository).findByUserIdWithDisplayState(
                1L,
                true,
                true,
                ChatRoom.RoomType.GROUP,
                pageable);
    }

    @Test
    void getUserChatRoomSummaries_batchesListDecorations() {
        PageRequest pageable = PageRequest.of(0, 30);
        groupRoom.setCreatedAt(LocalDateTime.of(2026, 7, 10, 10, 0));
        groupRoom.setUpdatedAt(LocalDateTime.of(2026, 7, 10, 10, 5));
        privateRoom.setCreatedAt(LocalDateTime.of(2026, 7, 10, 9, 0));
        privateRoom.setUpdatedAt(LocalDateTime.of(2026, 7, 10, 9, 5));
        Page<ChatRoom> roomPage = new PageImpl<>(List.of(groupRoom, privateRoom), pageable, 2);

        ChatRoomMember groupMembership = new ChatRoomMember();
        groupMembership.setChatRoom(groupRoom);
        groupMembership.setUser(user1);
        groupMembership.setUnreadCount(4);
        groupMembership.setIsPinned(true);
        groupMembership.setIsNotificationMuted(true);
        ChatRoomMember privateMembership = new ChatRoomMember();
        privateMembership.setChatRoom(privateRoom);
        privateMembership.setUser(user1);
        privateMembership.setUnreadCount(1);

        ChatRoomRepository.RoomMemberCountProjection groupCount =
                mock(ChatRoomRepository.RoomMemberCountProjection.class);
        when(groupCount.getRoomId()).thenReturn(10L);
        when(groupCount.getMemberCount()).thenReturn(7L);
        ChatRoomRepository.RoomMemberCountProjection privateCount =
                mock(ChatRoomRepository.RoomMemberCountProjection.class);
        when(privateCount.getRoomId()).thenReturn(20L);
        when(privateCount.getMemberCount()).thenReturn(2L);

        ChatRoomRepository.PrivateRoomParticipantProjection peer =
                mock(ChatRoomRepository.PrivateRoomParticipantProjection.class);
        when(peer.getRoomId()).thenReturn(20L);
        when(peer.getUserId()).thenReturn(2L);
        when(peer.getUsername()).thenReturn("user2");
        when(peer.getDisplayName()).thenReturn("User Two");
        when(peer.getOnlineStatus()).thenReturn(User.OnlineStatus.ONLINE);

        Message latest = new Message();
        latest.setId(88L);
        latest.setContent("latest room message");
        latest.setSender(user2);
        latest.setChatRoom(groupRoom);
        latest.setMessageType(Message.MessageType.TEXT);
        latest.setMessageStatus(Message.MessageStatus.SENT);
        latest.setCreatedAt(LocalDateTime.of(2026, 7, 10, 10, 6));

        when(chatRoomRepository.findByUserIdWithDisplayState(
                1L, false, false, null, pageable)).thenReturn(roomPage);
        when(chatRoomRepository.findMembershipsByUserIdAndRoomIds(1L, List.of(10L, 20L)))
                .thenReturn(List.of(groupMembership, privateMembership));
        when(chatRoomRepository.countMembersByRoomIds(List.of(10L, 20L)))
                .thenReturn(List.of(groupCount, privateCount));
        when(chatRoomRepository.findPrivateParticipantsByRoomIds(List.of(10L, 20L)))
                .thenReturn(List.of(peer));
        when(messageRepository.findLatestVisibleMessagesForRooms(1L, List.of(10L, 20L)))
                .thenReturn(List.of(latest));

        Page<ChatRoomSummaryDto> result = chatRoomService.getUserChatRoomSummaries(
                1L, pageable, false, false, null);

        assertEquals(2, result.getTotalElements());
        ChatRoomSummaryDto group = result.getContent().get(0);
        assertEquals(7, group.getMemberCount());
        assertEquals(4, group.getUnreadCount());
        assertTrue(group.isPinned());
        assertTrue(group.isMuted());
        assertEquals("latest room message", group.getLastMessage().getContent());
        ChatRoomSummaryDto direct = result.getContent().get(1);
        assertEquals(2, direct.getMemberCount());
        assertEquals("User Two", direct.getParticipants().get(0).getDisplayName());

        verify(chatRoomRepository, times(1))
                .findMembershipsByUserIdAndRoomIds(1L, List.of(10L, 20L));
        verify(chatRoomRepository, times(1)).countMembersByRoomIds(List.of(10L, 20L));
        verify(chatRoomRepository, times(1))
                .findPrivateParticipantsByRoomIds(List.of(10L, 20L));
        verify(messageRepository, times(1))
                .findLatestVisibleMessagesForRooms(1L, List.of(10L, 20L));
        verify(messageRepository, never()).findLatestMessageByChatRoomId(anyLong(), any());
    }

    @Test
    void updateDisplayState_clearStoresLastMessageIdOnMember() {
        ChatRoomMember member = new ChatRoomMember();
        member.setChatRoom(groupRoom);
        member.setUser(user1);
        Message last = new Message();
        last.setId(88L);

        when(chatRoomRepository.isMember(10L, 1L)).thenReturn(true);
        when(messageRepository.findLastMessage(10L)).thenReturn(last);
        when(chatRoomRepository.findMember(10L, 1L)).thenReturn(Optional.of(member));

        ChatRoomMember result = chatRoomService.updateDisplayState(10L, 1L, "CLEAR");

        assertSame(member, result);
        verify(chatRoomRepository).updateClearedBeforeMessageId(10L, 1L, 88L);
        verify(chatRoomRepository, never()).removeMember(anyLong(), anyLong());
    }

    @Test
    void updateDisplayState_hideRemovesOnlyFromMessageStream() {
        ChatRoomMember member = new ChatRoomMember();
        member.setChatRoom(groupRoom);
        member.setUser(user1);

        when(chatRoomRepository.isMember(10L, 1L)).thenReturn(true);
        when(chatRoomRepository.findMember(10L, 1L)).thenReturn(Optional.of(member));

        chatRoomService.updateDisplayState(10L, 1L, "REMOVE_FROM_LIST");

        verify(chatRoomRepository).hideRoomForMember(eq(10L), eq(1L), any(LocalDateTime.class));
        verify(chatRoomRepository, never()).removeMember(anyLong(), anyLong());
    }

    @Test
    void updateDisplayState_blockAndUnblockArePerMember() {
        ChatRoomMember member = new ChatRoomMember();
        member.setChatRoom(groupRoom);
        member.setUser(user1);

        when(chatRoomRepository.isMember(10L, 1L)).thenReturn(true);
        when(chatRoomRepository.findMember(10L, 1L)).thenReturn(Optional.of(member));

        chatRoomService.updateDisplayState(10L, 1L, "BLOCK");
        chatRoomService.updateDisplayState(10L, 1L, "UNBLOCK");

        verify(chatRoomRepository).blockRoomForMember(eq(10L), eq(1L), any(LocalDateTime.class));
        verify(chatRoomRepository).unblockRoomForMember(10L, 1L);
        verify(chatRoomRepository, never()).removeMember(anyLong(), anyLong());
    }

    // ---- updateChatRoom ----

    @Test
    void testUpdateChatRoom_AsAdmin() {
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(groupRoom));
        when(chatRoomRepository.isAdmin(10L, 1L)).thenReturn(true);
        when(chatRoomRepository.save(any(ChatRoom.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatRoom result = chatRoomService.updateChatRoom(10L, 1L, "New Name", "New Desc", null);

        assertEquals("New Name", result.getName());
        assertEquals("New Desc", result.getDescription());
        verify(chatRoomRepository).save(any(ChatRoom.class));
    }

    @Test
    void testUpdateChatRoom_NotAdmin() {
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(groupRoom));
        when(chatRoomRepository.isAdmin(10L, 2L)).thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> chatRoomService.updateChatRoom(10L, 2L, "New Name", "Desc", null));

        assertTrue(ex.getMessage().contains("无权限"));
    }

    // ---- kickMember ----

    @Test
    void testKickMember_Success() {
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(groupRoom));
        when(chatRoomRepository.isAdmin(10L, 1L)).thenReturn(true);

        assertDoesNotThrow(() -> chatRoomService.kickMember(10L, 1L, 2L));

        verify(chatRoomRepository).removeMember(10L, 2L);
    }

    @Test
    void testKickMember_CantKickCreator() {
        // groupRoom.createdBy is user1 (id=1)
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(groupRoom));
        when(chatRoomRepository.isAdmin(10L, 2L)).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> chatRoomService.kickMember(10L, 2L, 1L));

        assertTrue(ex.getMessage().contains("创建者"));
    }

    // ---- deleteChatRoom ----

    @Test
    void testDeleteChatRoom_ByCreator() {
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(groupRoom));
        when(chatRoomRepository.isOwner(10L, 1L)).thenReturn(true);

        assertDoesNotThrow(() -> chatRoomService.deleteChatRoom(10L, 1L));

        verify(chatRoomRepository).delete(groupRoom);
    }

    @Test
    void testDeleteChatRoom_NotCreator() {
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(groupRoom));
        when(chatRoomRepository.isOwner(10L, 2L)).thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> chatRoomService.deleteChatRoom(10L, 2L));

        assertTrue(ex.getMessage().contains("群主"));
    }

    @Test
    void testDeleteChatRoom_PrivateChat() {
        when(chatRoomRepository.findById(20L)).thenReturn(Optional.of(privateRoom));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> chatRoomService.deleteChatRoom(20L, 1L));

        assertTrue(ex.getMessage().contains("私聊"));
    }
}
