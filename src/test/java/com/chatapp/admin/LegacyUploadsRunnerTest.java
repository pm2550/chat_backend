package com.chatapp.admin;

import com.chatapp.config.FileStorageConfig;
import com.chatapp.security.FileVaultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyUploadsRunnerTest {
    @TempDir
    Path tempDir;

    private FileStorageConfig config;
    private FileVaultService vault;

    @BeforeEach
    void setUp() {
        config = new FileStorageConfig();
        config.setUploadDir(tempDir.toString());
        config.setLegacyEncryptExcludeDirs("app-releases");
        vault = new FileVaultService("legacy-runner-test-key", 1024, "test");
    }

    @Test
    void encryptRunnerMigratesInScopeFilesAndLeavesAppReleasesPlaintext() throws Exception {
        config.setEncryptLegacyOnStartup(true);
        Path avatar = write("avatars/a.png", "avatar-bytes");
        Path chat = write("chat-files/c.txt", "chat-bytes");
        Path release = write("app-releases/pmchat.apk", "PK\u0003\u0004apk-bytes");

        new EncryptLegacyUploadsRunner(config, vault)
                .run(new DefaultApplicationArguments());

        assertEncrypted(avatar, "avatar-bytes");
        assertEncrypted(chat, "chat-bytes");
        assertThat(Files.exists(release)).isTrue();
        assertThat(Files.readString(release)).startsWith("PK\u0003\u0004");
        assertThat(Files.exists(release.resolveSibling("pmchat.apk.enc"))).isFalse();
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
}
