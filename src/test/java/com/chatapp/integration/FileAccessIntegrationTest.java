package com.chatapp.integration;

import com.chatapp.entity.AuditLog;
import com.chatapp.entity.Sticker;
import com.chatapp.entity.StickerPack;
import com.chatapp.repository.AuditLogRepository;
import com.chatapp.repository.StickerPackRepository;
import com.chatapp.repository.StickerRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.AgentGatewayService;
import com.chatapp.service.CloudStorageService;
import com.chatapp.service.FileStorageService;
import com.chatapp.service.LLMService;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.SelfDestructService;
import com.chatapp.service.TokenBlacklistService;
import com.chatapp.service.UrlPreviewService;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 受保护文件（聊天附件 / 贴纸图）的访问控制：转发副本、贴纸消息、贴纸包可见性。
 */
@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
    "spring.main.allow-circular-references=true",
    "spring.main.allow-bean-definition-overriding=true",
    "server.servlet.context-path=",
    "spring.jpa.open-in-view=false",
    "file.storage.upload-dir=target/test-uploads/file-access-integration"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FileAccessIntegrationTest {

    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private StickerPackRepository stickerPackRepository;
    @Autowired private StickerRepository stickerRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private FileStorageService fileStorageService;

    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private LLMService llmService;
    @MockBean private AgentGatewayService agentGatewayService;
    @MockBean private SelfDestructService selfDestructService;
    @MockBean private CloudStorageService cloudStorageService;
    @MockBean private UrlPreviewService urlPreviewService;

    private String uniqueSuffix;

    @BeforeEach
    void setUp() {
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        when(agentGatewayService.isConfigured()).thenReturn(false);
        uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("转发的附件：目标房间成员能看，只在原房间或都不在的人不能看；删原消息后副本仍可看")
    void forwardedAttachmentIsVisibleToTargetRoomMembers() throws Exception {
        TestUser alice = createUser("fa_alice");
        TestUser bob = createUser("fa_bob");
        TestUser carol = createUser("fa_carol");
        TestUser dave = createUser("fa_dave");

        Long sourceRoom = createGroupChat(alice.token, "源房间 " + uniqueSuffix, List.of(bob.id));
        Long targetRoom = createGroupChat(alice.token, "目标房间 " + uniqueSuffix, List.of(carol.id));

        Map<String, Object> sent = sendFileMessage(alice.token, sourceRoom,
                new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES));
        String fileUrl = (String) sent.get("fileUrl");
        Long sourceMessageId = ((Number) sent.get("id")).longValue();

        // 转发之前 carol 不在任何引用该文件的房间
        getFile(carol.token, fileUrl).andExpect(status().isForbidden());

        Long forwardedId = forward(alice.token, sourceMessageId, targetRoom);

        getFile(carol.token, fileUrl)
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG_BYTES));
        getFile(bob.token, fileUrl).andExpect(status().isOk());
        getFile(dave.token, fileUrl).andExpect(status().isForbidden());

        // 审计记录的是真正给 carol 放行的那条消息（转发副本），不是源房间的第一条
        AuditLog carolAudit = auditLogRepository.findAll().stream()
                .filter(log -> "FILE_DOWNLOAD".equals(log.getAction()))
                .filter(log -> carol.id.equals(log.getActorId()))
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(carolAudit.getResourceId()).isEqualTo(forwardedId);
        assertThat(carolAudit.getChatRoomId()).isEqualTo(targetRoom);

        // 删除原消息后，转发副本仍然让目标房间成员可见；只在原房间的 bob 失去访问权
        mockMvc.perform(delete("/api/v1/messages/" + sourceMessageId)
                        .header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isOk());
        getFile(carol.token, fileUrl).andExpect(status().isOk());
        getFile(bob.token, fileUrl).andExpect(status().isForbidden());
        getFile(dave.token, fileUrl).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("公开贴纸包：未发送过的贴纸任何登录用户都能加载，未登录不行")
    void publicStickerIsVisibleToAnyLoggedInUserBeforeBeingSent() throws Exception {
        TestUser owner = createUser("st_owner");
        TestUser stranger = createUser("st_stranger");

        Map<String, Object> pack = createPack(owner.token, "公开包", true);
        Long packId = ((Number) pack.get("id")).longValue();
        String coverUrl = (String) pack.get("coverUrl");
        List<Map<String, Object>> stickers = listStickers(stranger.token, packId)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString().transform(this::stickerList);
        String stickerUrl = (String) stickers.get(0).get("url");

        assertThat(stickerUrl).startsWith("/api/files/sticker/");
        assertThat(coverUrl).isEqualTo(stickerUrl);
        getFile(stranger.token, stickerUrl)
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG_BYTES));
        mockMvc.perform(get(stickerUrl)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("私有贴纸包：只有上传者/订阅者能看，陌生人不能列出、订阅、发送或加载")
    void privateStickerIsHiddenFromStrangers() throws Exception {
        TestUser owner = createUser("ps_owner");
        TestUser stranger = createUser("ps_stranger");
        TestUser friend = createUser("ps_friend");

        Map<String, Object> pack = createPack(owner.token, "私有包", false);
        Long packId = ((Number) pack.get("id")).longValue();
        List<Map<String, Object>> stickers = listStickers(owner.token, packId)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString().transform(this::stickerList);
        String stickerUrl = (String) stickers.get(0).get("url");
        Long stickerId = ((Number) stickers.get(0).get("id")).longValue();

        getFile(owner.token, stickerUrl).andExpect(status().isOk());
        getFile(stranger.token, stickerUrl).andExpect(status().isForbidden());
        listStickers(stranger.token, packId).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/sticker-packs/" + packId + "/subscribe")
                        .header("Authorization", "Bearer " + stranger.token))
                .andExpect(status().isForbidden());

        // 陌生人不能靠猜 stickerId 把私有贴纸发进自己的房间
        Long strangerRoom = createGroupChat(stranger.token, "陌生人房间 " + uniqueSuffix, List.of(friend.id));
        sendSticker(stranger.token, strangerRoom, stickerId).andExpect(status().isBadRequest());
        getFile(friend.token, stickerUrl).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("私有贴纸发进房间后，收到的人能加载，房间外的人仍不能")
    void privateStickerSentToRoomIsVisibleToRecipients() throws Exception {
        TestUser owner = createUser("sr_owner");
        TestUser recipient = createUser("sr_recipient");
        TestUser outsider = createUser("sr_outsider");

        Map<String, Object> pack = createPack(owner.token, "私有包", false);
        Long packId = ((Number) pack.get("id")).longValue();
        Map<String, Object> sticker = listStickers(owner.token, packId)
                .andReturn().getResponse().getContentAsString().transform(this::stickerList).get(0);
        Long stickerId = ((Number) sticker.get("id")).longValue();

        Long roomId = createGroupChat(owner.token, "贴纸房间 " + uniqueSuffix, List.of(recipient.id));
        MvcResult result = sendSticker(owner.token, roomId, stickerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messageType").value("STICKER"))
                .andReturn();
        String messageFileUrl = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("fileUrl").asText();

        assertThat(messageFileUrl).isEqualTo(sticker.get("url"));
        getFile(recipient.token, messageFileUrl)
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG_BYTES));
        getFile(outsider.token, messageFileUrl).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("老贴纸行（/api/files/chat/ 地址）按贴纸包可见性继续可用")
    void legacyChatPathStickersFollowPackVisibility() throws Exception {
        TestUser owner = createUser("lg_owner");
        TestUser stranger = createUser("lg_stranger");

        String publicUrl = fileStorageService.uploadChatImageBytes("legacy.png", "image/png", PNG_BYTES);
        String privateUrl = fileStorageService.uploadChatImageBytes("legacy-private.png", "image/png", PNG_BYTES);
        saveLegacyPack(owner.id, true, publicUrl);
        saveLegacyPack(owner.id, false, privateUrl);

        getFile(stranger.token, publicUrl)
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG_BYTES));
        getFile(stranger.token, privateUrl).andExpect(status().isForbidden());
        getFile(owner.token, privateUrl).andExpect(status().isOk());
        // 既没有消息也没有贴纸引用的文件按不存在处理
        getFile(owner.token, "/api/files/chat/" + UUID.randomUUID() + ".png").andExpect(status().isNotFound());
    }

    // ---- helpers ----

    private void saveLegacyPack(Long ownerId, boolean isPublic, String url) {
        StickerPack pack = new StickerPack();
        pack.setName("legacy " + uniqueSuffix);
        pack.setOwnerUser(userRepository.findById(ownerId).orElseThrow());
        pack.setIsPublic(isPublic);
        pack.setCoverUrl(url);
        pack = stickerPackRepository.save(pack);
        Sticker sticker = new Sticker();
        sticker.setPack(pack);
        sticker.setUrl(url);
        sticker.setKeyword("legacy");
        stickerRepository.save(sticker);
    }

    private org.springframework.test.web.servlet.ResultActions getFile(String token, String fileUrl) throws Exception {
        return mockMvc.perform(get(fileUrl).header("Authorization", "Bearer " + token));
    }

    private org.springframework.test.web.servlet.ResultActions listStickers(String token, Long packId) throws Exception {
        return mockMvc.perform(get("/api/v1/sticker-packs/" + packId + "/stickers")
                .header("Authorization", "Bearer " + token));
    }

    private org.springframework.test.web.servlet.ResultActions sendSticker(String token, Long roomId, Long stickerId)
            throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("chatRoomId", roomId);
        request.put("messageType", "STICKER");
        request.put("stickerId", stickerId);
        return mockMvc.perform(post("/api/v1/messages")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> stickerList(String body) {
        try {
            return (List<Map<String, Object>>) objectMapper.readValue(body, Map.class).get("data");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createPack(String token, String name, boolean isPublic) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/sticker-packs")
                        .file(new MockMultipartFile("files", "smile.png", "image/png", PNG_BYTES))
                        .param("name", name)
                        .param("isPublic", Boolean.toString(isPublic))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return (Map<String, Object>) objectMapper.readValue(
                result.getResponse().getContentAsString(), Map.class).get("data");
    }

    @SuppressWarnings("unchecked")
    private Long forward(String token, Long messageId, Long targetRoomId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/messages/" + messageId + "/forward")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("targetChatRoomId", targetRoomId))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> data = (Map<String, Object>) objectMapper.readValue(
                result.getResponse().getContentAsString(), Map.class).get("data");
        return ((Number) data.get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sendFileMessage(String token, Long chatRoomId, MockMultipartFile file)
            throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/messages/file")
                        .file(file)
                        .param("chatRoomId", chatRoomId.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return (Map<String, Object>) objectMapper.readValue(
                result.getResponse().getContentAsString(), Map.class).get("data");
    }

    @SuppressWarnings("unchecked")
    private Long createGroupChat(String token, String name, List<Long> memberIds) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("name", name);
        request.put("description", "Test room");
        request.put("memberIds", memberIds);
        MvcResult result = mockMvc.perform(post("/api/v1/chat-rooms/group")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> chatRoom = (Map<String, Object>) objectMapper.readValue(
                result.getResponse().getContentAsString(), Map.class).get("chatRoom");
        return ((Number) chatRoom.get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private TestUser createUser(String prefix) throws Exception {
        String username = prefix + "_" + uniqueSuffix;
        Map<String, Object> register = new HashMap<>();
        register.put("username", username);
        register.put("email", username + "@test.com");
        register.put("password", "password123");
        register.put("displayName", username);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isOk());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", "password123"))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> loginData = (Map<String, Object>) objectMapper.readValue(
                login.getResponse().getContentAsString(), Map.class).get("data");
        String token = (String) loginData.get("accessToken");

        MvcResult validate = mockMvc.perform(get("/api/auth/validate").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> user = (Map<String, Object>) objectMapper.readValue(
                validate.getResponse().getContentAsString(), Map.class).get("data");
        return new TestUser(token, ((Number) user.get("id")).longValue());
    }

    private record TestUser(String token, Long id) {}
}
