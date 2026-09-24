package com.chatapp.service;

import com.chatapp.dto.ReadReceiptDto;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.Message;
import com.chatapp.entity.MessageReadReceipt;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomPinnedMessageRepository;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageReadReceiptRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.MessageStarRepository;
import com.chatapp.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** "已读回执"关闭后，读消息只清自己的未读，不产生别人可见的已读状态。 */
@ExtendWith(MockitoExtension.class)
class MessageServiceReadReceiptPrivacyTest {

    @Mock private MessageRepository messageRepository;
    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private UserRepository userRepository;
    @Mock private ChatRoomPinnedMessageRepository pinnedMessageRepository;
    @Mock private MessageStarRepository messageStarRepository;
    @Mock private AnonymousService anonymousService;
    @Mock private MessageReadReceiptRepository readReceiptRepository;
    @Mock private UserPrivacyService userPrivacyService;

    @InjectMocks private MessageService messageService;

    private Message message;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(messageService, "readReceiptRepository", readReceiptRepository);
        ReflectionTestUtils.setField(messageService, "userPrivacyService", userPrivacyService);
        ChatRoom room = new ChatRoom();
        room.setId(5L);
        message = new Message();
        message.setId(42L);
        message.setChatRoom(room);
        message.setSender(user(1L));
    }

    @Test
    void readingOneMessageWithReceiptsOffOnlyClearsOwnUnread() {
        when(messageRepository.findById(42L)).thenReturn(Optional.of(message));
        when(chatRoomRepository.isMember(5L, 2L)).thenReturn(true);
        when(userPrivacyService.readReceiptsDisabled(2L)).thenReturn(true);

        messageService.markMessageAsRead(42L, 2L);

        verify(readReceiptRepository, never()).save(any(MessageReadReceipt.class));
        verify(messageRepository, never()).markAsRead(anyLong(), anyLong());
        verify(chatRoomRepository).markMessageReadForMember(5L, 2L, 42L);
    }

    @Test
    void readingOneMessageWithReceiptsOnRecordsReceipt() {
        when(messageRepository.findById(42L)).thenReturn(Optional.of(message));
        when(chatRoomRepository.isMember(5L, 2L)).thenReturn(true);
        when(readReceiptRepository.findByMessageIdAndUserId(42L, 2L)).thenReturn(Optional.empty());
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L)));

        messageService.markMessageAsRead(42L, 2L);

        verify(readReceiptRepository).save(any(MessageReadReceipt.class));
        verify(messageRepository).markAsRead(42L, 2L);
    }

    @Test
    void readAllWithReceiptsOffDoesNotFlipSendersMessagesToRead() {
        when(chatRoomRepository.isMember(5L, 2L)).thenReturn(true);
        when(userPrivacyService.readReceiptsDisabled(2L)).thenReturn(true);

        messageService.markAllMessagesAsRead(5L, 2L);

        verify(messageRepository, never()).markAllAsReadInChatRoom(anyLong(), anyLong());
        verify(chatRoomRepository).markRoomReadForMember(eq(5L), eq(2L), any());
    }

    @Test
    void readByListHidesReadersWhoTurnedReceiptsOff() {
        when(messageRepository.findById(42L)).thenReturn(Optional.of(message));
        when(chatRoomRepository.isMember(5L, 1L)).thenReturn(true);
        when(readReceiptRepository.findByMessageIdOrderByReadAtAsc(42L))
                .thenReturn(List.of(receipt(2L), receipt(3L)));
        when(userPrivacyService.usersWithReadReceiptsDisabled(List.of(2L, 3L))).thenReturn(Set.of(3L));

        List<ReadReceiptDto> readers = messageService.getReadReceipts(42L, 1L);

        assertEquals(1, readers.size());
        assertEquals(2L, readers.get(0).getUserId());
    }

    @Test
    void requesterWithReceiptsOffSeesNoReaders() {
        when(messageRepository.findById(42L)).thenReturn(Optional.of(message));
        when(chatRoomRepository.isMember(5L, 1L)).thenReturn(true);
        when(userPrivacyService.readReceiptsDisabled(1L)).thenReturn(true);

        assertTrue(messageService.getReadReceipts(42L, 1L).isEmpty());
        verify(readReceiptRepository, never()).findByMessageIdOrderByReadAtAsc(anyLong());
    }

    private MessageReadReceipt receipt(Long userId) {
        MessageReadReceipt receipt = new MessageReadReceipt();
        receipt.setMessage(message);
        receipt.setUser(user(userId));
        return receipt;
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        return user;
    }
}
