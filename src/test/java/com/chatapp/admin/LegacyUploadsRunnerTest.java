package com.chatapp.admin;

import com.chatapp.config.FileStorageConfig;
import com.chatapp.entity.WorkspaceFile;
import com.chatapp.entity.WorkspaceFileVersion;
import com.chatapp.repository.WorkspaceFileRepository;
import com.chatapp.repository.WorkspaceFileVersionRepository;
import com.chatapp.security.FileVaultService;
import com.chatapp.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyUploadsRunnerTest {
    @TempDir
    Path tempDir;

    private FileStorageConfig config;
    private FileVaultService vault;
    private FileStorageService storageService;
    private WorkspaceFileRepository fileRepository;
    private WorkspaceFileVersionRepository versionRepository;

    @BeforeEach
    void setUp() {
        config = new FileStorageConfig();
        config.setUploadDir(tempDir.toString());
        config.setLegacyEncryptExcludeDirs("app-releases");
        vault = new FileVaultService("legacy-runner-test-key", 1024, "test");
        storageService = mock(FileStorageService.class);
        fileRepository = mock(WorkspaceFileRepository.class);
        versionRepository = mock(WorkspaceFileVersionRepository.class);
        when(fileRepository.findObjectStoredFiles()).thenReturn(List.of());
        when(versionRepository.findObjectStoredVersions()).thenReturn(List.of());
    }

    @Test
    void encryptRunnerMigratesInScopeFilesAndLeavesAppReleasesPlaintext() throws Exception {
        config.setEncryptLegacyOnStartup(true);
        Path avatar = write("avatars/a.png", "avatar-bytes");
        Path chat = write("chat-files/c.txt", "chat-bytes");
        Path release = write("app-releases/pmchat.apk", "PK\u0003\u0004apk-bytes");

        runner().run(new DefaultApplicationArguments());

        assertEncrypted(avatar, "avatar-bytes");
        assertEncrypted(chat, "chat-bytes");
        assertThat(Files.exists(release)).isTrue();
        assertThat(Files.readString(release)).startsWith("PK\u0003\u0004");
        assertThat(Files.exists(release.resolveSibling("pmchat.apk.enc"))).isFalse();
        verify(storageService, never()).encryptLegacyWorkspaceObjectIfPlaintext("MINIO", "workspace-files/none.txt");
    }

    @Test
    void encryptRunnerMigratesLegacyWorkspaceObjectsFromFilesAndVersions() throws Exception {
        config.setEncryptLegacyOnStartup(true);
        WorkspaceFile file = new WorkspaceFile();
        file.setId(10L);
        file.setStorageProvider("MINIO");
        file.setObjectKey("workspace-files/current.txt");
        WorkspaceFileVersion version = new WorkspaceFileVersion();
        version.setId(20L);
        version.setStorageProvider("MINIO");
        version.setObjectKey("workspace-files/v1.txt");
        when(fileRepository.findObjectStoredFiles()).thenReturn(List.of(file));
        when(versionRepository.findObjectStoredVersions()).thenReturn(List.of(version));

        runner().run(new DefaultApplicationArguments());

        verify(storageService).encryptLegacyWorkspaceObjectIfPlaintext("MINIO", "workspace-files/current.txt");
        verify(storageService).encryptLegacyWorkspaceObjectIfPlaintext("MINIO", "workspace-files/v1.txt");
    }

    @Test
    void decryptRunnerRestoresEncryptedFilesAndStillSkipsAppReleases() throws Exception {
        Path avatar = write("avatars/a.png", "avatar-bytes");
        Path release = write("app-releases/pmchat.apk", "PK\u0003\u0004apk-bytes");
        vault.storeEncrypted(avatar, Files.readAllBytes(avatar));
        Files.delete(avatar);
        config.setDecryptLegacyOnStartup(true);

        new DecryptLegacyUploadsRunner(config, vault)
                .run(new DefaultApplicationArguments());

        assertThat(Files.readString(avatar)).isEqualTo("avatar-bytes");
        assertThat(Files.exists(vault.cipherPath(avatar))).isFalse();
        assertThat(Files.readString(release)).startsWith("PK\u0003\u0004");
        assertThat(Files.exists(release.resolveSibling("pmchat.apk.enc"))).isFalse();
    }

    private Path write(String relative, String value) throws Exception {
        Path path = tempDir.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
        return path;
    }

    private void assertEncrypted(Path base, String expected) throws Exception {
        assertThat(Files.exists(base)).isFalse();
        assertThat(Files.exists(vault.cipherPath(base))).isTrue();
        assertThat(Files.readAllBytes(vault.cipherPath(base))).isNotEqualTo(expected.getBytes());
        assertThat(vault.loadDecrypted(base)).isEqualTo(expected.getBytes());
    }

    private EncryptLegacyUploadsRunner runner() {
        return new EncryptLegacyUploadsRunner(
                config,
                vault,
                storageService,
                fileRepository,
                versionRepository);
    }
}
