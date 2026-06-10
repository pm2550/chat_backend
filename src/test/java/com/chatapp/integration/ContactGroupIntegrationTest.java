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

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class ContactGroupIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private LLMService llmService;
    @MockBean private SelfDestructService selfDestructService;
    @MockBean private CloudStorageService cloudStorageService;

    private String uniqueSuffix;

    @BeforeEach
    void setUp() {
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("Contact groups can hold friends and rooms; deleting a group only ungroups items")
    void contactGroupsDoNotDeleteFriendshipOrRoom() throws Exception {
        Object[] alice = createUserAndLogin("cgalice");
        String aliceToken = (String) alice[0];
        Long aliceId = (Long) alice[1];
        Object[] bob = createUserAndLogin("cgbob");
        String bobToken = (String) bob[0];
        Long bobId = (Long) bob[1];

        makeFriends(aliceToken, bobToken, aliceId, bobId);
        Long roomId = createGroupChat(aliceToken, "Contact Group Room " + uniqueSuffix, List.of(bobId));

        Long coreGroupId = createContactGroup(aliceToken, "核心");
        Long laterGroupId = createContactGroup(aliceToken, "稍后");

        mockMvc.perform(post("/api/v1/contact-groups/reorder")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("groupIds", List.of(laterGroupId, coreGroupId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].id").value(laterGroupId.intValue()));

        assign(aliceToken, "FRIEND", bobId, coreGroupId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignment.targetType").value("FRIEND"))
                .andExpect(jsonPath("$.assignment.groupId").value(coreGroupId.intValue()));
        assign(aliceToken, "ROOM", roomId, coreGroupId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignment.targetType").value("ROOM"));

        mockMvc.perform(get("/api/v1/contact-groups")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[*].name", hasItem("核心")))
                .andExpect(jsonPath("$.assignments[*].targetType", hasItem("FRIEND")))
                .andExpect(jsonPath("$.assignments[*].targetId", hasItem(bobId.intValue())))
                .andExpect(jsonPath("$.assignments[*].targetId", hasItem(roomId.intValue())));

        mockMvc.perform(put("/api/v1/contact-groups/" + coreGroupId)
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "项目核心", "sortOrder", 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group.name").value("项目核心"));

        mockMvc.perform(delete("/api/v1/contact-groups/" + coreGroupId)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/contact-groups")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[*].id", hasItem(laterGroupId.intValue())))
                .andExpect(jsonPath("$.assignments").isEmpty());

        mockMvc.perform(get("/api/v1/friends")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friends[*].id", hasItem(bobId.intValue())));

        mockMvc.perform(get("/api/v1/chat-rooms")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("includeHidden", "true")
                        .param("includeBlocked", "true")
                        .param("type", "GROUP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatRooms[*].id", hasItem(roomId.intValue())));
    }

    @Test
    @DisplayName("Users cannot assign items into another user's contact group")
    void cannotAssignIntoAnotherUsersGroup() throws Exception {
        Object[] alice = createUserAndLogin("cgowneralice");
        String aliceToken = (String) alice[0];
        Long aliceId = (Long) alice[1];
        Object[] bob = createUserAndLogin("cgownerbob");
        String bobToken = (String) bob[0];
        Long bobId = (Long) bob[1];

        makeFriends(aliceToken, bobToken, aliceId, bobId);
        Long aliceGroupId = createContactGroup(aliceToken, "Alice Only");

        assign(bobToken, "FRIEND", aliceId, aliceGroupId)
                .andExpect(status().isForbidden());
    }

    private Object[] createUserAndLogin(String userPrefix) throws Exception {
        String username = userPrefix + "_" + uniqueSuffix;
        String email = username + "@test.com";
        String password = "password123";

        Map<String, Object> request = new HashMap<>();
        request.put("username", username);
        request.put("email", email);
        request.put("password", password);
        request.put("displayName", username);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(request)))
                .andExpect(status().isOk());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> loginMap = objectMapper.readValue(login.getResponse().getContentAsString(), Map.class);
        String token = (String) ((Map<String, Object>) loginMap.get("data")).get("accessToken");

        MvcResult validate = mockMvc.perform(get("/api/auth/validate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> validateMap =
                objectMapper.readValue(validate.getResponse().getContentAsString(), Map.class);
        Long userId = ((Number) ((Map<String, Object>) validateMap.get("data")).get("id")).longValue();
        return new Object[]{token, userId};
    }

    private void makeFriends(String aliceToken, String bobToken, Long aliceId, Long bobId) throws Exception {
        mockMvc.perform(post("/api/v1/friends/request/" + bobId)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/friends/accept/" + aliceId)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());
    }

    private Long createGroupChat(String token, String name, List<Long> memberIds) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/chat-rooms/group")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "name", name,
                                "description", "contact group test",
                                "memberIds", memberIds))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> response = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return ((Number) ((Map<String, Object>) response.get("chatRoom")).get("id")).longValue();
    }

    private Long createContactGroup(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/contact-groups")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", name))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> response = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return ((Number) ((Map<String, Object>) response.get("group")).get("id")).longValue();
    }

    private org.springframework.test.web.servlet.ResultActions assign(
            String token,
            String targetType,
            Long targetId,
            Long groupId) throws Exception {
        return mockMvc.perform(put("/api/v1/contact-groups/items")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of(
                        "targetType", targetType,
                        "targetId", targetId,
                        "groupId", groupId))));
    }

    private String body(Map<String, Object> map) throws Exception {
        return objectMapper.writeValueAsString(map);
    }
}
