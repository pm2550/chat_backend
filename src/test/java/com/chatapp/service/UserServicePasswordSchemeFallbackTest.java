package com.chatapp.service;

import com.chatapp.config.RateLimitConfig;
import com.chatapp.dto.UserDto;
import com.chatapp.entity.User;
import com.chatapp.exception.PasswordUpgradeRequiredException;
import com.chatapp.repository.UserRepository;
import com.chatapp.repository.UserSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.modelmapper.ModelMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServicePasswordSchemeFallbackTest {

    private static final String USERNAME = "legacy_null_scheme";
    private static final String PLAINTEXT_PASSWORD = "test-plain-password";
    private static final String ENCODED_PASSWORD = "$2a$10$encoded";
    private static final String CLIENT_HASH =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSettingsRepository userSettingsRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ModelMapper modelMapper;

    @Mock
    private RateLimitConfig rateLimitConfig;

    @InjectMocks
    private UserService userService;

    @Test
    void nullPasswordSchemeFallsBackToLegacyAndAuthenticatesWithPlaintext() {
        User user = legacyUserWithNullScheme();
        UserDto.LoginRequest request = new UserDto.LoginRequest();
        request.setUsername(USERNAME);
        request.setPassword(PLAINTEXT_PASSWORD);

        when(rateLimitConfig.isLoginLocked(USERNAME)).thenReturn(false);
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PLAINTEXT_PASSWORD, ENCODED_PASSWORD))
                .thenReturn(true);

        User authenticated = userService.authenticate(request);

        assertSame(user, authenticated);
        verify(rateLimitConfig).resetLoginFailures(USERNAME);
        verify(rateLimitConfig, never()).recordLoginFailure(USERNAME);
    }

    @Test
    void nullPasswordSchemeRejectsClientHashCredential() {
        User user = legacyUserWithNullScheme();
        UserDto.LoginRequest request = new UserDto.LoginRequest();
        request.setUsername(USERNAME);
        request.setClientHash(CLIENT_HASH);

        when(rateLimitConfig.isLoginLocked(USERNAME)).thenReturn(false);
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));

        assertThrows(
                PasswordUpgradeRequiredException.class,
                () -> userService.authenticate(request));
        verify(passwordEncoder, never()).matches(CLIENT_HASH, ENCODED_PASSWORD);
        verify(rateLimitConfig, never()).resetLoginFailures(USERNAME);
    }

    @Test
    void loadUserByUsernameWorksWithNullPasswordScheme() {
        User user = legacyUserWithNullScheme();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PLAINTEXT_PASSWORD, ENCODED_PASSWORD))
                .thenReturn(true);

        UserDetails userDetails = userService.loadUserByUsername(USERNAME);

        assertEquals(USERNAME, userDetails.getUsername());
        assertEquals(ENCODED_PASSWORD, userDetails.getPassword());
        assertTrue(userDetails.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_USER")));
        assertTrue(userService.validatePassword(USERNAME, PLAINTEXT_PASSWORD));
    }

    private User legacyUserWithNullScheme() {
        User user = new User();
        user.setId(7001L);
        user.setUsername(USERNAME);
        user.setPassword(ENCODED_PASSWORD);
        user.setEmail(USERNAME + "@example.com");
        user.setDisplayName("Legacy Null Scheme");
        user.setPasswordScheme(null);
        user.setIsActive(true);
        user.getRoles().add(User.Role.USER);
        return user;
    }
}
