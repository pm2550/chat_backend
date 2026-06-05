package com.chatapp.service;

import com.chatapp.config.FileStorageConfig;
import com.chatapp.security.FileVaultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileStorageServiceEncryptionTest {
    @TempDir
    Path tempDir;

    private FileStorageService service;
    private FileStorageConfig config;
    private FileVaultService vault;

    @BeforeEach
    void setUp() throws Exception {
        config = new FileStorageConfig();
        config.setUploadDir(tempDir.toString());
        vault = new FileVaultService("storage-service-test-key", 1024, "test");
        service = new FileStorageService();
        ReflectionTestUtils.setField(service, "fileStorageConfig", config);
        ReflectionTestUtils.setField(service, "fileVaultService", vault);
        service.init();
    }

    @Test
    void uploadAvatarStoresCiphertextButApiReturnsPlaintext() throws Exception {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
        MockMultipartFile file = new MockMultipartFile("avatar", "me.png", "image/png", png);

        String url = service.uploadAvatar(file);
        String fileName = url.substring("/api/files/avatar/".length());
        Path base = Path.of(config.getFullAvatarDir()).resolve(fileName);

        assertThat(Files.exists(base)).isFalse();
        assertThat(Files.exists(vault.cipherPath(base))).isTrue();
        assertThat(Files.readAllBytes(vault.cipherPath(base))).isNotEqualTo(png);
        assertThat(service.getFile("avatar", fileName)).isEqualTo(png);
    }

    @Test
    void deleteFileRemovesEncryptedSidecars() throws Exception {
        MockMultipartFile file = new MockMultipartFile("avatar", "me.png", "image/png", new byte[] {1, 2, 3});
        String url = service.uploadAvatar(file);
        String fileName = url.substring("/api/files/avatar/".length());
        Path base = Path.of(config.getFullAvatarDir()).resolve(fileName);

        assertThat(service.deleteFile(url)).isTrue();
        assertThat(Files.exists(vault.cipherPath(base))).isFalse();
        assertThat(Files.exists(vault.metaPath(base))).isFalse();
    }

    @Test
    void legacyPlaintextStillReadsBeforeMigration() throws Exception {
        Path base = Path.of(config.getFullAvatarDir()).resolve("legacy.png");
        byte[] plain = new byte[] {9, 8, 7};
        Files.write(base, plain);

        assertThat(service.getFile("avatar", "legacy.png")).isEqualTo(plain);
    }
}
