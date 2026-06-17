package com.chatapp.service;

import com.chatapp.dto.UserDto;
import com.chatapp.entity.User;
import com.chatapp.repository.UserRepository;
import com.chatapp.repository.UserSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.modelmapper.ModelMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSettingsRepository userSettingsRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ModelMapper modelMapper;

    @InjectMocks
    private UserService userService;

    private User createTestUser() {
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setPassword("encodedPassword");
        user.setEmail("test@example.com");
        user.setDisplayName("Test User");
        user.setIsActive(true);
        user.setOnlineStatus(User.OnlineStatus.OFFLINE);
        user.getRoles().add(User.Role.USER);
        return user;
    }

    @Test
    void testRegisterUser_Success() {
        UserDto.RegisterRequest request = new UserDto.RegisterRequest();
        request.setUsername("newuser");
        request.setPassword("password123");
        request.setEmail("new@example.com");
        request.setDisplayName("New User");

        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("encodedPassword");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserDto expectedDto = new UserDto();
        expectedDto.setUsername("newuser");
        when(modelMapper.map(any(User.class), eq(UserDto.class))).thenReturn(expectedDto);

        UserDto result = userService.registerUser(request);

        assertNotNull(result);
        assertEquals("newuser", result.getUsername());
        verify(userRepository).save(any(User.class));
    }

    @Test
    void testRegisterUser_DuplicateUsername() {
        UserDto.RegisterRequest request = new UserDto.RegisterRequest();
        request.setUsername("existinguser");
        request.setPassword("password123");
        request.setEmail("new@example.com");

        when(userRepository.existsByUsername("existinguser")).thenReturn(true);

        assertThrows(RuntimeException.class, () -> userService.registerUser(request));
    }

    @Test
    void testRegisterUser_DuplicateEmail() {
        UserDto.RegisterRequest request = new UserDto.RegisterRequest();
        request.setUsername("newuser");
        request.setPassword("password123");
        request.setEmail("existing@example.com");

        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(userRepository.existsByEmail("existing@example.com")).thenReturn(true);

        assertThrows(RuntimeException.class, () -> userService.registerUser(request));
    }

    @Test
    void testFindByUsername_Found() {
        User user = createTestUser();
        user.getRoles().add(User.Role.ADMIN);
        UserDto expectedDto = new UserDto();
        expectedDto.setUsername("testuser");

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
        when(modelMapper.map(user, UserDto.class)).thenReturn(expectedDto);
        when(userSettingsRepository.findByUserId(1L)).thenReturn(Optional.empty());

        UserDto result = userService.findByUsername("testuser");

        assertNotNull(result);
        assertEquals("testuser", result.getUsername());
        assertTrue(result.getRoles().contains(User.Role.USER));
        assertTrue(result.getRoles().contains(User.Role.ADMIN));
    }

    @Test
    void testFindByUsername_NotFound() {
        when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> userService.findByUsername("nonexistent"));
    }

    @Test
    void testFindById_Found() {
        User user = createTestUser();
        UserDto expectedDto = new UserDto();
        expectedDto.setId(1L);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(modelMapper.map(user, UserDto.class)).thenReturn(expectedDto);

        UserDto result = userService.findById(1L);

        assertNotNull(result);
        assertEquals(1L, result.getId());
    }

    @Test
    void testFindById_NotFound() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> userService.findById(999L));
    }

    @Test
    void testChangePassword_Success() {
        User user = createTestUser();
        UserDto.ChangePasswordRequest request = new UserDto.ChangePasswordRequest();
        request.setOldPassword("oldPassword");
        request.setNewPassword("newPassword");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("oldPassword", "encodedPassword")).thenReturn(true);
        when(passwordEncoder.encode("newPassword")).thenReturn("newEncodedPassword");

        userService.changePassword(1L, request);

        verify(userRepository).save(user);
        verify(passwordEncoder).encode("newPassword");
    }

    @Test
    void testChangePassword_WrongOldPassword() {
        User user = createTestUser();
        UserDto.ChangePasswordRequest request = new UserDto.ChangePasswordRequest();
        request.setOldPassword("wrongPassword");
        request.setNewPassword("newPassword");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongPassword", "encodedPassword")).thenReturn(false);

        assertThrows(RuntimeException.class, () -> userService.changePassword(1L, request));
    }

    @Test
    void testValidatePassword_Valid() {
        User user = createTestUser();

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctPassword", "encodedPassword")).thenReturn(true);

        assertTrue(userService.validatePassword("testuser", "correctPassword"));
    }

    @Test
    void testValidatePassword_Invalid() {
        User user = createTestUser();

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongPassword", "encodedPassword")).thenReturn(false);

        assertFalse(userService.validatePassword("testuser", "wrongPassword"));
    }

    @Test
    void testLoadUserByUsername_Found() {
        User user = createTestUser();

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        UserDetails userDetails = userService.loadUserByUsername("testuser");

        assertNotNull(userDetails);
        assertEquals("testuser", userDetails.getUsername());
        assertEquals("encodedPassword", userDetails.getPassword());
        assertTrue(userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
    }

    @Test
    void testLoadUserByUsername_NotFound() {
        when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class,
                () -> userService.loadUserByUsername("nonexistent"));
    }

    @Test
    void updateTitle_normalizesAndPersistsTitleFields() {
        User user = createTestUser();
        UserDto.TitleRequest request = new UserDto.TitleRequest();
        request.setTitle(" 值班 ");
        request.setTitleColor("#18B98F");
        request.setTitleEffect("glow");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenAnswer(invocation -> invocation.getArgument(0));
        when(modelMapper.map(any(User.class), eq(UserDto.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            UserDto dto = new UserDto();
            dto.setId(saved.getId());
            dto.setTitle(saved.getTitle());
            dto.setTitleColor(saved.getTitleColor());
            dto.setTitleEffect(saved.getTitleEffect());
            return dto;
        });
        when(userSettingsRepository.findByUserId(1L)).thenReturn(Optional.empty());

        UserDto result = userService.updateTitle(1L, request);

        assertEquals("值班", user.getTitle());
        assertEquals("#18B98F", user.getTitleColor());
        assertEquals("glow", user.getTitleEffect());
        assertEquals("值班", result.getTitle());
        verify(userRepository).save(user);
    }

    @Test
    void updateTitle_rejectsInvalidColorAndEffect() {
        User user = createTestUser();
        UserDto.TitleRequest badColor = new UserDto.TitleRequest();
        badColor.setTitle("PM");
        badColor.setTitleColor("blue");
        badColor.setTitleEffect("none");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        assertThrows(IllegalArgumentException.class,
                () -> userService.updateTitle(1L, badColor));

        UserDto.TitleRequest badEffect = new UserDto.TitleRequest();
        badEffect.setTitle("PM");
        badEffect.setTitleColor("#2F6BFF");
        badEffect.setTitleEffect("sparkle");
        assertThrows(IllegalArgumentException.class,
                () -> userService.updateTitle(1L, badEffect));

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void updateUserTitleAsAdmin_requiresAdminRole() {
        User operator = createTestUser();
        UserDto.TitleRequest request = new UserDto.TitleRequest();
        request.setTitle("管理员");
        request.setTitleEffect("gradient");

        when(userRepository.findById(1L)).thenReturn(Optional.of(operator));

        assertThrows(AccessDeniedException.class,
                () -> userService.updateUserTitleAsAdmin(1L, 2L, request));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void ensureBuiltinAdminRole_addsAdminRoleToExistingAdminUser() {
        User admin = createTestUser();
        admin.setUsername("admin");
        assertFalse(admin.getRoles().contains(User.Role.ADMIN));

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        assertTrue(userService.ensureBuiltinAdminRole());

        assertTrue(admin.getRoles().contains(User.Role.ADMIN));
        verify(userRepository).save(admin);
    }

    @Test
    void ensureBuiltinAdminRole_isNoopWhenAdminMissing() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());

        assertFalse(userService.ensureBuiltinAdminRole());

        verify(userRepository, never()).save(any(User.class));
    }
}
