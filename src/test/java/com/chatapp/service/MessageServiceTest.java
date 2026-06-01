package com.chatapp.service;

import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomMember;
import com.chatapp.entity.ChatRoomPinnedMessage;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.ChatRoomClearStateRepository;
import com.chatapp.repository.ChatRoomPinnedMessageRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.MessageStarRepository;
import com.chatapp.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private ChatRoomClearStateRepository clearStateRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ChatRoomPinnedMessageRepository pinnedMessageRepository;

    @Mock
    private MessageStarRepository messageStarRepository;

    @InjectMocks
    private MessageService messageService;

    // ---- Helper methods ----

    private User createTestUser(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    private ChatRoom createTestChatRoom(Long id, User creator) {
        ChatRoom room = new ChatRoom();
        room.setId(id);
        room.setName("Test Room");
        room.setCreatedBy(creator);
        room.setRoomType(ChatRoom.RoomType.GROUP);
        return room;
    }

    private Message createTestMessage(Long id, User sender, ChatRoom room) {
        Message msg = new Message();
        msg.setId(id);
        msg.setContent("Test message");
        msg.setSender(sender);
        msg.setChatRoom(room);
        msg.setMessageType(Message.MessageType.TEXT);
        msg.setIsDeleted(false);
        msg.setCreatedAt(LocalDateTime.now());
        return msg;
    }

    private void addMember(ChatRoom room, User user, String nickname) {
        ChatRoomMember member = new ChatRoomMember();
        member.setId(user.getId());
        member.setChatRoom(room);
        member.setUser(user);
        member.setNickname(nickname);
        room.getMembers().add(member);
    }

    // ---- sendMessage ----

    @Test
    void testSendMessage_Success() {
        User sender = createTestUser(1L, "sender");
        ChatRoom room = createTestChatRoom(10L, sender);

        when(userRepository.findById(1L)).thenReturn(Optional.of(sender));
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(room));
        when(chatRoomRepository.isMember(10L, 1L)).thenReturn(true);
        when(chatRoomRepository.isMuted(10L, 1L)).thenReturn(false);
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            m.setId(100L);
            return m;
        });

        Message result = messageService.sendMessage(1L, 10L, "Hello", Message.MessageType.TEXT);

        assertNotNull(result);
        assertEquals("Hello", result.getContent());
        assertEquals(Message.MessageType.TEXT, result.getMessageType());
        assertSame(sender, result.getSender());
        assertSame(room, result.getChatRoom());
        verify(messageRepository).save(any(Message.class));
        verify(chatRoomRepository).incrementUnreadForRoomMembersExcept(10L, 1L);
    }

    @Test
    void testSendMessage_NotMember() {
        User sender = createTestUser(1L, "sender");
        ChatRoom room = createTestChatRoom(10L, sender);

        when(userRepository.findById(1L)).thenReturn(Optional.of(sender));
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(room));
        when(chatRoomRepository.isMember(10L, 1L)).thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> messageService.sendMessage(1L, 10L, "Hello", Message.MessageType.TEXT));

        assertTrue(ex.getMessage().contains("不是该聊天室的成员"));
    }

    @Test
    void testSendMessage_Muted() {
        User sender = createTestUser(1L, "sender");
        ChatRoom room = createTestChatRoom(10L, sender);

        when(userRepository.findById(1L)).thenReturn(Optional.of(sender));
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(room));
        when(chatRoomRepository.isMember(10L, 1L)).thenReturn(true);
        when(chatRoomRepository.isMuted(10L, 1L)).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> messageService.sendMessage(1L, 10L, "Hello", Message.MessageType.TEXT));

        assertTrue(ex.getMessage().contains("禁言"));
    }

    @Test
    void testSendMessage_StoresMentionedUserIds() {
        User sender = createTestUser(1L, "sender");
        sender.setDisplayName("发送者");
        User target = createTestUser(2L, "luming");
        target.setDisplayName("陆铭");
        ChatRoom room = createTestChatRoom(10L, sender);
        addMember(room, sender, null);
        addMember(room, target, "老陆");

        when(userRepository.findById(1L)).thenReturn(Optional.of(sender));
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(room));
        when(chatRoomRepository.isMember(10L, 1L)).thenReturn(true);
        when(chatRoomRepository.isMuted(10L, 1L)).thenReturn(false);
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            m.setId(100L);
            return m;
        });

        Message result = messageService.sendMessage(
                1L,
                10L,
                "hi @陆铭 @老陆 @sender @不存在",
                Message.MessageType.TEXT);

        assertEquals(Set.of(1L, 2L), result.getMentionedUserIds());
    }

    @Test
    void testResolveMentionedUserIds_EdgeCases() {
        User sender = createTestUser(1L, "sender");
        sender.setDisplayName("Me");
        User target = createTestUser(2L, "alice");
        target.setDisplayName("Alice");
        ChatRoom room = createTestChatRoom(10L, sender);
        addMember(room, sender, null);
        addMember(room, target, null);

        assertEquals(Set.of(), messageService.resolveMentionedUserIds("@", room));
        assertEquals(Set.of(), messageService.resolveMentionedUserIds("\\@Alice", room));
        assertEquals(Set.of(), messageService.resolveMentionedUserIds("@Missing", room));
        assertEquals(Set.of(1L), messageService.resolveMentionedUserIds("@Me", room));
        assertEquals(Set.of(2L), messageService.resolveMentionedUserIds("@alice,", room));
    }

    // ---- sendFileMessage ----

    @Test
    void testSendFileMessage_Success() {
        User sender = createTestUser(1L, "sender");
        ChatRoom room = createTestChatRoom(10L, sender);

        when(userRepository.findById(1L)).thenReturn(Optional.of(sender));
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(room));
        when(chatRoomRepository.isMember(10L, 1L)).thenReturn(true);
        when(chatRoomRepository.isMuted(10L, 1L)).thenReturn(false);
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            m.setId(101L);
            return m;
        });

        Message result = messageService.sendFileMessage(1L, 10L, "doc.pdf",
                "http://files/doc.pdf", "application/pdf", 1024L, Message.MessageType.FILE);

        assertNotNull(result);
        assertEquals("doc.pdf", result.getFileName());
        assertEquals("http://files/doc.pdf", result.getFileUrl());
        assertEquals("application/pdf", result.getFileType());
        assertEquals(1024L, result.getFileSize());
        assertEquals(Message.MessageType.FILE, result.getMessageType());
        verify(messageRepository).save(any(Message.class));
        verify(chatRoomRepository).incrementUnreadForRoomMembersExcept(10L, 1L);
    }

    // ---- replyToMessage ----

    @Test
    void testReplyToMessage_Success() {
        User sender = createTestUser(1L, "sender");
        ChatRoom room = createTestChatRoom(10L, sender);
        Message originalMsg = createTestMessage(50L, sender, room);

        when(messageRepository.findWithSenderById(50L)).thenReturn(Optional.of(originalMsg));
        // sendMessage internals
        when(userRepository.findById(1L)).thenReturn(Optional.of(sender));
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(room));
        when(chatRoomRepository.isMember(10L, 1L)).thenReturn(true);
        when(chatRoomRepository.isMuted(10L, 1L)).thenReturn(false);
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            if (m.getId() == null) m.setId(102L);
            return m;
        });

        Message result = messageService.replyToMessage(1L, 10L, 50L, "Reply text", Message.MessageType.TEXT);

        assertNotNull(result);
        assertSame(originalMsg, result.getReplyToMessage());
        verify(messageRepository, atLeast(2)).save(any(Message.class));
    }

    @Test
    void testReplyToMessage_DifferentRoom() {
        User sender = createTestUser(1L, "sender");
        ChatRoom room1 = createTestChatRoom(10L, sender);
        ChatRoom room2 = createTestChatRoom(20L, sender);
        Message originalMsg = createTestMessage(50L, sender, room2); // in room2

        when(messageRepository.findWithSenderById(50L)).thenReturn(Optional.of(originalMsg));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> messageService.replyToMessage(1L, 10L, 50L, "Reply", Message.MessageType.TEXT));

        assertTrue(ex.getMessage().contains("同一聊天室"));
    }

    // ---- getChatRoomMessages ----

    @Test
    void testGetChatRoomMessages_AsMember() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<Message> page = new PageImpl<>(Collections.emptyList());

        when(chatRoomRepository.isMember(10L, 1L)).thenReturn(true);
        when(clearStateRepository.findByUserIdAndChatRoomId(1L, 10L)).thenReturn(Optional.empty());
        when(messageRepository.findByChatRoomIdOrderByCreatedAtDesc(10L, pageable)).thenReturn(page);

        Page<Message> result = messageService.getChatRoomMessages(10L, 1L, pageable);

        assertNotNull(result);
        verify(messageRepository).findByChatRoomIdOrderByCreatedAtDesc(10L, pageable);
    }

    @Test
    void testGetChatRoomMessages_NotMember() {
        Pageable pageable = PageRequest.of(0, 20);

        when(chatRoomRepository.isMember(10L, 99L)).thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> messageService.getChatRoomMessages(10L, 99L, pageable));

        assertTrue(ex.getMessage().contains("不是该聊天室的成员"));
    }

    // ---- markMessageAsRead ----

    @Test
    void testMarkMessageAsRead_Success() {
        User sender = createTestUser(1L, "sender");
        ChatRoom room = createTestChatRoom(10L, sender);
        Message msg = createTestMessage(50L, sender, room);

        when(messageRepository.findById(50L)).thenReturn(Optional.of(msg));
        when(chatRoomRepository.isMember(10L, 2L)).thenReturn(true);

        messageService.markMessageAsRead(50L, 2L);

        verify(messageRepository).markAsRead(50L, 2L);
        verify(chatRoomRepository).markMessageReadForMember(10L, 2L, 50L);
    }

    @Test
    void testMarkMessageAsRead_OwnMessage() {
        User sender = createTestUser(1L, "sender");
        ChatRoom room = createTestChatRoom(10L, sender);
        Message msg = createTestMessage(50L, sender, room);

        when(messageRepository.findById(50L)).thenReturn(Optional.of(msg));
        when(chatRoomRepository.isMember(10L, 1L)).thenReturn(true);

        messageService.markMessageAsRead(50L, 1L);

        verify(messageRepository, never()).markAsRead(anyLong(), anyLong());
    }

    // ---- recallMessage ----

    @Test
    void testRecallMessage_Success() {
        User sender = createTestUser(1L, "sender");
        ChatRoom room = createTestChatRoom(10L, sender);
        Message msg = createTestMessage(50L, sender, room);
        msg.setCreatedAt(LocalDateTime.now().minusSeconds(30)); // within 2 min

        when(messageRepository.findWithSenderById(50L)).thenReturn(Optional.of(msg));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        messageService.recallMessage(50L, 1L);

        assertTrue(msg.getIsDeleted());
        assertEquals("[消息已撤回]", msg.getContent());
        verify(messageRepository).save(msg);
    }

    @Test
    void testRecallMessage_NotSender() {
        User sender = createTestUser(1L, "sender");
        ChatRoom room = createTestChatRoom(10L, sender);
        Message msg = createTestMessage(50L, sender, room);

        when(messageRepository.findWithSenderById(50L)).thenReturn(Optional.of(msg));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> messageService.recallMessage(50L, 2L));

        assertTrue(ex.getMessage().contains("自己的消息"));
    }

    @Test
    void testRecallMessage_TooLate() {
        User sender = createTestUser(1L, "sender");
        ChatRoom room = createTestChatRoom(10L, sender);
        Message msg = createTestMessage(50L, sender, room);
        msg.setCreatedAt(LocalDateTime.now().minusMinutes(5)); // past 2 min

        when(messageRepository.findWithSenderById(50L)).thenReturn(Optional.of(msg));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> messageService.recallMessage(50L, 1L));

        assertTrue(ex.getMessage().contains("2分钟"));
    }

    @Test
    void editMessage_updatesTextAndMarksEdited() {
        User sender = createTestUser(1L, "sender");
        ChatRoom room = createTestChatRoom(10L, sender);
        Message msg = createTestMessage(50L, sender, room);

        when(messageRepository.findWithSenderById(50L)).thenReturn(Optional.of(msg));
        when(chatRoomRepository.isMember(10L, 1L)).thenReturn(true);
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        Message result = messageService.editMessage(50L, 1L, "updated");

        assertEquals("updated", result.getContent());
        assertTrue(result.getIsEdited());
        verify(messageRepository).save(msg);
    }

    @Test
    void editMessage_rejectsOtherUser() {
        User sender = createTestUser(1L, "sender");
        ChatRoom room = createTestChatRoom(10L, sender);
        Message msg = createTestMessage(50L, sender, room);

        when(messageRepository.findWithSenderById(50L)).thenReturn(Optional.of(msg));
        when(chatRoomRepository.isMember(10L, 2L)).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> messageService.editMessage(50L, 2L, "bad"));
    }

    @Test
    void forwardMessage_copiesContentIntoTargetRoom() {
        User sender = createTestUser(1L, "sender");
        ChatRoom sourceRoom = createTestChatRoom(10L, sender);
        ChatRoom targetRoom = createTestChatRoom(20L, sender);
        Message source = createTestMessage(50L, sender, sourceRoom);

        when(messageRepository.findWithSenderById(50L)).thenReturn(Optional.of(source));
        when(chatRoomRepository.isMember(10L, 1L)).thenReturn(true);
        when(chatRoomRepository.isMember(20L, 1L)).thenReturn(true);
        when(chatRoomRepository.isMuted(20L, 1L)).thenReturn(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(sender));
        when(chatRoomRepository.findById(20L)).thenReturn(Optional.of(targetRoom));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> {
            Message saved = inv.getArgument(0);
            saved.setId(99L);
            return saved;
        });

        Message forwarded = messageService.forwardMessage(50L, 1L, 20L);

        assertEquals("Test message", forwarded.getContent());
        assertEquals(20L, forwarded.getChatRoom().getId());
        assertEquals(50L, forwarded.getForwardedFromMessage().getId());
        verify(chatRoomRepository).incrementUnreadForRoomMembersExcept(20L, 1L);
    }

    @Test
    void pinMessage_requiresAdminInGroupAndSavesPin() {
        User sender = createTestUser(1L, "sender");
        ChatRoom room = createTestChatRoom(10L, sender);
        Message msg = createTestMessage(50L, sender, room);

        when(messageRepository.findWithSenderById(50L)).thenReturn(Optional.of(msg));
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(room));
        when(chatRoomRepository.isMember(10L, 2L)).thenReturn(true);
        when(chatRoomRepository.isAdmin(10L, 2L)).thenReturn(true);
        when(pinnedMessageRepository.findByChatRoomIdAndMessageId(10L, 50L))
                .thenReturn(Optional.empty());
        when(userRepository.findById(2L)).thenReturn(Optional.of(createTestUser(2L, "admin")));
        when(pinnedMessageRepository.save(any(ChatRoomPinnedMessage.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ChatRoomPinnedMessage pin = messageService.pinMessage(10L, 50L, 2L);

        assertEquals(50L, pin.getMessage().getId());
        verify(pinnedMessageRepository).save(any(ChatRoomPinnedMessage.class));
    }

    @Test
    void starMessage_isIdempotentForExistingStar() {
        User sender = createTestUser(1L, "sender");
        ChatRoom room = createTestChatRoom(10L, sender);
        Message msg = createTestMessage(50L, sender, room);

        when(messageRepository.findWithSenderById(50L)).thenReturn(Optional.of(msg));
        when(chatRoomRepository.isMember(10L, 1L)).thenReturn(true);
        when(messageStarRepository.findByMessageIdAndUserId(50L, 1L))
                .thenReturn(Optional.of(new com.chatapp.entity.MessageStar()));

        Message result = messageService.starMessage(50L, 1L);

        assertEquals(50L, result.getId());
        verify(messageStarRepository, never()).save(any());
    }

    // ---- deleteMessage ----

    @Test
    void testDeleteMessage_ByAdmin() {
        User sender = createTestUser(1L, "sender");
        User admin = createTestUser(2L, "admin");
        ChatRoom room = createTestChatRoom(10L, sender);
        Message msg = createTestMessage(50L, sender, room);

        when(messageRepository.findWithSenderById(50L)).thenReturn(Optional.of(msg));
        when(chatRoomRepository.isAdmin(10L, 2L)).thenReturn(true);
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        messageService.deleteMessage(50L, 2L);

        assertTrue(msg.getIsDeleted());
        assertEquals("[消息已删除]", msg.getContent());
        verify(messageRepository).save(msg);
    }

    @Test
    void testDeleteMessage_NoPermission() {
        User sender = createTestUser(1L, "sender");
        ChatRoom room = createTestChatRoom(10L, sender);
        Message msg = createTestMessage(50L, sender, room);

        when(messageRepository.findWithSenderById(50L)).thenReturn(Optional.of(msg));
        when(chatRoomRepository.isAdmin(10L, 3L)).thenReturn(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> messageService.deleteMessage(50L, 3L));

        assertTrue(ex.getMessage().contains("无权限"));
    }
}
