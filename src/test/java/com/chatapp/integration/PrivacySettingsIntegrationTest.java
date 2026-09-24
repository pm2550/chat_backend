package com.chatapp.integration;

import com.chatapp.service.CloudStorageService;
import com.chatapp.service.LLMService;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.SelfDestructService;
import com.chatapp.service.TokenBlacklistService;
import com.chatapp.repository.UserRepository;
import com.chatapp.websocket.RawWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.socket.CloseStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
    "spring.main.allow-circular-references=true",
    "spring.main.allow-bean-definition-overriding=true",
    "server.servlet.context-path=",
    "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
/** 隐私设置经过真实 HTTP 接口后的效果（好友请求 / 私聊 / 在线状态）。 */
public class PrivacySettingsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RawWebSocketHandler rawWebSocketHandler;

    @Autowired
    private UserRepository userRepository;

    private final List<RoomRealtimeSyncIntegrationTest.RecordingSession> openSessions = new ArrayList<>();

    @AfterEach
    void closeSessions() {
        openSessions.forEach(session -> rawWebSocketHandler.afterConnectionClosed(session, CloseStatus.NORMAL));
        openSessions.clear();
    }

    /** 在线 = 有前台实时连接（只登录不算）。 */
    private void connect(Object[] user) {
        var session = new RoomRealtimeSyncIntegrationTest.RecordingSession();
        session.getAttributes().put(RawWebSocketHandler.ATTR_USER,
                userRepository.findById((Long) user[1]).orElseThrow());
        rawWebSocketHandler.afterConnectionEstablished(session);
        openSessions.add(session);
    }

    @MockBean
    private TokenBlacklistService tokenBlacklistService;

    @MockBean
    private PushNotificationService pushNotificationService;

    @MockBean
    private LLMService llmService;

    @MockBean
    private SelfDestructService selfDestructService;

    @MockBean
    private CloudStorageService cloudStorageService;

    private String uniqueSuffix;

    @BeforeEach
    void setUp() {
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("allowFriendRequests=false rejects incoming friend requests with a readable error")
    void friendRequestsCanBeTurnedOff() throws Exception {
        Object[] alice = createUserAndLogin("pfalice");
        Object[] bob = createUserAndLogin("pfbob");
        updateSettings((String) bob[0], Map.of("allowFriendRequests", false));

        mockMvc.perform(post("/api/v1/friends/request/" + bob[1])
                .header("Authorization", "Bearer " + alice[0]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("对方已关闭好友请求，暂时无法添加"));
    }

    @Test
    @DisplayName("allowDirectMessages=false only lets friends open a new private chat")
    void directMessagesFromStrangersCanBeTurnedOff() throws Exception {
        Object[] alice = createUserAndLogin("pdalice");
        Object[] bob = createUserAndLogin("pdbob");
        updateSettings((String) bob[0], Map.of("allowDirectMessages", false));

        mockMvc.perform(post("/api/v1/chat-rooms/private/" + bob[1])
                .header("Authorization", "Bearer " + alice[0]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("对方只接受好友发起私聊，请先添加好友"));

        makeFriends(alice, bob);

        mockMvc.perform(post("/api/v1/chat-rooms/private/" + bob[1])
                .header("Authorization", "Bearer " + alice[0]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatRoom.roomType").value("PRIVATE"));
    }

    @Test
    @DisplayName("showOnlineStatus=false hides presence from others but not from the user")
    void onlineStatusCanBeHidden() throws Exception {
        Object[] alice = createUserAndLogin("psalice");
        Object[] bob = createUserAndLogin("psbob");
        makeFriends(alice, bob);
        connect(alice);
        connect(bob);
        updateSettings((String) bob[0], Map.of("showOnlineStatus", false));

        mockMvc.perform(get("/api/v1/friends")
                .header("Authorization", "Bearer " + alice[0]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friends[0].id").value(((Long) bob[1]).intValue()))
                .andExpect(jsonPath("$.friends[0].onlineStatus").value("OFFLINE"))
                .andExpect(jsonPath("$.friends[0].lastSeen").isEmpty());

        mockMvc.perform(get("/api/profile/search")
                .header("Authorization", "Bearer " + alice[0])
                .param("keyword", (String) bob[2]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].onlineStatus").value("OFFLINE"))
                .andExpect(jsonPath("$.data[0].clientSalt").doesNotExist());

        mockMvc.perform(get("/api/profile/search")
                .header("Authorization", "Bearer " + bob[0])
                .param("keyword", (String) bob[2]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].onlineStatus").value("ONLINE"));

        MvcResult room = mockMvc.perform(post("/api/v1/chat-rooms/private/" + bob[1])
                .header("Authorization", "Bearer " + alice[0]))
                .andExpect(status().isOk())
                .andReturn();
        Number roomId = com.jayway.jsonpath.JsonPath.read(room.getResponse().getContentAsString(), "$.chatRoom.id");

        mockMvc.perform(get("/api/v1/chat-rooms/" + roomId + "/members")
                .header("Authorization", "Bearer " + alice[0]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[?(@.userId == " + bob[1] + ")].user.onlineStatus")
                        .value(org.hamcrest.Matchers.contains("OFFLINE")))
                .andExpect(jsonPath("$.members[?(@.userId == " + alice[1] + ")].user.onlineStatus")
                        .value(org.hamcrest.Matchers.contains("ONLINE")));
    }

    private void updateSettings(String token, Map<String, Object> settings) throws Exception {
        mockMvc.perform(put("/api/profile/settings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(settings)))
                .andExpect(status().isOk());
    }

    private void makeFriends(Object[] requester, Object[] target) throws Exception {
        mockMvc.perform(post("/api/v1/friends/request/" + target[1])
                .header("Authorization", "Bearer " + requester[0]))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/friends/accept/" + requester[1])
                .header("Authorization", "Bearer " + target[0]))
                .andExpect(status().isOk());
    }

    private Object[] createUserAndLogin(String userPrefix) throws Exception {
        String username = userPrefix + "_" + uniqueSuffix;
        String email = username + "@test.com";
        String password = "password123";

        registerUser(username, email, password);
        String token = loginAndGetToken(username, password);
        Long userId = getUserIdFromToken(token);
        return new Object[]{token, userId, username};
    }

    private void registerUser(String username, String email, String password) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("username", username);
        request.put("email", email);
        request.put("password", password);
        request.put("displayName", username);

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        Map<String, Object> loginRequest = new HashMap<>();
        loginRequest.put("username", username);
        loginRequest.put("password", password);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
        Map<String, Object> data = (Map<String, Object>) responseMap.get("data");
        return (String) data.get("accessToken");
    }

    private Long getUserIdFromToken(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/validate")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
        Map<String, Object> data = (Map<String, Object>) responseMap.get("data");
        return ((Number) data.get("id")).longValue();
    }
}
