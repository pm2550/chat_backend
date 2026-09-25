package com.chatapp.integration;

import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.AgentGatewayService;
import com.chatapp.service.CloudStorageService;
import com.chatapp.service.FileStorageService;
import com.chatapp.service.LLMService;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.SelfDestructService;
import com.chatapp.service.ThumbnailBackfillService;
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
import org.springframework.test.web.servlet.ResultActions;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 图片消息的小预览图：服务器生成、和原图同样受保护（成员能看、陌生人 403、转发副本的收件人能看），
 * 老客户端照旧拿 fileUrl 加载原图；历史图片可以回填。
 */
@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
    "spring.main.allow-circular-references=true",
    "spring.main.allow-bean-definition-overriding=true",
    "server.servlet.context-path=",
    "spring.jpa.open-in-view=false",
    "file.storage.upload-dir=target/test-uploads/image-thumbnail-integration"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChatImageThumbnailIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MessageRepository messageRepository;
    @Autowired private ChatRoomRepository chatRoomRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private FileStorageService fileStorageService;
    @Autowired private ThumbnailBackfillService thumbnailBackfillService;

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
    @DisplayName("上传大图：带缩略图；成员能看、陌生人 403、转发目标房间成员能看；原图地址不变（老客户端照旧）")
    void uploadedPhotoGetsProtectedThumbnail() throws Exception {
        TestUser alice = createUser("th_alice");
        TestUser bob = createUser("th_bob");
        TestUser carol = createUser("th_carol");
        TestUser dave = createUser("th_dave");
        Long sourceRoom = createGroupChat(alice.token, "缩略图源 " + uniqueSuffix, List.of(bob.id));
        Long targetRoom = createGroupChat(alice.token, "缩略图目标 " + uniqueSuffix, List.of(carol.id));

        byte[] photo = noisyJpeg(2400, 1600);
        Map<String, Object> sent = sendFileMessage(alice.token, sourceRoom,
                new MockMultipartFile("file", "IMG_0001.jpg", "image/jpeg", photo));

        assertThat(sent.get("messageType")).isEqualTo("IMAGE");
        String fileUrl = (String) sent.get("fileUrl");
        String thumbnailUrl = (String) sent.get("thumbnailUrl");
        assertThat(thumbnailUrl).startsWith("/api/files/chat/").endsWith(".jpg").isNotEqualTo(fileUrl);
        assertThat(sent.get("width")).isEqualTo(2400);
        assertThat(sent.get("height")).isEqualTo(1600);

        byte[] thumbnail = getFile(bob.token, thumbnailUrl)
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumbnail));
        assertThat(Math.max(decoded.getWidth(), decoded.getHeight())).isEqualTo(400);
        assertThat(thumbnail.length).isLessThan(photo.length / 10);

        // 老客户端（1.1.50）不认 thumbnailUrl，照旧按 fileUrl 取原图：原图原样不动。
        getFile(bob.token, fileUrl).andExpect(status().isOk()).andExpect(content().bytes(photo));

        getFile(dave.token, thumbnailUrl).andExpect(status().isForbidden());
        getFile(carol.token, thumbnailUrl).andExpect(status().isForbidden());

        Long sourceId = ((Number) sent.get("id")).longValue();
        Long forwardedId = forward(alice.token, sourceId, targetRoom);
        assertThat(messageRepository.findById(forwardedId).orElseThrow().getThumbnailUrl()).isEqualTo(thumbnailUrl);
        getFile(carol.token, thumbnailUrl).andExpect(status().isOk());
        getFile(dave.token, thumbnailUrl).andExpect(status().isForbidden());

        // 原消息删了：只在源房间的 bob 失去访问权，转发副本仍让 carol 能看。
        mockMvc.perform(delete("/api/v1/messages/" + sourceId).header("Authorization", "Bearer " + alice.token))
                .andExpect(status().isOk());
        getFile(bob.token, thumbnailUrl).andExpect(status().isForbidden());
        getFile(carol.token, thumbnailUrl).andExpect(status().isOk());
    }

    @Test
    @DisplayName("小图和非图片不生成缩略图")
    void smallImagesAndDocumentsHaveNoThumbnail() throws Exception {
        TestUser alice = createUser("th_small");
        Long room = createGroupChat(alice.token, "小图 " + uniqueSuffix, List.of());

        Map<String, Object> small = sendFileMessage(alice.token, room,
                new MockMultipartFile("file", "icon.jpg", "image/jpeg", noisyJpeg(120, 80)));
        assertThat(small.get("thumbnailUrl")).isNull();

        Map<String, Object> doc = sendFileMessage(alice.token, room,
                new MockMultipartFile("file", "report.pdf", "application/pdf", new byte[200 * 1024]));
        assertThat(doc.get("thumbnailUrl")).isNull();
    }

    @Test
    @DisplayName("回填：历史大图补上缩略图（转发副本共用），再跑一次什么都不做")
    void backfillIsIdempotentAndCoversForwardedCopies() throws Exception {
        TestUser alice = createUser("th_backfill");
        Long roomId = createGroupChat(alice.token, "回填 " + uniqueSuffix, List.of());
        User sender = userRepository.findById(alice.id).orElseThrow();
        ChatRoom room = chatRoomRepository.findById(roomId).orElseThrow();
        byte[] photo = noisyJpeg(2000, 1500);
        String fileUrl = fileStorageService.uploadChatFileBytes("old.jpg", "image/jpeg", photo);
        Message original = legacyImage(sender, room, fileUrl, photo.length);
        Message copy = legacyImage(sender, room, fileUrl, photo.length);

        ThumbnailBackfillService.Result first = thumbnailBackfillService.backfill(500);

        assertThat(first.created()).isGreaterThanOrEqualTo(1);
        String thumbnailUrl = messageRepository.findById(original.getId()).orElseThrow().getThumbnailUrl();
        assertThat(thumbnailUrl).startsWith("/api/files/chat/");
        assertThat(messageRepository.findById(copy.getId()).orElseThrow().getThumbnailUrl()).isEqualTo(thumbnailUrl);
        getFile(alice.token, thumbnailUrl).andExpect(status().isOk());

        ThumbnailBackfillService.Result second = thumbnailBackfillService.backfill(500);
        assertThat(second.created()).isZero();
        assertThat(messageRepository.findById(original.getId()).orElseThrow().getThumbnailUrl())
                .isEqualTo(thumbnailUrl);
    }

    private Message legacyImage(User sender, ChatRoom room, String fileUrl, long size) {
        Message message = new Message();
        message.setSender(sender);
        message.setChatRoom(room);
        message.setMessageType(Message.MessageType.IMAGE);
        message.setMessageStatus(Message.MessageStatus.SENT);
        message.setContent("old.jpg");
        message.setFileName("old.jpg");
        message.setFileType("image/jpeg");
        message.setFileUrl(fileUrl);
        message.setFileSize(size);
        message.setCreatedAt(LocalDateTime.now());
        return messageRepository.save(message);
    }

    static byte[] noisyJpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(11);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int base = x * 255 / width;
                image.setRGB(x, y, (clamp(base + random.nextInt(50) - 25) << 16)
                        | (clamp(120 + random.nextInt(50) - 25) << 8)
                        | clamp(255 - base + random.nextInt(50) - 25));
            }
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(buffer)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.92f);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return buffer.toByteArray();
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private ResultActions getFile(String token, String fileUrl) throws Exception {
        return mockMvc.perform(get(fileUrl).header("Authorization", "Bearer " + token));
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
