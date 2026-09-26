package com.chatapp.integration;

import com.chatapp.dto.UserDto;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomBot;
import com.chatapp.entity.E2eeIdentityKey;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.BotConfigRepository;
import com.chatapp.repository.ChatRoomBotRepository;
import com.chatapp.repository.E2eeIdentityKeyRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.ChatRoomService;
import com.chatapp.service.E2eeKeyService;
import com.chatapp.service.UserService;
import com.chatapp.util.JwtUtils;
import com.chatapp.websocket.RawWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 私聊端到端加密的服务端部分：服务器只存密文和占位文字、推送不带内容、
 * 只有两个真人的私聊能发密文（有机器人的会话、群聊、匿名一律拒绝），
 * 以及密钥保管和改密码时的重新包装。
 */
@DisplayName("E2EE private chat integration")
class E2eeIntegrationTest extends IntegrationTestSupport {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ARGON2 = "m=65536,t=3,p=1,v=19,hashLen=32";

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private ChatRoomService chatRoomService;
    @Autowired private MessageRepository messageRepository;
    @Autowired private E2eeIdentityKeyRepository keyRepository;
    @Autowired private BotConfigRepository botConfigRepository;
    @Autowired private ChatRoomBotRepository chatRoomBotRepository;
    @Autowired private RawWebSocketHandler rawWebSocketHandler;
    @Autowired private JwtUtils jwtUtils;

    private final List<RawWebSocketIntegrationTest.TestWebSocketSession> openSessions = new ArrayList<>();

    private User alice;
    private User bob;
    private String aliceBearer;
    private String bobBearer;
    private ChatRoom dm;

    @BeforeEach
    void setUp() {
        alice = registerClientHashUser("e2ee_alice_" + uniqueSuffix, "Alice");
        bob = registerClientHashUser("e2ee_bob_" + uniqueSuffix, "Bob");
        aliceBearer = "Bearer " + jwtUtils.generateAccessToken(alice.getUsername());
        bobBearer = "Bearer " + jwtUtils.generateAccessToken(bob.getUsername());
        dm = chatRoomService.createPrivateChat(alice.getId(), bob.getId());
    }

    @AfterEach
    void tearDown() {
        for (RawWebSocketIntegrationTest.TestWebSocketSession session : openSessions) {
            rawWebSocketHandler.afterConnectionClosed(session, CloseStatus.NORMAL);
        }
        openSessions.clear();
    }

    @Test
    @DisplayName("key directory exposes public keys only; own keys include the wrapped private key")
    void key_lifecycle() throws Exception {
        String alicePublic = randomBase64(32);
        createKey(aliceBearer, alicePublic, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.activeKeyVersion").value(1));

        String directory = mockMvc.perform(get("/api/v1/e2ee/users/" + alice.getId() + "/keys")
                        .header("Authorization", bobBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.keys[0].publicKey").value(alicePublic))
                .andReturn().getResponse().getContentAsString();
        assertFalse(directory.contains("wrappedPrivateKey"), "the wrapped private key is only for its owner");

        mockMvc.perform(get("/api/v1/e2ee/keys/me").header("Authorization", aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passwordSchemeSupported").value(true))
                .andExpect(jsonPath("$.data.keys[0].wrappedPrivateKey").isNotEmpty());

        // 另一台设备还以为没有密钥，想再生成一把：拒绝，改为解锁已有的。
        createKey(aliceBearer, randomBase64(32), null).andExpect(status().isConflict());
        Map<String, Object> staleVersion = new HashMap<>();
        staleVersion.put("keyVersion", 1);
        staleVersion.put("publicKey", randomBase64(32));
        staleVersion.put("wrappedPrivateKey", randomBase64(60));
        staleVersion.put("wrapSalt", randomBase64(16));
        staleVersion.put("wrapParams", ARGON2);
        staleVersion.put("expectedActiveKeyVersion", 1);
        mockMvc.perform(post("/api/v1/e2ee/keys")
                        .header("Authorization", aliceBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(staleVersion)))
                .andExpect(status().isConflict());
        createKey(aliceBearer, randomBase64(32), 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeKeyVersion").value(2))
                .andExpect(jsonPath("$.data.keys.length()").value(2));

        // 关掉只是"新消息不加密"，密钥都在。
        mockMvc.perform(put("/api/v1/e2ee/enabled")
                        .header("Authorization", aliceBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.keys.length()").value(2));
        mockMvc.perform(get("/api/v1/e2ee/rooms/" + dm.getId() + "/status").header("Authorization", bobBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligible").value(true))
                .andExpect(jsonPath("$.data.peer.userId").value(alice.getId()))
                .andExpect(jsonPath("$.data.peer.enabled").value(false));
    }

    @Test
    @DisplayName("accounts whose login still sends the plain password cannot create keys")
    void legacy_password_scheme_cannot_enable() throws Exception {
        UserDto.RegisterRequest request = new UserDto.RegisterRequest();
        request.setUsername("e2ee_legacy_" + uniqueSuffix);
        request.setPassword("password123");
        request.setEmail("e2ee_legacy_" + uniqueSuffix + "@test.com");
        request.setDisplayName("Legacy");
        UserDto legacy = userService.registerUser(request);
        String bearer = "Bearer " + jwtUtils.generateAccessToken(legacy.getUsername());

        createKey(bearer, randomBase64(32), null).andExpect(status().isBadRequest());
        assertFalse(keyRepository.existsByUserId(legacy.getId()));
    }

    @Test
    @DisplayName("password change must re-wrap the private key in the same transaction")
    void password_change_rewraps_keys() throws Exception {
        createKey(aliceBearer, randomBase64(32), null).andExpect(status().isOk());
        String originalHash = userRepository.findById(alice.getId()).orElseThrow().getPassword();

        Map<String, Object> change = new HashMap<>();
        change.put("oldClientHash", clientHash("alice-old"));
        change.put("newClientHash", clientHash("alice-new"));
        change.put("newClientSalt", randomBase64Url(16));
        change.put("newArgon2Params", ARGON2);

        // 老客户端改密码不带新包装：拒绝，密码也不能改掉。
        mockMvc.perform(post("/api/profile/password")
                        .header("Authorization", aliceBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(change)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("端到端加密")));
        assertEquals(originalHash, userRepository.findById(alice.getId()).orElseThrow().getPassword());

        String newWrap = randomBase64(60);
        String newSalt = randomBase64(16);
        change.put("e2eeKeyWraps", List.of(Map.of(
                "version", 1,
                "wrappedPrivateKey", newWrap,
                "wrapSalt", newSalt,
                "wrapParams", ARGON2)));
        mockMvc.perform(post("/api/profile/password")
                        .header("Authorization", aliceBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(change)))
                .andExpect(status().isOk());

        E2eeIdentityKey key = keyRepository.findByUserIdAndKeyVersion(alice.getId(), 1).orElseThrow();
        assertEquals(newWrap, key.getWrappedPrivateKey());
        assertEquals(newSalt, key.getWrapSalt());
        assertFalse(originalHash.equals(userRepository.findById(alice.getId()).orElseThrow().getPassword()));
    }

    @Test
    @DisplayName("recovery wraps are stored per key version and only ever returned to their owner")
    void recovery_wraps_owner_only() throws Exception {
        createKey(aliceBearer, randomBase64(32), null).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/e2ee/keys/me").header("Authorization", aliceBearer))
                .andExpect(jsonPath("$.data.recoveryConfigured").value(false))
                .andExpect(jsonPath("$.data.keys[0].recoveryWrappedPrivateKey").doesNotExist());

        String recoveryWrap = randomBase64(60);
        String recoverySalt = randomBase64(16);
        setRecovery(aliceBearer, 1, List.of(recoveryWrap(1, recoveryWrap, recoverySalt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recoveryConfigured").value(true));

        mockMvc.perform(get("/api/v1/e2ee/keys/me").header("Authorization", aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recoveryConfigured").value(true))
                .andExpect(jsonPath("$.data.keys[0].recoveryWrappedPrivateKey").value(recoveryWrap))
                .andExpect(jsonPath("$.data.keys[0].recoveryWrapSalt").value(recoverySalt))
                .andExpect(jsonPath("$.data.keys[0].recoveryWrapParams").value(E2eeKeyService.RECOVERY_WRAP_PARAMS))
                // 密码包装还在，互不影响。
                .andExpect(jsonPath("$.data.keys[0].wrappedPrivateKey").isNotEmpty());

        // 别人：公钥目录、会话状态、自己的 /keys/me 都看不到 alice 的恢复码包装。
        String directory = mockMvc.perform(get("/api/v1/e2ee/users/" + alice.getId() + "/keys")
                        .header("Authorization", bobBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String roomStatus = mockMvc.perform(get("/api/v1/e2ee/rooms/" + dm.getId() + "/status")
                        .header("Authorization", bobBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bobOwn = mockMvc.perform(get("/api/v1/e2ee/keys/me").header("Authorization", bobBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keys.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        for (String body : List.of(directory, roomStatus, bobOwn)) {
            assertFalse(body.contains(recoveryWrap), "recovery wrap leaked: " + body);
            assertFalse(body.contains("recoveryWrap"), "recovery fields leaked: " + body);
        }
        // bob 写不到 alice 的行：接口只按登录身份操作，bob 自己没有密钥。
        setRecovery(bobBearer, null, List.of(recoveryWrap(1, randomBase64(60), randomBase64(16))))
                .andExpect(status().isBadRequest());
        assertEquals(recoveryWrap, keyRepository.findByUserIdAndKeyVersion(alice.getId(), 1)
                .orElseThrow().getRecoveryWrappedPrivateKey());

        // 格式不对、参数不认识、版本不存在、别的设备刚换过密钥：都拒绝。
        setRecovery(aliceBearer, 1, List.of(recoveryWrap(1, randomBase64(40), randomBase64(16))))
                .andExpect(status().isBadRequest());
        Map<String, Object> slowParams = new HashMap<>(recoveryWrap(1, randomBase64(60), randomBase64(16)));
        slowParams.put("wrapParams", ARGON2);
        setRecovery(aliceBearer, 1, List.of(slowParams)).andExpect(status().isBadRequest());
        setRecovery(aliceBearer, 1, List.of(recoveryWrap(7, randomBase64(60), randomBase64(16))))
                .andExpect(status().isBadRequest());
        setRecovery(aliceBearer, null, List.of(recoveryWrap(1, randomBase64(60), randomBase64(16))))
                .andExpect(status().isConflict());
        assertEquals(recoveryWrap, keyRepository.findByUserIdAndKeyVersion(alice.getId(), 1)
                .orElseThrow().getRecoveryWrappedPrivateKey());
    }

    @Test
    @DisplayName("new key version needs a new recovery code; regenerating drops wraps of the old code")
    void recovery_across_key_versions() throws Exception {
        createKey(aliceBearer, randomBase64(32), null).andExpect(status().isOk());
        String oldCodeWrap = randomBase64(60);
        setRecovery(aliceBearer, 1, List.of(recoveryWrap(1, oldCodeWrap, randomBase64(16))))
                .andExpect(status().isOk());

        // 密码被重置后重新生成密钥：新版本没有恢复码包装，客户端据此提示重新设置。
        createKey(aliceBearer, randomBase64(32), 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recoveryConfigured").value(false));
        assertEquals(oldCodeWrap, keyRepository.findByUserIdAndKeyVersion(alice.getId(), 1)
                .orElseThrow().getRecoveryWrappedPrivateKey());

        // 新恢复码必须包含当前版本。
        setRecovery(aliceBearer, 2, List.of(recoveryWrap(1, randomBase64(60), randomBase64(16))))
                .andExpect(status().isBadRequest());

        // 这台设备只解得开 v2：新恢复码只包 v2，v1 上旧恢复码的包装被清掉——旧码彻底作废。
        String newCodeWrap = randomBase64(60);
        setRecovery(aliceBearer, 2, List.of(recoveryWrap(2, newCodeWrap, randomBase64(16))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recoveryConfigured").value(true));
        E2eeIdentityKey v1 = keyRepository.findByUserIdAndKeyVersion(alice.getId(), 1).orElseThrow();
        E2eeIdentityKey v2 = keyRepository.findByUserIdAndKeyVersion(alice.getId(), 2).orElseThrow();
        assertNull(v1.getRecoveryWrappedPrivateKey());
        assertNull(v1.getRecoveryWrapSalt());
        assertNull(v1.getRecoveryWrapParams());
        assertEquals(newCodeWrap, v2.getRecoveryWrappedPrivateKey());
    }

    @Test
    @DisplayName("password change and recovery re-wrap only touch the password wrap")
    void password_rewrap_keeps_recovery_wrap() throws Exception {
        createKey(aliceBearer, randomBase64(32), null).andExpect(status().isOk());
        String recoveryWrap = randomBase64(60);
        setRecovery(aliceBearer, 1, List.of(recoveryWrap(1, recoveryWrap, randomBase64(16))))
                .andExpect(status().isOk());

        // 改密码：恢复码包装原样保留。
        Map<String, Object> change = new HashMap<>();
        change.put("oldClientHash", clientHash("alice-old"));
        change.put("newClientHash", clientHash("alice-new"));
        change.put("newClientSalt", randomBase64Url(16));
        change.put("newArgon2Params", ARGON2);
        change.put("e2eeKeyWraps", List.of(Map.of(
                "version", 1,
                "wrappedPrivateKey", randomBase64(60),
                "wrapSalt", randomBase64(16),
                "wrapParams", ARGON2)));
        mockMvc.perform(post("/api/profile/password")
                        .header("Authorization", aliceBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(change)))
                .andExpect(status().isOk());
        assertEquals(recoveryWrap, keyRepository.findByUserIdAndKeyVersion(alice.getId(), 1)
                .orElseThrow().getRecoveryWrappedPrivateKey());

        // 用恢复码找回后换密码包装：必须带对当前密码，否则 422 且什么都不改。
        String before = keyRepository.findByUserIdAndKeyVersion(alice.getId(), 1)
                .orElseThrow().getWrappedPrivateKey();
        String newWrap = randomBase64(60);
        rewrapWithPassword(aliceBearer, clientHash("alice-old"), newWrap)
                .andExpect(status().isUnprocessableEntity());
        rewrapWithPassword(aliceBearer, null, newWrap).andExpect(status().isUnprocessableEntity());
        assertEquals(before, keyRepository.findByUserIdAndKeyVersion(alice.getId(), 1)
                .orElseThrow().getWrappedPrivateKey());

        rewrapWithPassword(aliceBearer, clientHash("alice-new"), newWrap)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keys[0].wrappedPrivateKey").value(newWrap))
                .andExpect(jsonPath("$.data.keys[0].recoveryWrappedPrivateKey").value(recoveryWrap));

        // bob 的请求只作用于 bob 自己（他没有密钥）。
        rewrapWithPassword(bobBearer, clientHash("bob-old"), randomBase64(60))
                .andExpect(status().isBadRequest());
        assertEquals(newWrap, keyRepository.findByUserIdAndKeyVersion(alice.getId(), 1)
                .orElseThrow().getWrappedPrivateKey());
    }

    @Test
    @DisplayName("encrypted DM over WebSocket: server stores only ciphertext, push body is a placeholder")
    void encrypted_ws_message_stores_ciphertext_only() throws Exception {
        byte[] envelope = randomBytes(180);
        String envelopeBase64 = Base64.getEncoder().encodeToString(envelope);
        RawWebSocketIntegrationTest.TestWebSocketSession aliceSession = connect(alice);
        RawWebSocketIntegrationTest.TestWebSocketSession bobSession = connect(bob);
        drain(aliceSession, bobSession);

        sendWs(aliceSession, Map.of(
                "type", "message",
                "chatRoomId", dm.getId(),
                // 故意带上明文：服务器必须丢掉它，只存占位。
                "content", "top secret plaintext",
                "messageType", "TEXT",
                "encryptedContent", envelopeBase64,
                "encryptionVersion", E2eeKeyService.MESSAGE_ENCRYPTION_VERSION));

        JsonNode received = await(bobSession, "message");
        assertNotNull(received, "bob should receive the encrypted message");
        assertEquals(E2eeKeyService.OLD_CLIENT_PLACEHOLDER, received.path("message").path("content").asText());
        assertEquals(envelopeBase64, received.path("message").path("encryptedContent").asText());
        assertEquals(E2eeKeyService.MESSAGE_ENCRYPTION_VERSION,
                received.path("message").path("encryptionVersion").asInt());
        assertFalse(received.toString().contains("top secret plaintext"));

        Long messageId = received.path("message").path("id").asLong();
        Message stored = messageRepository.findById(messageId).orElseThrow();
        assertEquals(E2eeKeyService.OLD_CLIENT_PLACEHOLDER, stored.getContent());
        assertArrayEquals(envelope, stored.getEncryptedContent());
        assertNull(stored.getLinkPreviewJson());

        // bob 离线：推送只有"[加密消息]"，没有任何内容。
        rawWebSocketHandler.afterConnectionClosed(bobSession, CloseStatus.NORMAL);
        openSessions.remove(bobSession);
        clearInvocations(pushNotificationService);
        sendWs(aliceSession, Map.of(
                "type", "message",
                "chatRoomId", dm.getId(),
                "content", "second secret https://example.com",
                "messageType", "TEXT",
                "encryptedContent", envelopeBase64,
                "encryptionVersion", E2eeKeyService.MESSAGE_ENCRYPTION_VERSION));
        assertNotNull(await(aliceSession, "message"));
        verify(pushNotificationService).sendPushNotification(
                eq(bob.getId()), anyString(), eq(E2eeKeyService.NOTIFICATION_PLACEHOLDER), anyString());
        verify(pushNotificationService, never()).sendPushNotification(
                anyLong(), anyString(), org.mockito.ArgumentMatchers.contains("secret"), anyString());

        // 搜索不会因为占位文字把加密消息都搜出来。
        mockMvc.perform(get("/api/v1/chat-rooms/" + dm.getId() + "/messages/search")
                        .param("q", "加密")
                        .header("Authorization", bobBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("encrypted messages are refused in rooms with bots, group rooms and anonymous sends")
    void encrypted_messages_only_in_human_private_chats() throws Exception {
        String envelope = randomBase64(120);
        RawWebSocketIntegrationTest.TestWebSocketSession aliceSession = connect(alice);
        drain(aliceSession);

        BotConfig bot = new BotConfig();
        bot.setBotName("helper-" + uniqueSuffix);
        bot.setLlmProvider(BotConfig.LLMProvider.OPENAI);
        bot.setCreatedBy(alice);
        bot = botConfigRepository.save(bot);
        ChatRoomBot binding = new ChatRoomBot();
        binding.setChatRoom(dm);
        binding.setBotConfig(bot);
        binding = chatRoomBotRepository.save(binding);

        mockMvc.perform(get("/api/v1/e2ee/rooms/" + dm.getId() + "/status").header("Authorization", aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligible").value(false))
                .andExpect(jsonPath("$.data.reason").value(E2eeKeyService.REASON_HAS_BOTS));
        sendWs(aliceSession, encryptedFrame(dm.getId(), envelope));
        JsonNode rejected = await(aliceSession, "error");
        assertNotNull(rejected, "a DM with a bot must not accept ciphertext the bot cannot read");
        assertTrue(rejected.path("message").asText().contains("机器人"));
        chatRoomBotRepository.delete(binding);

        ChatRoom group = chatRoomService.createGroupChat(alice.getId(), "e2ee-group-" + uniqueSuffix, "g",
                List.of(bob.getId()));
        sendWs(aliceSession, encryptedFrame(group.getId(), envelope));
        assertNotNull(await(aliceSession, "error"), "group chats stay unencrypted");

        Map<String, Object> anonymous = new HashMap<>(encryptedFrame(dm.getId(), envelope));
        anonymous.put("isAnonymous", true);
        sendWs(aliceSession, anonymous);
        assertNotNull(await(aliceSession, "error"));

        Map<String, Object> wrongVersion = new HashMap<>(encryptedFrame(dm.getId(), envelope));
        wrongVersion.put("encryptionVersion", 1);
        sendWs(aliceSession, wrongVersion);
        assertNotNull(await(aliceSession, "error"));

        assertEquals(0, messageRepository.findAll().stream()
                .filter(m -> m.getChatRoom().getId().equals(dm.getId())
                        || m.getChatRoom().getId().equals(group.getId()))
                .count());
    }

    @Test
    @DisplayName("encrypted attachments are stored as opaque FILE blobs: no real name, type or transcoding")
    void encrypted_attachment_is_opaque() throws Exception {
        String envelope = randomBase64(200);
        org.springframework.mock.web.MockMultipartFile ciphertext = new org.springframework.mock.web.MockMultipartFile(
                "file", "encrypted.bin", "application/octet-stream", randomBytes(4096));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/messages/file")
                        .file(ciphertext)
                        .param("chatRoomId", dm.getId().toString())
                        .param("messageType", "VOICE")
                        .param("encryptedContent", envelope)
                        .param("encryptionVersion", String.valueOf(E2eeKeyService.MESSAGE_ENCRYPTION_VERSION))
                        .header("Authorization", aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messageType").value("FILE"))
                .andExpect(jsonPath("$.data.fileName").value(E2eeKeyService.ATTACHMENT_FILE_NAME))
                .andExpect(jsonPath("$.data.fileType").value("application/octet-stream"))
                .andExpect(jsonPath("$.data.fileUrl").value(org.hamcrest.Matchers.endsWith(".bin")))
                .andExpect(jsonPath("$.data.content").value(E2eeKeyService.OLD_CLIENT_PLACEHOLDER))
                .andExpect(jsonPath("$.data.encryptedContent").value(envelope));

        // 群聊里不能上传加密附件（先检查，不会先把密文存下来）。
        ChatRoom group = chatRoomService.createGroupChat(alice.getId(), "att-" + uniqueSuffix, "g",
                List.of(bob.getId()));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/messages/file")
                        .file(ciphertext)
                        .param("chatRoomId", group.getId().toString())
                        .param("encryptedContent", envelope)
                        .param("encryptionVersion", String.valueOf(E2eeKeyService.MESSAGE_ENCRYPTION_VERSION))
                        .header("Authorization", aliceBearer))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("encrypted image thumbnails: client-sealed .bin blob, visible to the two participants only")
    void encrypted_thumbnail_is_stored_opaque_and_protected() throws Exception {
        String envelope = randomBase64(200);
        byte[] sealedThumbnail = randomBytes(3000);
        org.springframework.mock.web.MockMultipartFile ciphertext = new org.springframework.mock.web.MockMultipartFile(
                "file", "encrypted.bin", "application/octet-stream", randomBytes(4096));
        org.springframework.mock.web.MockMultipartFile thumbnail = new org.springframework.mock.web.MockMultipartFile(
                "thumbnail", "encrypted.bin", "application/octet-stream", sealedThumbnail);

        String body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/messages/file")
                        .file(ciphertext)
                        .file(thumbnail)
                        .param("chatRoomId", dm.getId().toString())
                        .param("messageType", "IMAGE")
                        .param("encryptedContent", envelope)
                        .param("encryptionVersion", String.valueOf(E2eeKeyService.MESSAGE_ENCRYPTION_VERSION))
                        .header("Authorization", aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messageType").value("FILE"))
                .andExpect(jsonPath("$.data.thumbnailUrl").value(org.hamcrest.Matchers.endsWith(".bin")))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).path("data");
        String thumbnailUrl = data.path("thumbnailUrl").asText();
        String fileUrl = data.path("fileUrl").asText();
        assertNotEquals(fileUrl, thumbnailUrl);
        // 服务器看不到图：不能从密文里读出尺寸，也不该存。
        assertTrue(data.path("width").isNull() || data.path("width").isMissingNode());

        mockMvc.perform(get(thumbnailUrl).header("Authorization", bobBearer))
                .andExpect(status().isOk())
                .andExpect(content().bytes(sealedThumbnail));
        User carol = registerClientHashUser("e2ee_thumb_carol_" + uniqueSuffix, "Carol");
        mockMvc.perform(get(thumbnailUrl)
                        .header("Authorization", "Bearer " + jwtUtils.generateAccessToken(carol.getUsername())))
                .andExpect(status().isForbidden());

        // 过大的"缩略图"不收，但消息照常发出（客户端退回加载原图）。
        org.springframework.mock.web.MockMultipartFile oversized = new org.springframework.mock.web.MockMultipartFile(
                "thumbnail", "encrypted.bin", "application/octet-stream", randomBytes(600 * 1024));
        String second = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/messages/file")
                        .file(ciphertext)
                        .file(oversized)
                        .param("chatRoomId", dm.getId().toString())
                        .param("encryptedContent", envelope)
                        .param("encryptionVersion", String.valueOf(E2eeKeyService.MESSAGE_ENCRYPTION_VERSION))
                        .header("Authorization", aliceBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode secondData = objectMapper.readTree(second).path("data");
        assertTrue(secondData.path("thumbnailUrl").isNull() || secondData.path("thumbnailUrl").isMissingNode());
    }

    @Test
    @DisplayName("encrypted big-image preview: client-sealed .bin next to the thumbnail, same access rule")
    void encrypted_preview_is_stored_opaque_and_protected() throws Exception {
        String envelope = randomBase64(200);
        byte[] sealedPreview = randomBytes(150 * 1024);
        org.springframework.mock.web.MockMultipartFile ciphertext = new org.springframework.mock.web.MockMultipartFile(
                "file", "encrypted.bin", "application/octet-stream", randomBytes(4096));
        org.springframework.mock.web.MockMultipartFile thumbnail = new org.springframework.mock.web.MockMultipartFile(
                "thumbnail", "encrypted.bin", "application/octet-stream", randomBytes(3000));
        org.springframework.mock.web.MockMultipartFile preview = new org.springframework.mock.web.MockMultipartFile(
                "preview", "encrypted.bin", "application/octet-stream", sealedPreview);

        String body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/messages/file")
                        .file(ciphertext)
                        .file(thumbnail)
                        .file(preview)
                        .param("chatRoomId", dm.getId().toString())
                        .param("messageType", "IMAGE")
                        .param("encryptedContent", envelope)
                        .param("encryptionVersion", String.valueOf(E2eeKeyService.MESSAGE_ENCRYPTION_VERSION))
                        .header("Authorization", aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previewUrl").value(org.hamcrest.Matchers.endsWith(".bin")))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).path("data");
        String previewUrl = data.path("previewUrl").asText();
        assertNotEquals(data.path("fileUrl").asText(), previewUrl);
        assertNotEquals(data.path("thumbnailUrl").asText(), previewUrl);

        mockMvc.perform(get(previewUrl).header("Authorization", bobBearer))
                .andExpect(status().isOk())
                .andExpect(content().bytes(sealedPreview));
        User carol = registerClientHashUser("e2ee_prev_carol_" + uniqueSuffix, "Carol");
        mockMvc.perform(get(previewUrl)
                        .header("Authorization", "Bearer " + jwtUtils.generateAccessToken(carol.getUsername())))
                .andExpect(status().isForbidden());

        // 过大的"中图"不收，消息照常发出（客户端停在缩略图，点开再下原图）。
        org.springframework.mock.web.MockMultipartFile oversized = new org.springframework.mock.web.MockMultipartFile(
                "preview", "encrypted.bin", "application/octet-stream", randomBytes(3 * 1024 * 1024));
        String second = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/messages/file")
                        .file(ciphertext)
                        .file(oversized)
                        .param("chatRoomId", dm.getId().toString())
                        .param("encryptedContent", envelope)
                        .param("encryptionVersion", String.valueOf(E2eeKeyService.MESSAGE_ENCRYPTION_VERSION))
                        .header("Authorization", aliceBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode secondData = objectMapper.readTree(second).path("data");
        assertTrue(secondData.path("previewUrl").isNull() || secondData.path("previewUrl").isMissingNode());
    }

    @Test
    @DisplayName("the built-in mention-only Agent auto-attached to every chat does not block encryption")
    void passive_system_agent_does_not_block_encryption() throws Exception {
        BotConfig agent = botConfigRepository.findFirstByBotNameAndCreatedByIsNullOrderByIdAsc("Agent")
                .orElseGet(() -> {
                    BotConfig created = new BotConfig();
                    created.setBotName("Agent");
                    created.setLlmProvider(BotConfig.LLMProvider.HERMES);
                    return botConfigRepository.save(created);
                });
        User carol = registerClientHashUser("e2ee_carol_" + uniqueSuffix, "Carol");
        ChatRoom withAgent = chatRoomService.createPrivateChat(alice.getId(), carol.getId());
        ChatRoomBot binding = chatRoomBotRepository
                .findByChatRoomIdAndBotConfigId(withAgent.getId(), agent.getId())
                .orElseThrow(() -> new AssertionError("every new chat gets the system Agent"));

        mockMvc.perform(get("/api/v1/e2ee/rooms/" + withAgent.getId() + "/status")
                        .header("Authorization", aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligible").value(true));
        sendEncryptedRest(withAgent.getId(), randomBase64(100));

        // 把 Agent 改成"所有消息都触发"：它要读每条消息，这个会话就不能再加密。
        binding.setTriggerMode(ChatRoomBot.TriggerMode.ALL);
        chatRoomBotRepository.save(binding);
        mockMvc.perform(get("/api/v1/e2ee/rooms/" + withAgent.getId() + "/status")
                        .header("Authorization", aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligible").value(false))
                .andExpect(jsonPath("$.data.reason").value(E2eeKeyService.REASON_HAS_BOTS));
    }

    @Test
    @DisplayName("edits stay encrypted, forwards of ciphertext are refused, recall drops the ciphertext")
    void edit_forward_recall_of_encrypted_messages() throws Exception {
        String envelope = randomBase64(120);
        Long messageId = sendEncryptedRest(dm.getId(), envelope);

        // 老客户端拿占位文字改成明文：拒绝。
        mockMvc.perform(put("/api/v1/messages/" + messageId)
                        .header("Authorization", aliceBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "now in plaintext"))))
                .andExpect(status().isBadRequest());

        String edited = randomBase64(140);
        mockMvc.perform(put("/api/v1/messages/" + messageId)
                        .header("Authorization", aliceBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "ignored",
                                "encryptedContent", edited,
                                "encryptionVersion", E2eeKeyService.MESSAGE_ENCRYPTION_VERSION))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.encryptedContent").value(edited))
                .andExpect(jsonPath("$.data.content").value(E2eeKeyService.OLD_CLIENT_PLACEHOLDER));

        ChatRoom group = chatRoomService.createGroupChat(alice.getId(), "fwd-" + uniqueSuffix, "g",
                List.of(bob.getId()));
        mockMvc.perform(post("/api/v1/messages/" + messageId + "/forward")
                        .header("Authorization", aliceBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("targetChatRoomId", group.getId()))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/messages/" + messageId + "/recall")
                        .header("Authorization", aliceBearer))
                .andExpect(status().isOk());
        Message recalled = messageRepository.findById(messageId).orElseThrow();
        assertNull(recalled.getEncryptedContent(), "recall must not leave the ciphertext behind");
    }

    private Long sendEncryptedRest(Long roomId, String envelope) throws Exception {
        Map<String, Object> request = new HashMap<>(encryptedFrame(roomId, envelope));
        request.remove("type");
        String body = mockMvc.perform(post("/api/v1/messages")
                        .header("Authorization", aliceBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(E2eeKeyService.OLD_CLIENT_PLACEHOLDER))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    private Map<String, Object> encryptedFrame(Long roomId, String envelope) {
        return Map.of(
                "type", "message",
                "chatRoomId", roomId,
                "content", "plaintext that must never be stored",
                "messageType", "TEXT",
                "encryptedContent", envelope,
                "encryptionVersion", E2eeKeyService.MESSAGE_ENCRYPTION_VERSION);
    }

    private org.springframework.test.web.servlet.ResultActions createKey(
            String bearer, String publicKey, Integer expectedActive) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("publicKey", publicKey);
        request.put("wrappedPrivateKey", randomBase64(60));
        request.put("wrapSalt", randomBase64(16));
        request.put("wrapParams", ARGON2);
        request.put("expectedActiveKeyVersion", expectedActive);
        return mockMvc.perform(post("/api/v1/e2ee/keys")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private org.springframework.test.web.servlet.ResultActions setRecovery(
            String bearer, Integer expectedActive, List<Map<String, Object>> wraps) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("wraps", wraps);
        request.put("expectedActiveKeyVersion", expectedActive);
        return mockMvc.perform(put("/api/v1/e2ee/recovery")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private static Map<String, Object> recoveryWrap(int version, String wrapped, String salt) {
        return Map.of(
                "version", version,
                "wrappedPrivateKey", wrapped,
                "wrapSalt", salt,
                "wrapParams", E2eeKeyService.RECOVERY_WRAP_PARAMS);
    }

    private org.springframework.test.web.servlet.ResultActions rewrapWithPassword(
            String bearer, String currentClientHash, String wrapped) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("clientHash", currentClientHash);
        request.put("wraps", List.of(Map.of(
                "version", 1,
                "wrappedPrivateKey", wrapped,
                "wrapSalt", randomBase64(16),
                "wrapParams", ARGON2)));
        return mockMvc.perform(put("/api/v1/e2ee/keys/password-wraps")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private User registerClientHashUser(String username, String displayName) {
        UserDto.RegisterRequest request = new UserDto.RegisterRequest();
        request.setUsername(username);
        request.setEmail(username + "@test.com");
        request.setDisplayName(displayName);
        request.setClientHash(clientHash(displayName.toLowerCase() + "-old"));
        request.setClientSalt(randomBase64Url(16));
        request.setArgon2Params(ARGON2);
        UserDto dto = userService.registerUser(request);
        return userRepository.findById(dto.getId()).orElseThrow();
    }

    /** 测试里不用真的算 Argon2：服务器只把 clientHash 当作一段够长的口令再 bcrypt。 */
    private static String clientHash(String seed) {
        byte[] padded = new byte[32];
        byte[] raw = seed.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(raw, 0, padded, 0, Math.min(raw.length, padded.length));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(padded);
    }

    private RawWebSocketIntegrationTest.TestWebSocketSession connect(User user) {
        RawWebSocketIntegrationTest.TestWebSocketSession session = new RawWebSocketIntegrationTest.TestWebSocketSession();
        session.getAttributes().put(RawWebSocketHandler.ATTR_USER, user);
        rawWebSocketHandler.afterConnectionEstablished(session);
        openSessions.add(session);
        return session;
    }

    private void sendWs(RawWebSocketIntegrationTest.TestWebSocketSession session, Map<String, Object> frame)
            throws Exception {
        rawWebSocketHandler.handleMessage(session, new TextMessage(objectMapper.writeValueAsString(frame)));
    }

    private void drain(RawWebSocketIntegrationTest.TestWebSocketSession... sessions) {
        for (RawWebSocketIntegrationTest.TestWebSocketSession session : sessions) {
            session.messages().clear();
        }
    }

    private JsonNode await(RawWebSocketIntegrationTest.TestWebSocketSession session, String type) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            String msg = session.messages().poll(3000, TimeUnit.MILLISECONDS);
            if (msg == null) {
                return null;
            }
            JsonNode node = objectMapper.readTree(msg);
            if (type.equals(node.path("type").asText())) {
                return node;
            }
        }
        return null;
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String randomBase64(int length) {
        return Base64.getEncoder().encodeToString(randomBytes(length));
    }

    private static String randomBase64Url(int length) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(length));
    }
}
