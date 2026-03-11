package com.chatapp.service;

import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomMember;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.HashSet;
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
        when(chatRoomRepository.findPrivateChatBetween(1L, 2L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
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
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
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

        assertDoesNotThrow(() -> chatRoomService.deleteChatRoom(10L, 1L));

        verify(chatRoomRepository).delete(groupRoom);
    }

    @Test
    void testDeleteChatRoom_NotCreator() {
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(groupRoom));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> chatRoomService.deleteChatRoom(10L, 2L));

        assertTrue(ex.getMessage().contains("创建者"));
    }

    @Test
    void testDeleteChatRoom_PrivateChat() {
        when(chatRoomRepository.findById(20L)).thenReturn(Optional.of(privateRoom));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> chatRoomService.deleteChatRoom(20L, 1L));

        assertTrue(ex.getMessage().contains("私聊"));
    }
}
