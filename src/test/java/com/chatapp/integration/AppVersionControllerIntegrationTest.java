package com.chatapp.integration;

import com.chatapp.entity.User;
import com.chatapp.repository.AppVersionRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.CloudStorageService;
import com.chatapp.service.LLMService;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.TokenBlacklistService;
import com.chatapp.entity.DeviceToken;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    @DisplayName("Version check without platform is a 400 that names the parameter")
    void checkVersion_withoutPlatform_isBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/app/version"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("缺少必填参数: platform"));
    }

    @Test
    @DisplayName("Published artifacts carry a SHA-256 that clients can verify")
    void publishFromCi_recordsSha256_andVersionCheckReturnsIt() throws Exception {
        int versionCode = 15000 + (int) (System.nanoTime() % 1000);
        MockMultipartFile metadata = new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                ("{\"platform\":\"MACOS\",\"versionName\":\"1.2.0-sha\",\"versionCode\":"
                        + versionCode + "}").getBytes()
        );
        MockMultipartFile artifact = new MockMultipartFile(
                "artifact",
                "pm-chat-macos-sha.zip",
                "application/zip",
                "abc".getBytes()
        );
        // sha256("abc")
        String expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

        mockMvc.perform(multipart("/api/v1/app/version/publish-from-ci")
                        .file(metadata)
                        .file(artifact)
                        .header("Authorization", "Bearer test-ci-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.sha256").value(expected));

        mockMvc.perform(get("/api/v1/app/version")
                        .param("platform", "macos")
                        .param("currentVersionCode", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updateAvailable").value(true))
                .andExpect(jsonPath("$.fileSize").value(3))
                .andExpect(jsonPath("$.sha256").value(expected));
    }

    // ---- Android 按 ABI 拆包 ----

    @AfterEach
    void removeAndroidSplitVersions() {
        // 分包行不会被整包发布下架：清掉，免得别的测试查"最新 Android 版本"时查到它们。
        versionRepository.findByPlatformOrderByVersionCodeDesc(DeviceToken.Platform.ANDROID).stream()
                .filter(v -> !v.getAbi().isEmpty())
                .forEach(versionRepository::delete);
    }

    private void clearAndroidVersions() {
        versionRepository.deleteAll(
                versionRepository.findByPlatformOrderByVersionCodeDesc(DeviceToken.Platform.ANDROID));
    }

    private ResultActions publishAndroid(int versionCode, String abi, String filename, String content)
            throws Exception {
        String abiJson = abi == null ? "" : ",\"abi\":\"" + abi + "\"";
        MockMultipartFile metadata = new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                ("{\"platform\":\"ANDROID\",\"versionName\":\"1.2.0\",\"versionCode\":"
                        + versionCode + abiJson + "}").getBytes()
        );
        MockMultipartFile artifact = new MockMultipartFile(
                "artifact", filename, "application/vnd.android.package-archive", content.getBytes());
        return mockMvc.perform(multipart("/api/v1/app/version/publish-from-ci")
                .file(metadata)
                .file(artifact)
                .header("Authorization", "Bearer test-ci-token"));
    }

    private static String sha256Hex(String content) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content.getBytes()));
    }

    @Test
    @DisplayName("Both ABI builds of one Android version are published side by side")
    void publishFromCi_twoAbis_bothStayActive_andEachAbiGetsItsOwnApk() throws Exception {
        clearAndroidVersions();
        int code = 11052;
        publishAndroid(code, "arm64-v8a", "pm-chat-android-arm64-v8a-v1.2.0-11052.apk", "arm64-apk!")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.abi").value("arm64-v8a"));
        publishAndroid(code, "armeabi-v7a", "pm-chat-android-armeabi-v7a-v1.2.0-11052.apk", "v7a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.abi").value("armeabi-v7a"));

        // 发 32 位包不能把 64 位包下架。
        var rows = versionRepository.findByPlatformOrderByVersionCodeDesc(DeviceToken.Platform.ANDROID);
        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(v -> Boolean.TRUE.equals(v.getIsActive())));

        mockMvc.perform(get("/api/v1/app/version")
                        .param("platform", "ANDROID")
                        .param("currentVersionCode", "11051")
                        .param("abi", "armeabi-v7a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updateAvailable").value(true))
                .andExpect(jsonPath("$.latestVersionCode").value(code))
                .andExpect(jsonPath("$.abi").value("armeabi-v7a"))
                .andExpect(jsonPath("$.downloadUrl").value(
                        "/api/v1/app/download/android/pm-chat-android-armeabi-v7a-v1.2.0-11052.apk"))
                .andExpect(jsonPath("$.fileSize").value(3))
                .andExpect(jsonPath("$.sha256").value(sha256Hex("v7a")));

        mockMvc.perform(get("/api/v1/app/version")
                        .param("platform", "ANDROID")
                        .param("currentVersionCode", "11051")
                        .param("abi", "arm64-v8a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.abi").value("arm64-v8a"))
                .andExpect(jsonPath("$.downloadUrl").value(
                        "/api/v1/app/download/android/pm-chat-android-arm64-v8a-v1.2.0-11052.apk"))
                .andExpect(jsonPath("$.fileSize").value(10))
                .andExpect(jsonPath("$.sha256").value(sha256Hex("arm64-apk!")));

        // 下载到的字节和上报的大小/摘要一致（客户端安装前会校验）。
        byte[] v7aBytes = mockMvc.perform(get(
                        "/api/v1/app/download/android/pm-chat-android-armeabi-v7a-v1.2.0-11052.apk"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals("v7a", new String(v7aBytes));
        byte[] arm64Bytes = mockMvc.perform(get(
                        "/api/v1/app/download/android/pm-chat-android-arm64-v8a-v1.2.0-11052.apk"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals("arm64-apk!", new String(arm64Bytes));
    }

    @Test
    @DisplayName("Old clients that send no ABI get the 64-bit APK")
    void checkVersion_withoutAbi_defaultsToArm64() throws Exception {
        clearAndroidVersions();
        publishAndroid(11052, "armeabi-v7a", "pm-chat-android-armeabi-v7a-v1.2.0-11052.apk", "v7a")
                .andExpect(status().isOk());
        publishAndroid(11052, "arm64-v8a", "pm-chat-android-arm64-v8a-v1.2.0-11052.apk", "arm64-apk!")
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/app/version")
                        .param("platform", "android")
                        .param("currentVersionCode", "11051"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updateAvailable").value(true))
                .andExpect(jsonPath("$.abi").value("arm64-v8a"))
                .andExpect(jsonPath("$.downloadUrl").value(
                        "/api/v1/app/download/android/pm-chat-android-arm64-v8a-v1.2.0-11052.apk"))
                .andExpect(jsonPath("$.fileSize").value(10));
    }

    @Test
    @DisplayName("The pre-split universal APK keeps serving an ABI whose split is not published yet")
    void checkVersion_fallsBackToUniversalApk_forAbiWithoutSplit() throws Exception {
        clearAndroidVersions();
        publishAndroid(11051, null, "pm-chat-android-v1.1.51-11051.apk", "universal")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.abi").doesNotExist());
        publishAndroid(11052, "arm64-v8a", "pm-chat-android-arm64-v8a-v1.2.0-11052.apk", "arm64-apk!")
                .andExpect(status().isOk());

        // 64 位：新分包。
        mockMvc.perform(get("/api/v1/app/version")
                        .param("platform", "ANDROID")
                        .param("currentVersionCode", "0")
                        .param("abi", "arm64-v8a"))
                .andExpect(jsonPath("$.latestVersionCode").value(11052))
                .andExpect(jsonPath("$.abi").value("arm64-v8a"));
        // 32 位分包还没发：整包仍然有效（下载页的 32 位链接不会空）。
        mockMvc.perform(get("/api/v1/app/version")
                        .param("platform", "ANDROID")
                        .param("currentVersionCode", "0")
                        .param("abi", "armeabi-v7a"))
                .andExpect(jsonPath("$.latestVersionCode").value(11051))
                .andExpect(jsonPath("$.abi").doesNotExist())
                .andExpect(jsonPath("$.downloadUrl").value(
                        "/api/v1/app/download/android/pm-chat-android-v1.1.51-11051.apk"));
        // 已经是 11051 的 32 位手机：没有更新，而不是被推 64 位包。
        mockMvc.perform(get("/api/v1/app/version")
                        .param("platform", "ANDROID")
                        .param("currentVersionCode", "11051")
                        .param("abi", "armeabi-v7a"))
                .andExpect(jsonPath("$.updateAvailable").value(false));
    }

    @Test
    @DisplayName("Re-running the CI publish of one ABI updates that ABI's row only")
    void publishFromCi_sameAbiTwice_isIdempotent() throws Exception {
        clearAndroidVersions();
        publishAndroid(11052, "arm64-v8a", "pm-chat-android-arm64-v8a-v1.2.0-11052.apk", "first")
                .andExpect(status().isOk());
        publishAndroid(11052, "armeabi-v7a", "pm-chat-android-armeabi-v7a-v1.2.0-11052.apk", "v7a")
                .andExpect(status().isOk());
        publishAndroid(11052, "arm64-v8a", "pm-chat-android-arm64-v8a-v1.2.0-11052.apk", "second-run")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.fileSize").value(10));

        var rows = versionRepository.findByPlatformOrderByVersionCodeDesc(DeviceToken.Platform.ANDROID);
        assertEquals(2, rows.size());
        assertEquals(1, rows.stream().filter(v -> "arm64-v8a".equals(v.getAbi())).count());
        assertTrue(rows.stream().allMatch(v -> Boolean.TRUE.equals(v.getIsActive())));
    }

    @Test
    @DisplayName("Unknown ABIs are rejected, and non-Android releases cannot carry an ABI")
    void abi_validation() throws Exception {
        mockMvc.perform(get("/api/v1/app/version")
                        .param("platform", "ANDROID")
                        .param("abi", "mips"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("不支持的 Android 架构: mips"));

        MockMultipartFile metadata = new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                "{\"platform\":\"WINDOWS\",\"versionName\":\"1.2.0\",\"versionCode\":11052,\"abi\":\"arm64-v8a\"}"
                        .getBytes()
        );
        mockMvc.perform(multipart("/api/v1/app/version/publish-from-ci")
                        .file(metadata)
                        .header("Authorization", "Bearer test-ci-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("只有 Android 安装包区分 CPU 架构"));
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
