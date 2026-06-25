package com.chatapp.integration;

import com.chatapp.entity.User;
import com.chatapp.repository.AppVersionRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.CloudStorageService;
import com.chatapp.service.LLMService;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.TokenBlacklistService;
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

import java.util.UUID;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
                "spring.main.allow-circular-references=true",
                "spring.main.allow-bean-definition-overriding=true",
                "server.servlet.context-path=",
                "app.version.publish-token=test-ci-token",
                "app.version.storage-path=./target/test-app-releases/"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("App Version Controller Integration Test")
class AppVersionControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AppVersionRepository versionRepository;

    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private LLMService llmService;
    @MockBean private CloudStorageService cloudStorageService;

    @BeforeEach
    void setUp() {
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        if (userRepository.findFirstByRolesContainingOrderByIdAsc(User.Role.ADMIN).isEmpty()) {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            User admin = new User();
            admin.setUsername("ci_admin_" + suffix);
            admin.setPassword("not-used");
            admin.setEmail("ci_admin_" + suffix + "@test.com");
            admin.setDisplayName("CI Admin");
            admin.getRoles().add(User.Role.ADMIN);
            admin.setIsActive(true);
            userRepository.save(admin);
        }
    }

    @Test
    @DisplayName("CI publish with valid token persists local artifact version")
    void publishFromCi_withValidToken_persistsVersion() throws Exception {
        int versionCode = 12000 + (int) (System.nanoTime() % 1000);
        String metadataJson = """
                {
                  "platform": "ANDROID",
                  "versionName": "1.1.0-ci",
                  "versionCode": %d,
                  "forceUpdate": false,
                  "releaseNotes": "CI publish test"
                }
                """.formatted(versionCode);
        MockMultipartFile metadata = new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                metadataJson.getBytes()
        );
        MockMultipartFile artifact = new MockMultipartFile(
                "artifact",
                "pm-chat-android-v1.1.0-ci.apk",
                "application/vnd.android.package-archive",
                "fake-apk".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/app/version/publish-from-ci")
                        .file(metadata)
                        .file(artifact)
                        .header("Authorization", "Bearer test-ci-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.versionName").value("1.1.0-ci"))
                .andExpect(jsonPath("$.version.versionCode").value(versionCode))
                .andExpect(jsonPath("$.version.downloadUrl",
                        startsWith("/api/v1/app/download/android/")))
                .andExpect(jsonPath("$.version.fileSize").value(8));

        var latest = versionRepository.findFirstByPlatformAndIsActiveTrueOrderByVersionCodeDesc(
                com.chatapp.entity.DeviceToken.Platform.ANDROID);
        org.junit.jupiter.api.Assertions.assertTrue(latest.isPresent());
        org.junit.jupiter.api.Assertions.assertEquals(versionCode, latest.get().getVersionCode());
    }


    @Test
    @DisplayName("CI publish succeeds even when no system or admin publisher exists")
    void publishFromCi_withoutSystemOrAdminPublisher_stillPersistsVersion() throws Exception {
        userRepository.findAll().forEach(user -> {
            user.getRoles().remove(User.Role.ADMIN);
            userRepository.save(user);
        });

        int versionCode = 13000 + (int) (System.nanoTime() % 1000);
        MockMultipartFile metadata = new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                ("{\"platform\":\"LINUX\",\"versionName\":\"1.1.0-ci-no-admin\",\"versionCode\":"
                        + versionCode + "}").getBytes()
        );
        MockMultipartFile artifact = new MockMultipartFile(
                "artifact",
                "pm-chat-linux-v1.1.0-ci.tar.gz",
                "application/gzip",
                "fake-linux".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/app/version/publish-from-ci")
                        .file(metadata)
                        .file(artifact)
                        .header("Authorization", "Bearer test-ci-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.platform").value("LINUX"))
                .andExpect(jsonPath("$.version.versionName").value("1.1.0-ci-no-admin"))
                .andExpect(jsonPath("$.version.versionCode").value(versionCode))
                .andExpect(jsonPath("$.version.downloadUrl",
                        startsWith("/api/v1/app/download/linux/")))
                .andExpect(jsonPath("$.version.fileSize").value(10));
    }

    @Test
    @DisplayName("CI publish is idempotent for same platform and version code")
    void publishFromCi_samePlatformAndVersionCode_updatesExistingVersion() throws Exception {
        int versionCode = 14000 + (int) (System.nanoTime() % 1000);
        MockMultipartFile firstMetadata = new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                ("{\"platform\":\"WINDOWS\",\"versionName\":\"1.1.0-ci\",\"versionCode\":"
                        + versionCode + ",\"releaseNotes\":\"first\"}").getBytes()
        );
        MockMultipartFile firstArtifact = new MockMultipartFile(
                "artifact",
                "pm-chat-windows-first.zip",
                "application/zip",
                "first".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/app/version/publish-from-ci")
                        .file(firstMetadata)
                        .file(firstArtifact)
                        .header("Authorization", "Bearer test-ci-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.fileSize").value(5));

        MockMultipartFile secondMetadata = new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                ("{\"platform\":\"WINDOWS\",\"versionName\":\"1.1.0-ci-rerun\",\"versionCode\":"
                        + versionCode + ",\"releaseNotes\":\"rerun\"}").getBytes()
        );
        MockMultipartFile secondArtifact = new MockMultipartFile(
                "artifact",
                "pm-chat-windows-rerun.zip",
                "application/zip",
                "second-run".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/app/version/publish-from-ci")
                        .file(secondMetadata)
                        .file(secondArtifact)
                        .header("Authorization", "Bearer test-ci-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.versionName").value("1.1.0-ci-rerun"))
                .andExpect(jsonPath("$.version.downloadUrl")
                        .value("/api/v1/app/download/windows/pm-chat-windows-rerun.zip"))
                .andExpect(jsonPath("$.version.fileSize").value(10));

        var versions = versionRepository.findByPlatformOrderByVersionCodeDesc(
                com.chatapp.entity.DeviceToken.Platform.WINDOWS);
        long matching = versions.stream()
                .filter(version -> version.getVersionCode().equals(versionCode))
                .count();
        assertEquals(1, matching);
    }

    @Test
    @DisplayName("CI publish without token is rejected")
    void publishFromCi_withoutToken_isRejected() throws Exception {
        MockMultipartFile metadata = new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                """
                {"platform":"ANDROID","versionName":"1.1.0","versionCode":11000}
                """.getBytes()
        );

        mockMvc.perform(multipart("/api/v1/app/version/publish-from-ci")
                        .file(metadata))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CI publish with invalid token is rejected")
    void publishFromCi_withInvalidToken_isRejected() throws Exception {
        MockMultipartFile metadata = new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                """
                {"platform":"ANDROID","versionName":"1.1.0","versionCode":11000}
                """.getBytes()
        );

        mockMvc.perform(multipart("/api/v1/app/version/publish-from-ci")
                        .file(metadata)
                        .header("X-Publish-Token", "wrong-token"))
                .andExpect(status().isUnauthorized());
    }
}
