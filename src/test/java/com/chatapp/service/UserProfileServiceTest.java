package com.chatapp.service;

import com.chatapp.dto.UserProfileUpdateRequest;
import com.chatapp.entity.User;
import com.chatapp.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserProfileService")
class UserProfileServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private FileStorageService fileStorageService;

    @InjectMocks private UserProfileService service;

    private User alice;

    @BeforeEach
    void setUp() {
        alice = new User();
        alice.setId(1L);
        alice.setUsername("alice");
        alice.setDisplayName("Alice");
        alice.setEmail("alice@test.com");
    }

    private UserProfileUpdateRequest req() {
        return new UserProfileUpdateRequest();
    }

    @Test
    @DisplayName("updateProfile trims and sets display name")
    void update_display_name() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserProfileUpdateRequest r = req();
        r.setDisplayName("  Alice Liddell  ");
        service.updateProfile(1L, r);
        assertEquals("Alice Liddell", alice.getDisplayName());
    }

    @Test
    @DisplayName("updateProfile ignores blank display name")
    void update_blank_display_name_ignored() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        UserProfileUpdateRequest r = req();
        r.setDisplayName("   ");
        service.updateProfile(1L, r);
        assertEquals("Alice", alice.getDisplayName()); // unchanged
    }

    @Test
    @DisplayName("updateProfile rejects email conflict with another user")
    void update_email_conflict() {
        User other = new User();
        other.setId(2L);
        other.setEmail("taken@test.com");
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(userRepository.findByEmail("taken@test.com")).thenReturn(Optional.of(other));

        UserProfileUpdateRequest r = req();
        r.setEmail("taken@test.com");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.updateProfile(1L, r));
        assertTrue(ex.getMessage().contains("邮箱"));
    }

    @Test
    @DisplayName("updateProfile allows setting own email again")
    void update_own_email_ok() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        UserProfileUpdateRequest r = req();
        r.setEmail("alice@test.com");
        service.updateProfile(1L, r);
        assertEquals("alice@test.com", alice.getEmail());
    }

    @Test
    @DisplayName("updateProfile normalizes new email to lowercase")
    void update_email_lowercased() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(userRepository.findByEmail("alice2@test.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        UserProfileUpdateRequest r = req();
        r.setEmail("Alice2@Test.com");
        service.updateProfile(1L, r);
        assertEquals("alice2@test.com", alice.getEmail());
    }

    @Test
    @DisplayName("updateProfile sets phone to null when blank")
    void update_phone_blank_null() {
        alice.setPhone("13800138000");
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        UserProfileUpdateRequest r = req();
        r.setPhone("");
        service.updateProfile(1L, r);
        assertNull(alice.getPhone());
    }

    @Test
    @DisplayName("updateProfile rejects invalid online status")
    void update_bad_status() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        UserProfileUpdateRequest r = req();
        r.setOnlineStatus("UNKNOWN");
        assertThrows(RuntimeException.class, () -> service.updateProfile(1L, r));
    }

    @Test
    @DisplayName("updateAvatar deletes old and uploads new")
    void update_avatar() throws IOException {
        alice.setAvatarUrl("/old.png");
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(fileStorageService.uploadAvatar(any())).thenReturn("/new.png");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile mf = new MockMultipartFile("a", "a.png", "image/png", new byte[]{1, 2, 3});
        service.updateAvatar(1L, mf);
        verify(fileStorageService).deleteFile("/old.png");
        verify(fileStorageService).uploadAvatar(mf);
        assertEquals("/new.png", alice.getAvatarUrl());
    }

    @Test
    @DisplayName("deleteAvatar clears url and deletes file")
    void delete_avatar() {
        alice.setAvatarUrl("/old.png");
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        service.deleteAvatar(1L);
        verify(fileStorageService).deleteFile("/old.png");
        assertNull(alice.getAvatarUrl());
    }

    @Test
    @DisplayName("updateOnlineStatus=OFFLINE also stamps lastSeen")
    void update_status_offline_stamps() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        User result = service.updateOnlineStatus(1L, User.OnlineStatus.OFFLINE);
        assertEquals(User.OnlineStatus.OFFLINE, result.getOnlineStatus());
        assertNotNull(result.getLastSeen());
    }

    @Test
    @DisplayName("searchUsers returns empty list for blank keyword")
    void search_blank() {
        assertTrue(service.searchUsers("   ", 10).isEmpty());
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("searchUsers delegates to repo with trimmed keyword")
    void search_delegate() {
        Page<User> page = new PageImpl<>(List.of(alice));
        when(userRepository.searchUsers(eq("bob"), any())).thenReturn(page);
        List<User> result = service.searchUsers("  bob ", 5);
        assertEquals(1, result.size());
    }
}
