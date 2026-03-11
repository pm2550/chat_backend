package com.chatapp.service;

import com.chatapp.dto.AnonymousDto;
import com.chatapp.entity.AnonymousIdentity;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.User;
import com.chatapp.repository.AnonymousIdentityRepository;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnonymousServiceTest {

    @Mock
    private AnonymousIdentityRepository anonymousIdentityRepository;

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AnonymousService anonymousService;

    private User testUser;
    private ChatRoom testRoom;
    private AnonymousIdentity testIdentity;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("testuser");

        testRoom = new ChatRoom();
        testRoom.setId(10L);
        testRoom.setName("Test Room");
        testRoom.setAnonymousEnabled(true);

        testIdentity = new AnonymousIdentity();
        testIdentity.setId(100L);
        testIdentity.setUser(testUser);
        testIdentity.setChatRoom(testRoom);
        testIdentity.setAnonymousName("神秘海豚");
        testIdentity.setAnonymousAvatar("#FF6B6B");
        testIdentity.setAssignedDate(LocalDate.now());
        testIdentity.setCustomNameUsed(false);
    }

    @Test
    void testGetOrCreateIdentity_ExistingIdentity() {
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(testRoom));
        when(anonymousIdentityRepository.findByUserIdAndChatRoomIdAndAssignedDate(
                eq(1L), eq(10L), any(LocalDate.class)))
                .thenReturn(Optional.of(testIdentity));

        AnonymousDto result = anonymousService.getOrCreateIdentity(1L, 10L);

        assertNotNull(result);
        assertEquals(100L, result.getId());
        assertEquals("神秘海豚", result.getAnonymousName());
        assertEquals("#FF6B6B", result.getAnonymousAvatar());
        assertFalse(result.getCustomNameUsed());

        verify(anonymousIdentityRepository, never()).save(any());
    }

    @Test
    void testGetOrCreateIdentity_NewIdentity() {
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(testRoom));
        when(anonymousIdentityRepository.findByUserIdAndChatRoomIdAndAssignedDate(
                eq(1L), eq(10L), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(anonymousIdentityRepository.save(any(AnonymousIdentity.class)))
                .thenAnswer(invocation -> {
                    AnonymousIdentity saved = invocation.getArgument(0);
                    saved.setId(101L);
                    return saved;
                });

        AnonymousDto result = anonymousService.getOrCreateIdentity(1L, 10L);

        assertNotNull(result);
        assertEquals(101L, result.getId());
        assertNotNull(result.getAnonymousName());
        assertNotNull(result.getAnonymousAvatar());
        assertFalse(result.getCustomNameUsed());

        verify(anonymousIdentityRepository).save(any(AnonymousIdentity.class));
    }

    @Test
    void testGetOrCreateIdentity_AnonymousDisabled() {
        testRoom.setAnonymousEnabled(false);
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(testRoom));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> anonymousService.getOrCreateIdentity(1L, 10L));

        assertEquals("该聊天室未开启匿名功能", exception.getMessage());
        verifyNoInteractions(anonymousIdentityRepository);
    }

    @Test
    void testRenameAnonymousIdentity_Success() {
        when(anonymousIdentityRepository.findByUserIdAndChatRoomIdAndAssignedDate(
                eq(1L), eq(10L), any(LocalDate.class)))
                .thenReturn(Optional.of(testIdentity));
        when(anonymousIdentityRepository.save(any(AnonymousIdentity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AnonymousDto result = anonymousService.renameAnonymousIdentity(1L, 10L, "新名字");

        assertEquals("新名字", result.getAnonymousName());
        assertTrue(result.getCustomNameUsed());
        verify(anonymousIdentityRepository).save(any(AnonymousIdentity.class));
    }

    @Test
    void testRenameAnonymousIdentity_AlreadyRenamed() {
        testIdentity.setCustomNameUsed(true);
        when(anonymousIdentityRepository.findByUserIdAndChatRoomIdAndAssignedDate(
                eq(1L), eq(10L), any(LocalDate.class)))
                .thenReturn(Optional.of(testIdentity));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> anonymousService.renameAnonymousIdentity(1L, 10L, "另一个名字"));

        assertEquals("今日已使用过改名机会，明天再试", exception.getMessage());
        verify(anonymousIdentityRepository, never()).save(any());
    }

    @Test
    void testToggleAnonymous_AsAdmin() {
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(testRoom));
        when(chatRoomRepository.isAdmin(10L, 1L)).thenReturn(true);
        when(chatRoomRepository.save(any(ChatRoom.class))).thenReturn(testRoom);

        anonymousService.toggleAnonymous(10L, 1L, true);

        assertTrue(testRoom.getAnonymousEnabled());
        verify(chatRoomRepository).save(testRoom);
    }

    @Test
    void testToggleAnonymous_NotAdmin() {
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(testRoom));
        when(chatRoomRepository.isAdmin(10L, 2L)).thenReturn(false);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> anonymousService.toggleAnonymous(10L, 2L, true));

        assertEquals("只有管理员可以切换匿名功能", exception.getMessage());
        verify(chatRoomRepository, never()).save(any());
    }
}
