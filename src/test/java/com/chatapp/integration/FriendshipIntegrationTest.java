package com.chatapp.integration;

import com.chatapp.service.CloudStorageService;
import com.chatapp.service.LLMService;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.SelfDestructService;
import com.chatapp.service.TokenBlacklistService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
public class FriendshipIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    @DisplayName("Friend request can be sent, listed, accepted and used to start private chat")
    void friendRequestAcceptListAndPrivateChat() throws Exception {
        Object[] alice = createUserAndLogin("friendalice");
        String aliceToken = (String) alice[0];
        Long aliceId = (Long) alice[1];

        Object[] bob = createUserAndLogin("friendbob");
        String bobToken = (String) bob[0];
        Long bobId = (Long) bob[1];
        String bobUsername = (String) bob[2];

        mockMvc.perform(post("/api/v1/friends/request/" + bobId)
                .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendship.status").value("PENDING"))
                .andExpect(jsonPath("$.friendship.user.id").value(aliceId.intValue()))
                .andExpect(jsonPath("$.friendship.friend.id").value(bobId.intValue()))
                .andExpect(jsonPath("$.friendship.user.password").doesNotExist())
                .andExpect(jsonPath("$.friendship.user.roles").doesNotExist());

        mockMvc.perform(get("/api/v1/friends/requests/received")
                .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests[0].status").value("PENDING"))
                .andExpect(jsonPath("$.requests[0].user.id").value(aliceId.intValue()))
                .andExpect(jsonPath("$.requests[0].friend.id").value(bobId.intValue()))
                .andExpect(jsonPath("$.requests[0].user.password").doesNotExist());

        mockMvc.perform(get("/api/v1/friends/requests/sent")
                .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests[0].friend.id").value(bobId.intValue()));

        mockMvc.perform(post("/api/v1/friends/accept/" + aliceId)
                .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendship.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.friendship.acceptedAt").exists());

        mockMvc.perform(get("/api/v1/friends")
                .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friends[*].id", hasItem(bobId.intValue())))
                .andExpect(jsonPath("$.friends[0].password").doesNotExist())
                .andExpect(jsonPath("$.friends[0].roles").doesNotExist());

        mockMvc.perform(get("/api/v1/friends/search")
                .header("Authorization", "Bearer " + aliceToken)
                .param("keyword", bobUsername))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friends[*].id", hasItem(bobId.intValue())));

        mockMvc.perform(post("/api/v1/chat-rooms/private/" + bobId)
                .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatRoom.id").isNumber())
                .andExpect(jsonPath("$.chatRoom.roomType").value("PRIVATE"));
    }

    @Test
    @DisplayName("Friend request can be declined")
    void declineFriendRequest() throws Exception {
        Object[] alice = createUserAndLogin("declinealice");
        String aliceToken = (String) alice[0];
        Long aliceId = (Long) alice[1];

        Object[] bob = createUserAndLogin("declinebob");
        String bobToken = (String) bob[0];
        Long bobId = (Long) bob[1];

        mockMvc.perform(post("/api/v1/friends/request/" + bobId)
                .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/friends/decline/" + aliceId)
                .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/friends/requests/received")
                .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @DisplayName("Accepting a new reverse request ignores stale declined history")
    void acceptReverseRequestAfterStaleDeclinedHistory() throws Exception {
        Object[] alice = createUserAndLogin("stalealice");
        String aliceToken = (String) alice[0];
        Long aliceId = (Long) alice[1];

        Object[] bob = createUserAndLogin("stalebob");
        String bobToken = (String) bob[0];
        Long bobId = (Long) bob[1];

        mockMvc.perform(post("/api/v1/friends/request/" + bobId)
                .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendship.status").value("PENDING"));

        mockMvc.perform(post("/api/v1/friends/decline/" + aliceId)
                .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/friends/request/" + aliceId)
                .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendship.status").value("PENDING"))
                .andExpect(jsonPath("$.friendship.user.id").value(bobId.intValue()))
                .andExpect(jsonPath("$.friendship.friend.id").value(aliceId.intValue()));

        mockMvc.perform(get("/api/v1/friends/requests/received")
                .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.requests[0].user.id").value(bobId.intValue()));

        mockMvc.perform(post("/api/v1/friends/accept/" + bobId)
                .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendship.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.friendship.user.id").value(bobId.intValue()))
                .andExpect(jsonPath("$.friendship.friend.id").value(aliceId.intValue()));
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
