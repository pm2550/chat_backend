package com.chatapp.integration;

import com.chatapp.service.CloudStorageService;
import com.chatapp.service.LLMService;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.TokenBlacklistService;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "spring.main.allow-circular-references=true",
        "spring.main.allow-bean-definition-overriding=true",
        "server.servlet.context-path=",
        "file.storage.upload-dir=target/profile-test-uploads"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("User profile integration")
class UserProfileIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private LLMService llmService;
    @MockBean private CloudStorageService cloudStorageService;

    private String token;
    private String suffix;

    @BeforeEach
    void setUp() throws Exception {
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        suffix = UUID.randomUUID().toString().substring(0, 8);
        token = registerAndLogin("profile_" + suffix);
    }

    @Test
    @DisplayName("Profile can be read, updated, and status changed without leaking secrets")
    void profile_read_update_status() throws Exception {
        String profileJson = mockMvc.perform(get("/api/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.displayName").value("profile_" + suffix))
                .andReturn().getResponse().getContentAsString();
        assertNoSecretLeak(profileJson);

        String updateJson = mockMvc.perform(put("/api/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "displayName", "Updated " + suffix,
                                "email", "updated_" + suffix + "@test.com",
                                "phone", "555-" + suffix.substring(0, 4),
                                "bio", "Profile bio",
                                "onlineStatus", "BUSY"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Updated " + suffix))
                .andExpect(jsonPath("$.data.email").value("updated_" + suffix + "@test.com"))
                .andExpect(jsonPath("$.data.onlineStatus").value("BUSY"))
                .andReturn().getResponse().getContentAsString();
        assertNoSecretLeak(updateJson);

        mockMvc.perform(put("/api/profile/status?status=AWAY")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onlineStatus").value("AWAY"));
    }

    @Test
    @DisplayName("Avatar upload and delete use authenticated profile endpoint")
    void avatar_upload_delete() throws Exception {
        MockMultipartFile avatar = new MockMultipartFile(
                "avatar",
                "avatar.png",
                "image/png",
                new byte[]{1, 2, 3, 4});

        String uploadJson = mockMvc.perform(multipart("/api/profile/avatar")
                        .file(avatar)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarUrl", startsWith("/api/files/avatar/")))
                .andReturn().getResponse().getContentAsString();
        assertNoSecretLeak(uploadJson);

        mockMvc.perform(get("/api/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarUrl", startsWith("/api/files/avatar/")));

        mockMvc.perform(delete("/api/profile/avatar")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarUrl").doesNotExist());
    }

    @Test
    @DisplayName("Chat customization settings and background upload are persisted")
    void chat_customization_settings_and_background_upload() throws Exception {
        mockMvc.perform(get("/api/profile/settings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chatBackgroundPreset").value("cloud_gradient"))
                .andExpect(jsonPath("$.data.avatarFramePreset").value("none"))
                .andExpect(jsonPath("$.data.bubbleStylePreset").value("default_gradient"));

        mockMvc.perform(put("/api/profile/settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "chatBackgroundPreset", "aurora",
                                "avatarFramePreset", "cyber_glow",
                                "bubbleStylePreset", "retro_block"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chatBackgroundPreset").value("aurora"))
                .andExpect(jsonPath("$.data.avatarFramePreset").value("cyber_glow"))
                .andExpect(jsonPath("$.data.bubbleStylePreset").value("retro_block"));

        mockMvc.perform(put("/api/profile/settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "chatBackgroundPreset", "solid:#EAF4FF"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chatBackgroundPreset").value("solid:#EAF4FF"));

        mockMvc.perform(put("/api/profile/settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "chatBackgroundPreset", "solid:#XYZ123",
                                "bubbleStylePreset", "unknown_style"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        MockMultipartFile background = new MockMultipartFile(
                "background",
                "desk.png",
                "image/png",
                new byte[]{9, 8, 7, 6});

        String uploadJson = mockMvc.perform(multipart("/api/profile/chat-background")
                        .file(background)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chatBackgroundCustomUrl", startsWith("/api/files/background/")))
                .andReturn().getResponse().getContentAsString();
        JsonNode urlNode = objectMapper.readTree(uploadJson).path("data").path("chatBackgroundCustomUrl");
        String backgroundFileName = urlNode.asText().substring("/api/files/background/".length());

        mockMvc.perform(get("/api/files/background/" + backgroundFileName))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("2MB jpg chat background upload succeeds and persists URL")
    void chat_background_upload_accepts_two_mb_jpg() throws Exception {
        MockMultipartFile background = new MockMultipartFile(
                "background",
                "two-mb.jpg",
                "image/jpeg",
                new byte[2 * 1024 * 1024]);

        mockMvc.perform(multipart("/api/profile/chat-background")
                        .file(background)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.chatBackgroundCustomUrl", startsWith("/api/files/background/")));

        mockMvc.perform(get("/api/profile/settings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chatBackgroundCustomUrl", startsWith("/api/files/background/")));
    }

    @Test
    @DisplayName("3MB png chat background upload is rejected as too large")
    void chat_background_upload_rejects_three_mb_png() throws Exception {
        MockMultipartFile background = new MockMultipartFile(
                "background",
                "three-mb.png",
                "image/png",
                new byte[3 * 1024 * 1024]);

        mockMvc.perform(multipart("/api/profile/chat-background")
                        .file(background)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("Text files cannot be uploaded as chat backgrounds")
    void chat_background_upload_rejects_text_plain() throws Exception {
        MockMultipartFile background = new MockMultipartFile(
                "background",
                "notes.txt",
                "text/plain",
                "not an image".getBytes());

        mockMvc.perform(multipart("/api/profile/chat-background")
                        .file(background)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("Invalid online status is rejected")
    void invalid_status_rejected() throws Exception {
        mockMvc.perform(put("/api/profile/status?status=NOPE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "password123",
                                "email", username + "@test.com",
                                "displayName", username))))
                .andExpect(status().isOk());

        String loginJson = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "password123"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(loginJson).path("data");
        return data.path("accessToken").asText();
    }

    private static void assertNoSecretLeak(String json) {
        String lower = json.toLowerCase();
        boolean leaked = lower.contains("$2a$")
                || lower.contains("$2b$")
                || lower.contains("$2y$")
                || lower.contains("\"password\":\"")
                || lower.contains("\"roles\"");
        assertFalse(leaked, "secret fields leaked: " + json);
    }
}
