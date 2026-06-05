package com.chatapp.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileVaultServiceTest {
    @TempDir
    Path tempDir;

    private FileVaultService service() {
        return new FileVaultService("test-master-key-not-secret", 1024 * 1024, "test");
    }

    @Test
    void smallRoundTripStoresCiphertextAndMeta() throws Exception {
        FileVaultService vault = service();
        Path base = tempDir.resolve("avatar.png");
        byte[] plain = "hello-secret-image".getBytes();

        vault.storeEncrypted(base, plain);

        assertThat(vault.isEncrypted(base)).isTrue();
        assertThat(Files.exists(base)).isFalse();
        assertThat(Files.readAllBytes(vault.cipherPath(base))).isNotEqualTo(plain);
        assertThat(vault.loadDecrypted(base)).isEqualTo(plain);
    }

    @Test
    void streamingRoundTripSupportsLargePayload() throws Exception {
        FileVaultService vault = service();
        Path base = tempDir.resolve("large.bin");
        byte[] chunk = "pmchat-large-streaming-payload".repeat(8192).getBytes();
        int repeats = 256;
        String expectedHash = hash(new RepeatingInputStream(chunk, repeats));

        vault.storeEncryptedStream(base, new RepeatingInputStream(chunk, repeats), (long) chunk.length * repeats);

        assertThat(Files.size(vault.cipherPath(base))).isGreaterThan((long) chunk.length * repeats);
        assertThat(hash(vault.loadDecryptedStream(base))).isEqualTo(expectedHash);
    }

    @Test
    void tamperedCiphertextFailsAuthentication() throws Exception {
        FileVaultService vault = service();
        Path base = tempDir.resolve("tamper.txt");
        vault.storeEncrypted(base, "cannot forge me".getBytes());

        byte[] cipher = Files.readAllBytes(vault.cipherPath(base));
        cipher[Math.max(0, cipher.length - 3)] ^= 0x7f;
        Files.write(vault.cipherPath(base), cipher);

        assertThatThrownBy(() -> vault.loadDecrypted(base))
                .isInstanceOf(IOException.class);
    }

    @Test
    void missingMetadataFailsClosed() throws Exception {
        FileVaultService vault = service();
        Path base = tempDir.resolve("missing.txt");
        vault.storeEncrypted(base, "data".getBytes());
        Files.delete(vault.metaPath(base));

        assertThatThrownBy(() -> vault.loadDecrypted(base))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("缺少");
    }

    @Test
    void encryptedFilesAreNotOverwrittenAccidentally() throws Exception {
        FileVaultService vault = service();
        Path base = tempDir.resolve("once.txt");
        vault.storeEncrypted(base, "first".getBytes());

        assertThatThrownBy(() -> vault.storeEncrypted(base, "second".getBytes()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void deleteEncryptedRemovesCipherAndMeta() throws Exception {
        FileVaultService vault = service();
        Path base = tempDir.resolve("delete.txt");
        vault.storeEncrypted(base, "delete me".getBytes());

        assertThat(vault.deleteEncrypted(base)).isTrue();
        assertThat(vault.isEncrypted(base)).isFalse();
        assertThat(Files.exists(vault.cipherPath(base))).isFalse();
        assertThat(Files.exists(vault.metaPath(base))).isFalse();
    }

    private String hash(InputStream in) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = in) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static class RepeatingInputStream extends InputStream {
        private final byte[] chunk;
        private final int repeats;
        private int repeatIndex;
        private int chunkIndex;

        private RepeatingInputStream(byte[] chunk, int repeats) {
            this.chunk = chunk;
            this.repeats = repeats;
        }

        @Override
        public int read() {
            if (repeatIndex >= repeats) {
                return -1;
            }
            int value = chunk[chunkIndex++] & 0xff;
            if (chunkIndex == chunk.length) {
                chunkIndex = 0;
                repeatIndex++;
            }
            return value;
        }
    }
}
