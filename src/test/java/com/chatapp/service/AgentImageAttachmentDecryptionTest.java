package com.chatapp.service;

import com.chatapp.config.FileStorageConfig;
import com.chatapp.dto.BotDto;
import com.chatapp.entity.Message;
import com.chatapp.security.FileVaultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class AgentImageAttachmentDecryptionTest {
    @TempDir Path tempDir;

    private FileStorageService storageService;
    private FileStorageConfig config;
    private FileVaultService vault;
    private AgentVisionAttachmentService visionService;

    @BeforeEach
    void setUp() throws Exception {
        config = new FileStorageConfig();
        config.setUploadDir(tempDir.toString());
        vault = new FileVaultService("agent-vision-test-key", 1024, "test");
        storageService = new FileStorageService();
        ReflectionTestUtils.setField(storageService, "fileStorageConfig", config);
        ReflectionTestUtils.setField(storageService, "fileVaultService", vault);
        storageService.init();
        visionService = new AgentVisionAttachmentService(storageService);
    }

    @Test
    void fileVaultEncryptedChatImageDecodesToOriginalBase64() throws Exception {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 1, 2, 3};
        MockMultipartFile file = new MockMultipartFile("file", "plain.png", "image/png", png);
        String url = storageService.uploadChatFile(file);
        String storedName = url.substring("/api/files/chat/".length());
        Path base = Path.of(config.getFullChatFileDir()).resolve(storedName);
        assertThat(Files.exists(base)).isFalse();
        assertThat(Files.readAllBytes(vault.cipherPath(base))).isNotEqualTo(png);

        Message message = new Message();
        message.setMessageType(Message.MessageType.IMAGE);
        message.setFileUrl(url);
        message.setFileName("plain.png");
        message.setFileType("image/png");

        AgentVisionAttachmentService.ImageContext context = visionService.resolve(message, true);

        assertThat(context.damaged()).isFalse();
        assertThat(context.attachments()).hasSize(1);
        BotDto.ImageAttachment attachment = context.attachments().get(0);
        assertThat(attachment.mediaType()).isEqualTo("image/png");
        String encoded = attachment.dataUrl().substring("data:image/png;base64,".length());
        assertThat(Base64.getDecoder().decode(encoded)).isEqualTo(png);
    }

    @Test
    void missingOrTamperedImageFallsBackToTextAnnotation() {
        Message message = new Message();
        message.setId(55L);
        message.setMessageType(Message.MessageType.IMAGE);
        message.setFileUrl("/api/files/chat/missing.png");
        message.setFileName("missing.png");
        message.setFileType("image/png");

        AgentVisionAttachmentService.ImageContext context = visionService.resolve(message, true);

        assertThat(context.damaged()).isTrue();
        assertThat(context.attachments()).isEmpty();
        assertThat(context.annotation()).contains("图片读取失败");
    }
}
