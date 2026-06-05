package com.chatapp.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class FileVaultService {
    private static final Logger log = LoggerFactory.getLogger(FileVaultService.class);
    private static final String ALG = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int DK_BYTES = 32;
    private static final String META_SUFFIX = ".meta.json";
    private static final String CIPHER_SUFFIX = ".enc";
    private static final int META_VERSION = 1;

    private final SecretKeySpec masterKey;
    private final long streamingThreshold;
    private final SecureRandom rng = new SecureRandom();
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean prodProfile;

    public FileVaultService(
            @Value("${file.storage.master-key:}") String masterRaw,
            @Value("${file.storage.streaming-threshold-bytes:1048576}") long streamingThreshold,
            @Value("${spring.profiles.active:default}") String profile) {
        this.prodProfile = "prod".equals(profile);
        if (masterRaw == null || masterRaw.isBlank()) {
            if (prodProfile) {
                throw new IllegalStateException(
                        "FILE_STORAGE_MASTER_KEY must be set under prod profile; "
                                + "fallback to JWT_SECRET / PROVIDER_VAULT_MASTER_KEY is forbidden");
            }
            log.warn("file.storage.master-key blank under non-prod profile; using insecure dev key");
            masterRaw = "DEV-INSECURE-FILE-STORAGE-KEY-DO-NOT-USE-IN-PROD";
        }
        this.masterKey = new SecretKeySpec(sha256(masterRaw), "AES");
        this.streamingThreshold = streamingThreshold;
    }

    @PostConstruct
    void logReady() {
        log.info("FileVaultService ready; streamingThreshold={} prodProfile={}", streamingThreshold, prodProfile);
    }

    public long getStreamingThreshold() {
        return streamingThreshold;
    }

    public void storeEncrypted(Path basePath, byte[] plaintext) throws IOException {
        EncryptedPayload payload = encryptObject(plaintext);
        writeEncryptedFiles(basePath, payload.ciphertext(), readMeta(payload.metaJson()));
    }

    public void storeEncryptedStream(Path basePath, InputStream plaintextStream, long expectedSize)
            throws IOException {
        ensureNotEncrypted(basePath);
        Files.createDirectories(basePath.getParent());

        byte[] dk = randomBytes(DK_BYTES);
        byte[] iv = randomBytes(IV_BYTES);
        byte[] wrapIv = randomBytes(IV_BYTES);
        byte[] wrappedDk = crypt(Cipher.ENCRYPT_MODE, masterKey, wrapIv, dk);
        VaultMeta meta = VaultMeta.create(iv, wrapIv, wrappedDk, expectedSize);

        Path cipherPath = cipherPath(basePath);
        Path metaPath = metaPath(basePath);
        Path cipherTmp = tmpSibling(cipherPath);
        Path metaTmp = tmpSibling(metaPath);
        try {
            Cipher cipher = initCipher(Cipher.ENCRYPT_MODE, new SecretKeySpec(dk, "AES"), iv);
            try (InputStream in = plaintextStream;
                 CipherOutputStream out = new CipherOutputStream(Files.newOutputStream(
                         cipherTmp, StandardOpenOption.CREATE_NEW), cipher)) {
                in.transferTo(out);
            }
            mapper.writeValue(metaTmp.toFile(), meta);
            Files.move(cipherTmp, cipherPath, StandardCopyOption.ATOMIC_MOVE);
            Files.move(metaTmp, metaPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            cleanupTmp(cipherTmp, metaTmp);
            throw asIOException("文件加密写入失败", e);
        }
    }

    public byte[] loadDecrypted(Path basePath) throws IOException {
        try (InputStream in = loadDecryptedStream(basePath)) {
            return in.readAllBytes();
        }
    }

    public EncryptedPayload encryptObject(byte[] plaintext) throws IOException {
        byte[] safePlaintext = plaintext == null ? new byte[0] : plaintext;
        byte[] dk = randomBytes(DK_BYTES);
        byte[] iv = randomBytes(IV_BYTES);
        byte[] wrapIv = randomBytes(IV_BYTES);
        byte[] cipherText = crypt(Cipher.ENCRYPT_MODE, new SecretKeySpec(dk, "AES"), iv, safePlaintext);
        byte[] wrappedDk = crypt(Cipher.ENCRYPT_MODE, masterKey, wrapIv, dk);
        VaultMeta meta = VaultMeta.create(iv, wrapIv, wrappedDk, safePlaintext.length);
        return new EncryptedPayload(cipherText, mapper.writeValueAsBytes(meta));
    }

    public byte[] decryptObject(byte[] ciphertext, byte[] metaJson) throws IOException {
        if (ciphertext == null || metaJson == null || metaJson.length == 0) {
            throw new IOException("加密对象缺少密文或元数据");
        }
        try {
            VaultMeta meta = readMeta(metaJson);
            byte[] dk = crypt(
                    Cipher.DECRYPT_MODE,
                    masterKey,
                    decode(meta.wrapIvB64()),
                    decode(meta.wrappedDkB64()));
            return crypt(Cipher.DECRYPT_MODE, new SecretKeySpec(dk, "AES"), decode(meta.ivB64()), ciphertext);
        } catch (Exception e) {
            throw asIOException("对象解密失败", e);
        }
    }

    public InputStream loadDecryptedStream(Path basePath) throws IOException {
        Path cipherPath = cipherPath(basePath);
        Path metaPath = metaPath(basePath);
        if (!Files.exists(cipherPath) || !Files.exists(metaPath)) {
            throw new IOException("加密文件缺少密文或元数据: " + basePath.getFileName());
        }
        try {
            VaultMeta meta = mapper.readValue(metaPath.toFile(), VaultMeta.class);
            byte[] dk = crypt(
                    Cipher.DECRYPT_MODE,
                    masterKey,
                    decode(meta.wrapIvB64()),
                    decode(meta.wrappedDkB64()));
            Cipher cipher = initCipher(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(dk, "AES"),
                    decode(meta.ivB64()));
            return new CipherInputStream(Files.newInputStream(cipherPath), cipher);
        } catch (Exception e) {
            throw asIOException("文件解密失败", e);
        }
    }

    public boolean deleteEncrypted(Path basePath) throws IOException {
        boolean deletedCipher = Files.deleteIfExists(cipherPath(basePath));
        boolean deletedMeta = Files.deleteIfExists(metaPath(basePath));
        return deletedCipher || deletedMeta;
    }

    public boolean isEncrypted(Path basePath) {
        return Files.exists(cipherPath(basePath)) && Files.exists(metaPath(basePath));
    }

    public Path cipherPath(Path basePath) {
        return basePath.resolveSibling(basePath.getFileName() + CIPHER_SUFFIX);
    }

    public Path metaPath(Path basePath) {
        return basePath.resolveSibling(basePath.getFileName() + META_SUFFIX);
    }

    private void writeEncryptedFiles(Path basePath, byte[] cipherText, VaultMeta meta) throws IOException {
        ensureNotEncrypted(basePath);
        Files.createDirectories(basePath.getParent());
        Path cipherPath = cipherPath(basePath);
        Path metaPath = metaPath(basePath);
        Path cipherTmp = tmpSibling(cipherPath);
        Path metaTmp = tmpSibling(metaPath);
        try {
            Files.write(cipherTmp, cipherText, StandardOpenOption.CREATE_NEW);
            mapper.writeValue(metaTmp.toFile(), meta);
            Files.move(cipherTmp, cipherPath, StandardCopyOption.ATOMIC_MOVE);
            Files.move(metaTmp, metaPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            cleanupTmp(cipherTmp, metaTmp);
            throw asIOException("文件加密写入失败", e);
        }
    }

    private VaultMeta readMeta(byte[] metaJson) throws IOException {
        return mapper.readValue(metaJson, VaultMeta.class);
    }

    private void ensureNotEncrypted(Path basePath) throws IOException {
        if (Files.exists(cipherPath(basePath)) || Files.exists(metaPath(basePath))) {
            throw new IOException("加密文件已存在: " + basePath.getFileName());
        }
    }

    private byte[] crypt(int mode, SecretKeySpec key, byte[] iv, byte[] input) throws IOException {
        try {
            Cipher cipher = initCipher(mode, key, iv);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IOException("AES-GCM 操作失败", e);
        }
    }

    private Cipher initCipher(int mode, SecretKeySpec key, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(ALG);
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        return cipher;
    }

    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        rng.nextBytes(bytes);
        return bytes;
    }

    private byte[] sha256(String raw) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private byte[] decode(String value) {
        return Base64.getDecoder().decode(value);
    }

    private Path tmpSibling(Path path) {
        return path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
    }

    private void cleanupTmp(Path... paths) {
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
        }
    }

    private IOException asIOException(String message, Exception e) {
        return e instanceof IOException io ? io : new IOException(message, e);
    }

    public record VaultMeta(
            int version,
            String alg,
            @JsonProperty("iv_b64") String ivB64,
            @JsonProperty("wrap_iv_b64") String wrapIvB64,
            @JsonProperty("wrapped_dk_b64") String wrappedDkB64,
            @JsonProperty("size_plain") long sizePlain,
            @JsonProperty("created_at") String createdAt) {
        static VaultMeta create(byte[] iv, byte[] wrapIv, byte[] wrappedDk, long sizePlain) {
            Base64.Encoder encoder = Base64.getEncoder();
            return new VaultMeta(
                    META_VERSION,
                    ALG,
                    encoder.encodeToString(iv),
                    encoder.encodeToString(wrapIv),
                    encoder.encodeToString(wrappedDk),
                    sizePlain,
                    Instant.now().toString());
        }
    }

    public record EncryptedPayload(byte[] ciphertext, byte[] metaJson) {
    }
}
