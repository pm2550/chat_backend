package com.chatapp.controller;

import com.chatapp.dto.UserDto;
import com.chatapp.entity.User;
import com.chatapp.service.TokenBlacklistService;
import com.chatapp.service.UserPresenceService;
import com.chatapp.service.UserService;
import com.chatapp.util.JwtUtils;
import com.chatapp.websocket.RawWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private UserService userService;

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    @Mock
    private UserPresenceService userPresenceService;

    @Mock
    private RawWebSocketHandler rawWebSocketHandler;

    @InjectMocks
    private AuthController authController;

    private UserDto testUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(authController).build();

        testUser = new UserDto();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setEmail("test@example.com");
        testUser.setDisplayName("Test User");
        testUser.setOnlineStatus(User.OnlineStatus.ONLINE);
    }

    @Test
    void testLogin_Success() throws Exception {
        UserDto.LoginRequest loginRequest = new UserDto.LoginRequest("testuser", "password123");
        User authenticated = new User();
        authenticated.setId(1L);
        authenticated.setUsername("testuser");

        when(userService.authenticate(any(UserDto.LoginRequest.class))).thenReturn(authenticated);
        when(jwtUtils.generateAccessToken("testuser")).thenReturn("access-token-123");
        when(jwtUtils.generateRefreshToken("testuser")).thenReturn("refresh-token-456");
        when(userService.findByUsername("testuser")).thenReturn(testUser);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("access-token-123"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-token-456"))
                .andExpect(jsonPath("$.data.user.username").value("testuser"));

        verify(userService).authenticate(any(UserDto.LoginRequest.class));
        // 登录本身不让人"在线"：实时连接建立时才上线。
        verifyNoInteractions(rawWebSocketHandler);
        verify(userPresenceService, never()).markConnected(anyLong());
    }

    @Test
    void loginKeepsTheStatusTheUserChoseInsteadOfResettingToOnline() throws Exception {
        UserDto.LoginRequest loginRequest = new UserDto.LoginRequest("testuser", "password123");
        User authenticated = new User();
        authenticated.setId(1L);
        authenticated.setUsername("testuser");
        authenticated.setPresenceStatus(User.OnlineStatus.BUSY);

        when(userService.authenticate(any(UserDto.LoginRequest.class))).thenReturn(authenticated);
        when(jwtUtils.generateAccessToken("testuser")).thenReturn("access-token-123");
        when(jwtUtils.generateRefreshToken("testuser")).thenReturn("refresh-token-456");
        when(userService.findByUsername("testuser")).thenReturn(testUser);
        when(userPresenceService.chosenPresence(1L)).thenReturn(User.OnlineStatus.BUSY);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                // 返回给本人的是他选的状态，而不是库里"有没有连着"的状态。
                .andExpect(jsonPath("$.data.user.onlineStatus").value("BUSY"));

        verify(userPresenceService, never()).markConnected(anyLong());
    }

    @Test
    void refreshIssuesANewRefreshTokenSoTheSessionSlides() throws Exception {
        when(jwtUtils.validateJwtToken("old-refresh")).thenReturn(true);
        when(jwtUtils.getTokenId("old-refresh")).thenReturn("jti-1");
        when(tokenBlacklistService.isBlacklisted("jti-1")).thenReturn(false);
        when(jwtUtils.getTokenType("old-refresh")).thenReturn("refresh");
        when(jwtUtils.getUserNameFromJwtToken("old-refresh")).thenReturn("testuser");
        when(jwtUtils.generateAccessToken("testuser")).thenReturn("new-access");
        when(jwtUtils.generateRefreshToken("testuser")).thenReturn("new-refresh");
        when(userService.findByUsername("testuser")).thenReturn(testUser);

        mockMvc.perform(post("/api/auth/refresh")
                        .header("Authorization", "Bearer old-refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("new-access"))
                .andExpect(jsonPath("$.data.refreshToken").value("new-refresh"));

        // 旧 refresh token 不能被拉黑：客户端并发续期时后到的请求还要用它。
        verify(tokenBlacklistService, never()).blacklistToken(anyString(), anyLong());
    }

    @Test
    void refreshRejectsAnExpiredRefreshToken() throws Exception {
        when(jwtUtils.validateJwtToken("expired-refresh")).thenReturn(false);

        mockMvc.perform(post("/api/auth/refresh")
                        .header("Authorization", "Bearer expired-refresh"))
                .andExpect(status().isBadRequest());

        verify(jwtUtils, never()).generateRefreshToken(anyString());
    }

    @Test
    void testLogin_InvalidCredentials() throws Exception {
        UserDto.LoginRequest loginRequest = new UserDto.LoginRequest("testuser", "wrongpassword");

        when(userService.authenticate(any(UserDto.LoginRequest.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void testRegister_Success() throws Exception {
        UserDto.RegisterRequest registerRequest = new UserDto.RegisterRequest(
                "newuser", "password123", "new@example.com", "1234567890", "New User");

        when(userService.registerUser(any(UserDto.RegisterRequest.class))).thenReturn(testUser);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value("testuser"));
    }

    @Test
    void testRegister_DuplicateUsername() throws Exception {
        UserDto.RegisterRequest registerRequest = new UserDto.RegisterRequest(
                "existinguser", "password123", "dup@example.com", null, null);

        when(userService.registerUser(any(UserDto.RegisterRequest.class)))
                .thenThrow(new RuntimeException("用户名已存在"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void testLogout_Success() throws Exception {
        String token = "Bearer valid-jwt-token";

        when(jwtUtils.getTokenId("valid-jwt-token")).thenReturn("token-id-1");
        when(jwtUtils.getRemainingExpirationMs("valid-jwt-token")).thenReturn(3600000L);
        when(jwtUtils.getUserNameFromJwtToken("valid-jwt-token")).thenReturn("testuser");
        when(userService.findByUsername("testuser")).thenReturn(testUser);

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(tokenBlacklistService).blacklistToken("token-id-1", 3600000L);
        // 按连接表同步：别的设备还连着就仍在线，否则离线。
        verify(rawWebSocketHandler).syncPresence(1L);
    }
}
