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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
    "spring.main.allow-circular-references=true",
    "spring.main.allow-bean-definition-overriding=true",
    "server.servlet.context-path="
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class ChatRoomIntegrationTest {

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

    // ---- Helper methods ----

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

    private Object[] createUserAndLogin(String userPrefix) throws Exception {
        String username = userPrefix + "_" + uniqueSuffix;
        String email = username + "@test.com";
        String password = "password123";

        registerUser(username, email, password);
        String token = loginAndGetToken(username, password);
        Long userId = getUserIdFromToken(token);
        return new Object[]{token, userId};
    }

    private Long createGroupChat(String token, String name, String description, List<Long> memberIds) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("name", name);
        request.put("description", description);
        request.put("memberIds", memberIds);

        MvcResult result = mockMvc.perform(post("/api/v1/chat-rooms/group")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatRoom").exists())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
        Map<String, Object> chatRoom = (Map<String, Object>) responseMap.get("chatRoom");
        return ((Number) chatRoom.get("id")).longValue();
    }

    // ---- Tests ----

    @Test
    @DisplayName("Create group chat room and get room details")
    void testCreateGroupChatAndGetDetails() throws Exception {
        Object[] user = createUserAndLogin("groupcreator");
        String token = (String) user[0];

        String roomName = "Test Group " + uniqueSuffix;
        Long roomId = createGroupChat(token, roomName, "A test group chat", List.of());

        mockMvc.perform(get("/api/v1/chat-rooms/" + roomId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatRoom.name").value(roomName))
                .andExpect(jsonPath("$.chatRoom.description").value("A test group chat"))
                .andExpect(jsonPath("$.chatRoom.roomType").value("GROUP"));
    }

    @Test
    @DisplayName("Get user chat rooms list")
    void testGetUserChatRooms() throws Exception {
        Object[] user = createUserAndLogin("roomlister");
        String token = (String) user[0];

        createGroupChat(token, "Room A " + uniqueSuffix, "Room A desc", List.of());
        createGroupChat(token, "Room B " + uniqueSuffix, "Room B desc", List.of());

        mockMvc.perform(get("/api/v1/chat-rooms")
                .header("Authorization", "Bearer " + token)
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatRooms").isArray())
                .andExpect(jsonPath("$.chatRooms.length()").value(greaterThanOrEqualTo(2)));
    }

    @Test
    @DisplayName("Search public chat rooms")
    void testSearchPublicChatRooms() throws Exception {
        Object[] user = createUserAndLogin("searcher");
        String token = (String) user[0];

        String uniqueName = "UniqueSearchable_" + uniqueSuffix;
        createGroupChat(token, uniqueName, "Searchable room", List.of());

        mockMvc.perform(get("/api/v1/chat-rooms/search")
                .header("Authorization", "Bearer " + token)
                .param("keyword", uniqueName)
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatRooms").isArray())
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("Create group with members, join and leave chat room")
    void testJoinAndLeaveChatRoom() throws Exception {
        Object[] user1 = createUserAndLogin("owner");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("joiner");
        String token2 = (String) user2[0];

        Long roomId = createGroupChat(token1, "Join Test Room " + uniqueSuffix, "Join test", List.of());

        mockMvc.perform(post("/api/v1/chat-rooms/" + roomId + "/join")
                .header("Authorization", "Bearer " + token2))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/chat-rooms/" + roomId + "/members")
                .header("Authorization", "Bearer " + token2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));

        mockMvc.perform(post("/api/v1/chat-rooms/" + roomId + "/leave")
                .header("Authorization", "Bearer " + token2))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/chat-rooms/" + roomId + "/members")
                .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    @DisplayName("Create private chat between two users")
    void testCreatePrivateChat() throws Exception {
        Object[] user1 = createUserAndLogin("priv1");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("priv2");
        Long userId2 = (Long) user2[1];

        mockMvc.perform(post("/api/v1/chat-rooms/private/" + userId2)
                .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatRoom.roomType").value("PRIVATE"));
    }

    @Test
    @DisplayName("Kick member from chat room (admin operation)")
    void testKickMember() throws Exception {
        Object[] admin = createUserAndLogin("admin");
        String adminToken = (String) admin[0];

        Object[] member = createUserAndLogin("member");
        Long memberId = (Long) member[1];

        Long roomId = createGroupChat(adminToken, "Kick Test " + uniqueSuffix, "Kick test", List.of(memberId));

        mockMvc.perform(get("/api/v1/chat-rooms/" + roomId + "/members")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));

        mockMvc.perform(post("/api/v1/chat-rooms/" + roomId + "/members/" + memberId + "/kick")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/chat-rooms/" + roomId + "/members")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    @DisplayName("Toggle admin status for a member")
    void testToggleAdmin() throws Exception {
        Object[] admin = createUserAndLogin("togadmin");
        String adminToken = (String) admin[0];

        Object[] member = createUserAndLogin("togmember");
        Long memberId = (Long) member[1];

        Long roomId = createGroupChat(adminToken, "Toggle Admin Test " + uniqueSuffix, "Toggle admin", List.of(memberId));

        mockMvc.perform(post("/api/v1/chat-rooms/" + roomId + "/members/" + memberId + "/toggle-admin")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Non-admin cannot kick members")
    void testNonAdminCannotKick() throws Exception {
        Object[] admin = createUserAndLogin("realadmin");
        String adminToken = (String) admin[0];

        Object[] member1 = createUserAndLogin("normalmem1");
        String member1Token = (String) member1[0];
        Long member1Id = (Long) member1[1];

        Object[] member2 = createUserAndLogin("normalmem2");
        Long member2Id = (Long) member2[1];

        Long roomId = createGroupChat(adminToken, "NonAdmin Kick Test " + uniqueSuffix, "Test", List.of(member1Id, member2Id));

        mockMvc.perform(post("/api/v1/chat-rooms/" + roomId + "/members/" + member2Id + "/kick")
                .header("Authorization", "Bearer " + member1Token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Update chat room information")
    void testUpdateChatRoom() throws Exception {
        Object[] user = createUserAndLogin("updater");
        String token = (String) user[0];

        Long roomId = createGroupChat(token, "Original Name " + uniqueSuffix, "Original desc", List.of());

        Map<String, Object> updateRequest = new HashMap<>();
        updateRequest.put("name", "Updated Name " + uniqueSuffix);
        updateRequest.put("description", "Updated description");

        mockMvc.perform(put("/api/v1/chat-rooms/" + roomId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatRoom.name").value("Updated Name " + uniqueSuffix))
                .andExpect(jsonPath("$.chatRoom.description").value("Updated description"));
    }

    @Test
    @DisplayName("Delete chat room by creator")
    void testDeleteChatRoom() throws Exception {
        Object[] user = createUserAndLogin("deleter");
        String token = (String) user[0];

        Long roomId = createGroupChat(token, "Delete Me " + uniqueSuffix, "To be deleted", List.of());

        mockMvc.perform(delete("/api/v1/chat-rooms/" + roomId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/chat-rooms/" + roomId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Get chat room members")
    void testGetChatRoomMembers() throws Exception {
        Object[] admin = createUserAndLogin("memadmin");
        String adminToken = (String) admin[0];

        Object[] member = createUserAndLogin("memmember");
        Long memberId = (Long) member[1];

        Long roomId = createGroupChat(adminToken, "Members Test " + uniqueSuffix, "Test", List.of(memberId));

        mockMvc.perform(get("/api/v1/chat-rooms/" + roomId + "/members")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members").isArray())
                .andExpect(jsonPath("$.count").value(2));
    }
}
