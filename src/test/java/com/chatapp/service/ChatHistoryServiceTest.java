package com.chatapp.service;

import com.chatapp.entity.ChatHistory;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatHistoryRepository;
import com.chatapp.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatHistoryService")
class ChatHistoryServiceTest {

    @Mock private ChatHistoryRepository chatHistoryRepository;
    @Mock private UserRepository userRepository;
    @Mock private FileStorageService fileStorageService;

    @InjectMocks private ChatHistoryService service;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        alice = new User();
        alice.setId(1L);
        alice.setDisplayName("Alice");
        alice.setAvatarUrl("/a.png");
        bob = new User();
        bob.setId(2L);
        bob.setDisplayName("Bob");
    }

    @Test
    @DisplayName("sendPrivateMessage persists history with both sides and cached sender fields")
    void send_private() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(userRepository.findById(2L)).thenReturn(Optional.of(bob));
        when(chatHistoryRepository.save(any(ChatHistory.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatHistory saved = service.sendPrivateMessage(1L, 2L, "hi bob");

        ArgumentCaptor<ChatHistory> captor = ArgumentCaptor.forClass(ChatHistory.class);
        verify(chatHistoryRepository).save(captor.capture());
        ChatHistory persisted = captor.getValue();
        assertEquals(1L, persisted.getSenderId());
        assertEquals(2L, persisted.getReceiverId());
        assertEquals("hi bob", persisted.getContent());
        assertEquals("Alice", persisted.getSenderName());
        assertEquals("/a.png", persisted.getSenderAvatar());
        assertEquals(ChatHistory.MessageType.TEXT, persisted.getMessageType());
    }

    @Test
    @DisplayName("sendPrivateMessage fails when sender missing")
    void send_private_no_sender() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> service.sendPrivateMessage(99L, 2L, "hi"));
    }

    @Test
    @DisplayName("sendGroupMessage persists with chatRoomId")
    void send_group() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(chatHistoryRepository.save(any(ChatHistory.class))).thenAnswer(inv -> inv.getArgument(0));

        service.sendGroupMessage(1L, 42L, "yo team");
        ArgumentCaptor<ChatHistory> captor = ArgumentCaptor.forClass(ChatHistory.class);
        verify(chatHistoryRepository).save(captor.capture());
        assertEquals(42L, captor.getValue().getChatRoomId());
        assertEquals("yo team", captor.getValue().getContent());
    }

    @Test
    @DisplayName("recallMessage within 2-minute window marks recalled and sets recall content")
    void recall_within_window() {
        ChatHistory existing = new ChatHistory();
        existing.setId(10L);
        existing.setSenderId(1L);
        existing.setSentAt(LocalDateTime.now().minusSeconds(30));
        existing.setContent("oops");
        when(chatHistoryRepository.findByIdAndIsDeletedFalse(10L)).thenReturn(Optional.of(existing));
        when(chatHistoryRepository.save(any(ChatHistory.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatHistory result = service.recallMessage(10L, 1L);
        assertTrue(result.getIsRecalled());
        assertEquals("[消息已撤回]", result.getContent());
        assertNotNull(result.getRecalledAt());
    }

    @Test
    @DisplayName("recallMessage refuses after the 2-minute grace period")
    void recall_expired() {
        ChatHistory existing = new ChatHistory();
        existing.setId(10L);
        existing.setSenderId(1L);
        existing.setSentAt(LocalDateTime.now().minusMinutes(5));
        existing.setContent("oops");
        when(chatHistoryRepository.findByIdAndIsDeletedFalse(10L)).thenReturn(Optional.of(existing));
        assertThrows(RuntimeException.class, () -> service.recallMessage(10L, 1L));
    }

    @Test
    @DisplayName("recallMessage refuses non-sender")
    void recall_non_sender() {
        ChatHistory existing = new ChatHistory();
        existing.setId(10L);
        existing.setSenderId(1L);
        existing.setSentAt(LocalDateTime.now().minusSeconds(30));
        when(chatHistoryRepository.findByIdAndIsDeletedFalse(10L)).thenReturn(Optional.of(existing));
        assertThrows(RuntimeException.class, () -> service.recallMessage(10L, 999L));
    }

    @Test
    @DisplayName("deleteMessage soft-deletes and cleans up file attachment")
    void delete_with_file() {
        ChatHistory existing = new ChatHistory();
        existing.setId(11L);
        existing.setSenderId(1L);
        existing.setFileUrl("/uploads/a.pdf");
        when(chatHistoryRepository.findByIdAndIsDeletedFalse(11L)).thenReturn(Optional.of(existing));
        when(chatHistoryRepository.save(any(ChatHistory.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deleteMessage(11L, 1L);
        assertTrue(existing.getIsDeleted());
        assertNotNull(existing.getDeletedAt());
        verify(fileStorageService).deleteFile("/uploads/a.pdf");
    }

    @Test
    @DisplayName("deleteMessage refuses non-sender")
    void delete_non_sender() {
        ChatHistory existing = new ChatHistory();
        existing.setId(11L);
        existing.setSenderId(1L);
        when(chatHistoryRepository.findByIdAndIsDeletedFalse(11L)).thenReturn(Optional.of(existing));
        assertThrows(RuntimeException.class, () -> service.deleteMessage(11L, 999L));
    }

    @Test
    @DisplayName("replyToPrivateMessage requires the referenced message to exist")
    void reply_private_requires_target() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(chatHistoryRepository.findByIdAndIsDeletedFalse(7L)).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class,
                () -> service.replyToPrivateMessage(1L, 2L, "hi", 7L));
    }

    @Test
    @DisplayName("cleanupOldMessages delegates with cutoff derived from daysToKeep")
    void cleanup_delegates() {
        when(chatHistoryRepository.markMessagesAsDeletedBefore(any(LocalDateTime.class))).thenReturn(5);
        int n = service.cleanupOldMessages(30);
        assertEquals(5, n);
        verify(chatHistoryRepository).markMessagesAsDeletedBefore(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("countPrivateMessages delegates to repo")
    void count_private() {
        when(chatHistoryRepository.countPrivateMessages(1L, 2L)).thenReturn(42L);
        assertEquals(42L, service.countPrivateMessages(1L, 2L));
    }

    @Test
    @DisplayName("countGroupMessages delegates to repo")
    void count_group() {
        when(chatHistoryRepository.countGroupMessages(10L)).thenReturn(17L);
        assertEquals(17L, service.countGroupMessages(10L));
    }
}
