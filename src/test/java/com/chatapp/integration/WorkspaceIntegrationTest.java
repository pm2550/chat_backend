package com.chatapp.integration;

import com.chatapp.service.AgentGatewayService;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
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
    "server.servlet.context-path=",
    "spring.jpa.open-in-view=false",
    "file.storage.upload-dir=target/test-uploads/workspace-integration"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkspaceIntegrationTest {

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
    private AgentGatewayService agentGatewayService;

    @MockBean
    private SelfDestructService selfDestructService;

    @MockBean
    private CloudStorageService cloudStorageService;

    private String uniqueSuffix;

    @BeforeEach
    void setUp() {
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        when(agentGatewayService.isConfigured()).thenReturn(false);
        uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("Workspace library supports private files, explicit sharing and versions")
    void workspaceFileLibrarySupportsPermissionsAndVersions() throws Exception {
        Object[] owner = createUserAndLogin("wsowner");
        String ownerToken = (String) owner[0];

        Object[] editor = createUserAndLogin("wseditor");
        String editorToken = (String) editor[0];
        Long editorId = (Long) editor[1];

        Object[] viewer = createUserAndLogin("wsviewer");
        String viewerToken = (String) viewer[0];
        Long viewerId = (Long) viewer[1];

        Object[] outsider = createUserAndLogin("wsoutsider");
        String outsiderToken = (String) outsider[0];
        Long outsiderId = (Long) outsider[1];

        Long workspaceId = createWorkspace(ownerToken, "PM team vault", "TEAM", false);
        addMember(ownerToken, workspaceId, editorId, "EDITOR");
        addMember(ownerToken, workspaceId, viewerId, "VIEWER");

        Long folderId = createFolder(editorToken, workspaceId, "Specs", null);
        Long rootFileId = uploadFile(editorToken, workspaceId, null, "root.txt", "root version").fileId();
        Long folderFileId = uploadFile(editorToken, workspaceId, folderId, "roadmap.txt", "v1").fileId();

        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/contents")
                .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.folders[0].id").value(folderId))
                .andExpect(jsonPath("$.data.files[*].id", hasItem(rootFileId.intValue())));

        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/files/" + rootFileId + "/download")
                .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());

        Long permissionId = grantPermission(ownerToken, workspaceId, "FILE", rootFileId, "USER", outsiderId, "VIEW");
        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/permissions")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", hasItem(permissionId.intValue())))
                .andExpect(jsonPath("$.data[*].resourceName", hasItem("root.txt")))
                .andExpect(jsonPath("$.data[*].principalType", hasItem("USER")));

        mockMvc.perform(delete("/api/v1/workspaces/" + workspaceId + "/permissions/" + permissionId)
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/files/" + rootFileId + "/download")
                .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());

        grantPermission(ownerToken, workspaceId, "FILE", rootFileId, "USER", outsiderId, "VIEW");
        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/files/" + rootFileId + "/download")
                .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andExpect(content().bytes("root version".getBytes()));

        lockWorkspace(ownerToken, workspaceId, true, "freeze before release");
        addVersion(editorToken, workspaceId, folderFileId, "roadmap-v2.txt", "blocked")
                .andExpect(status().isForbidden());

        addVersion(ownerToken, workspaceId, folderFileId, "roadmap-v2.txt", "v2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentVersion").value(2));

        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/files/" + folderFileId + "/versions/1/restore")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentVersion").value(3))
                .andExpect(jsonPath("$.data.displayName").value("roadmap.txt"));

        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/files/" + folderFileId + "/download")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(content().bytes("v1".getBytes()));

        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/files/" + folderFileId + "/versions")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].versionNumber").value(3))
                .andExpect(jsonPath("$.data[0].versionNote").value("恢复到 v1"))
                .andExpect(jsonPath("$.data[1].versionNumber").value(2))
                .andExpect(jsonPath("$.data[2].versionNumber").value(1));
    }

    @Test
    @DisplayName("Workspace lifecycle supports quotas, scanning, previews and trash restore")
    void workspaceLifecycleSupportsQuotaScanningPreviewAndTrashRestore() throws Exception {
        Object[] owner = createUserAndLogin("wslifecycle");
        String ownerToken = (String) owner[0];

        Long workspaceId = createWorkspace(ownerToken, "Lifecycle vault", "TEAM", false, 16L);
        Long fileId = uploadFile(ownerToken, workspaceId, null, "notes.txt", "hello").fileId();

        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId)
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usedBytes").value(5))
                .andExpect(jsonPath("$.data.quotaBytes").value(16));

        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/files/" + fileId + "/preview")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("inline")))
                .andExpect(content().bytes("hello".getBytes()));

        uploadFile(ownerToken, workspaceId, null, "tool.exe", "blocked", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("安全扫描未通过")));

        uploadFile(ownerToken, workspaceId, null, "sample.txt",
                "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("安全扫描未通过")));

        uploadFile(ownerToken, workspaceId, null, "large.txt", "this file is too large", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("配额不足")));

        mockMvc.perform(delete("/api/v1/workspaces/" + workspaceId + "/files/" + fileId)
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDeleted").value(true));

        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/contents")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files[*].id", not(hasItem(fileId.intValue()))));

        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/trash")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files[*].id", hasItem(fileId.intValue())));

        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/files/" + fileId + "/restore")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDeleted").value(false));

        Long folderId = createFolder(ownerToken, workspaceId, "Archive", null);
        Long childFileId = uploadFile(ownerToken, workspaceId, folderId, "child.txt", "kid").fileId();
        mockMvc.perform(delete("/api/v1/workspaces/" + workspaceId + "/folders/" + folderId)
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDeleted").value(true));
        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/trash")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.folders[*].id", hasItem(folderId.intValue())))
                .andExpect(jsonPath("$.data.files[*].id", hasItem(childFileId.intValue())));
        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/folders/" + folderId + "/restore")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDeleted").value(false));
        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/contents")
                .param("folderId", folderId.toString())
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files[*].id", hasItem(childFileId.intValue())));

        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/maintenance/orphans")
                .param("dryRun", "true")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dryRun").value(true))
                .andExpect(jsonPath("$.data.orphanCount").isNumber());
    }

    @Test
    @DisplayName("Bot-sourced workspace files require workspace bot access")
    void botSourcedWorkspaceFilesRequireBotAccess() throws Exception {
        Object[] owner = createUserAndLogin("wsbotowner");
        String ownerToken = (String) owner[0];

        Long botId = createBot(ownerToken);
        Long lockedWorkspaceId = createWorkspace(ownerToken, "No bot vault", "SERVICE", false);

        uploadFile(ownerToken, lockedWorkspaceId, null, "bot.txt", "blocked", botId)
                .andExpect(status().isForbidden());

        Long botWorkspaceId = createWorkspace(ownerToken, "Bot vault", "SERVICE", true);
        uploadFile(ownerToken, botWorkspaceId, null, "bot.txt", "ok", botId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceType").value("BOT"))
                .andExpect(jsonPath("$.data.sourceBotId").value(botId));
    }

    @Test
    @DisplayName("Malformed workspace permission payload returns bad request")
    void malformedPermissionPayloadReturnsBadRequest() throws Exception {
        Object[] owner = createUserAndLogin("wsmalformed");
        String ownerToken = (String) owner[0];

        Long workspaceId = createWorkspace(ownerToken, "Malformed vault", "TEAM", false);

        Map<String, Object> request = new HashMap<>();
        request.put("resourceType", "WORKSPACE");
        request.put("principalType", "USER");
        request.put("principalId", 9999L);
        request.put("accessLevel", "EDITOR");

        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/permissions")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("请求格式错误")));
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

        Map<String, Object> responseMap = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        Map<String, Object> data = (Map<String, Object>) responseMap.get("data");
        return (String) data.get("accessToken");
    }

    private Long getUserIdFromToken(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/validate")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> responseMap = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
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

    private Long createWorkspace(String token, String name, String type, boolean botAccess) throws Exception {
        return createWorkspace(token, name, type, botAccess, null);
    }

    private Long createWorkspace(String token, String name, String type, boolean botAccess, Long quotaBytes) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("name", name + "_" + uniqueSuffix);
        request.put("workspaceType", type);
        request.put("botAccessEnabled", botAccess);
        if (quotaBytes != null) {
            request.put("quotaBytes", quotaBytes);
        }

        MvcResult result = mockMvc.perform(post("/api/v1/workspaces")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();
        return extractDataId(result);
    }

    private void addMember(String token, Long workspaceId, Long userId, String role) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("userId", userId);
        request.put("role", role);
        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/members")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    private Long createFolder(String token, Long workspaceId, String name, Long parentFolderId) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("name", name);
        if (parentFolderId != null) {
            request.put("parentFolderId", parentFolderId);
        }
        MvcResult result = mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/folders")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();
        return extractDataId(result);
    }

    private UploadedFile uploadFile(String token, Long workspaceId, Long folderId, String fileName, String content) throws Exception {
        MvcResult result = uploadFile(token, workspaceId, folderId, fileName, content, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();
        return new UploadedFile(extractDataId(result));
    }

    private org.springframework.test.web.servlet.ResultActions uploadFile(
            String token,
            Long workspaceId,
            Long folderId,
            String fileName,
            String content,
            Long sourceBotId) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                fileName,
                "text/plain",
                content.getBytes());
        var request = multipart("/api/v1/workspaces/" + workspaceId + "/files")
                .file(file)
                .header("Authorization", "Bearer " + token);
        if (folderId != null) {
            request.param("folderId", folderId.toString());
        }
        if (sourceBotId != null) {
            request.param("sourceBotId", sourceBotId.toString());
        }
        return mockMvc.perform(request);
    }

    private org.springframework.test.web.servlet.ResultActions addVersion(
            String token,
            Long workspaceId,
            Long fileId,
            String fileName,
            String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                fileName,
                "text/plain",
                content.getBytes());
        return mockMvc.perform(multipart("/api/v1/workspaces/" + workspaceId + "/files/" + fileId + "/versions")
                .file(file)
                .header("Authorization", "Bearer " + token));
    }

    private Long grantPermission(
            String token,
            Long workspaceId,
            String resourceType,
            Long resourceId,
            String principalType,
            Long principalId,
            String accessLevel) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("resourceType", resourceType);
        request.put("resourceId", resourceId);
        request.put("principalType", principalType);
        request.put("principalId", principalId);
        request.put("accessLevel", accessLevel);
        MvcResult result = mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/permissions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();
        return extractDataId(result);
    }

    private void lockWorkspace(String token, Long workspaceId, boolean locked, String reason) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("locked", locked);
        request.put("reason", reason);
        mockMvc.perform(put("/api/v1/workspaces/" + workspaceId + "/lock")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    private Long createBot(String token) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("botName", "workspace-bot-" + uniqueSuffix);
        request.put("llmProvider", "OLLAMA");
        request.put("modelName", "test-model");

        MvcResult result = mockMvc.perform(post("/api/v1/bots")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();
        return extractDataId(result);
    }

    private Long extractDataId(MvcResult result) throws Exception {
        Map<String, Object> responseMap = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        Map<String, Object> data = (Map<String, Object>) responseMap.get("data");
        return ((Number) data.get("id")).longValue();
    }

    private record UploadedFile(Long fileId) {
    }
}
