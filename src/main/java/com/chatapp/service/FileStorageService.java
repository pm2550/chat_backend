package com.chatapp.service;

import com.chatapp.config.FileStorageConfig;
import com.chatapp.security.FileVaultService;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 文件存储服务
 */
@Service
public class FileStorageService {
    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    @Autowired
    private FileStorageConfig fileStorageConfig;

    @Autowired
    private FileVaultService fileVaultService;

    private volatile MinioClient workspaceObjectClient;

    /**
     * 初始化存储目录
     */
    public void init() {
        try {
            Files.createDirectories(Paths.get(fileStorageConfig.getFullUploadDir()));
            Files.createDirectories(Paths.get(fileStorageConfig.getFullAvatarDir()));
            Files.createDirectories(Paths.get(fileStorageConfig.getFullChatFileDir()));
            Files.createDirectories(Paths.get(fileStorageConfig.getFullImageGenDir()));
            Files.createDirectories(Paths.get(fileStorageConfig.getFullBackgroundDir()));
            if (isLocalWorkspaceStorage()) {
                Files.createDirectories(Paths.get(fileStorageConfig.getFullWorkspaceFileDir()));
            } else {
                ensureWorkspaceBucket();
            }
        } catch (IOException e) {
            throw new RuntimeException("无法创建文件存储目录", e);
        } catch (Exception e) {
            throw new RuntimeException("无法初始化工作区对象存储", e);
        }
    }

    /**
     * 上传头像
     */
    public String uploadAvatar(MultipartFile file) throws IOException {
        // 验证文件
        validateAvatarFile(file);
        
        // 生成唯一文件名
        String originalFilename = StringUtils.cleanPath(file.getOriginalFilename());
        String fileExtension = getFileExtension(originalFilename);
        String fileName = UUID.randomUUID().toString() + "." + fileExtension;
        
        Path targetLocation = Paths.get(fileStorageConfig.getFullAvatarDir()).resolve(fileName);
        storeEncryptedFile(targetLocation, file);
        
        // 返回访问URL
        return "/api/files/avatar/" + fileName;
    }

    /**
     * 上传聊天文件
     */
    public String uploadChatFile(MultipartFile file) throws IOException {
        // 验证文件
        validateChatFile(file);
        
        // 生成唯一文件名
        String originalFilename = StringUtils.cleanPath(file.getOriginalFilename());
        String fileExtension = getFileExtension(originalFilename);
        String fileName = UUID.randomUUID().toString() + "." + fileExtension;
        
        Path targetLocation = Paths.get(fileStorageConfig.getFullChatFileDir()).resolve(fileName);
        storeEncryptedFile(targetLocation, file);
        
        // 返回访问URL
        return "/api/files/chat/" + fileName;
    }

    /**
     * 上传聊天背景。背景是视觉皮肤资源，和聊天附件 ACL 分开存储。
     */
    public String uploadChatBackground(MultipartFile file) throws IOException {
        validateBackgroundFile(file);

        String originalFilename = StringUtils.cleanPath(file.getOriginalFilename());
        String fileExtension = getFileExtension(originalFilename);
        String fileName = UUID.randomUUID().toString() + "." + fileExtension;

        Path targetLocation = Paths.get(fileStorageConfig.getFullBackgroundDir()).resolve(fileName);
        storeEncryptedFile(targetLocation, file);

        return "/api/files/background/" + fileName;
    }

    public String uploadStickerFile(String originalFilename, String contentType, byte[] bytes) throws IOException {
        String safeName = cleanFileName(originalFilename, "sticker.png");
        validateImageFile(safeName, contentType, bytes == null ? 0 : bytes.length);
        String fileExtension = getFileExtension(safeName);
        String fileName = UUID.randomUUID().toString() + "." + (fileExtension.isBlank() ? "png" : fileExtension);
        Path targetLocation = Paths.get(fileStorageConfig.getFullChatFileDir()).resolve(fileName);
        storeEncryptedBytes(targetLocation, bytes == null ? new byte[0] : bytes);
        return "/api/files/chat/" + fileName;
    }

    public String uploadGeneratedImage(String originalFilename, String contentType, byte[] bytes) throws IOException {
        String safeName = cleanFileName(originalFilename, "image.png");
        validateImageFile(safeName, contentType, bytes == null ? 0 : bytes.length);
        String fileExtension = getFileExtension(safeName);
        String fileName = UUID.randomUUID() + "." + (fileExtension.isBlank() ? "png" : fileExtension);
        Path targetLocation = Paths.get(fileStorageConfig.getFullImageGenDir()).resolve(fileName);
        storeEncryptedBytes(targetLocation, bytes == null ? new byte[0] : bytes);
        return "/api/files/image-gen/" + fileName;
    }

    /**
     * 上传工作区文件，返回内部存储文件名。
     */
    public StoredFile uploadWorkspaceFile(MultipartFile file) throws IOException {
        validateChatFile(file);
        String originalFilename = cleanFileName(file.getOriginalFilename(), "file.bin");
        return uploadWorkspaceFile(originalFilename, file.getContentType(), file.getBytes());
    }

    /**
     * 保存服务/Bot/Agent 生成的工作区文件。
     */
    public StoredFile uploadWorkspaceFile(String originalFilename, String contentType, byte[] bytes) throws IOException {
        byte[] safeBytes = bytes == null ? new byte[0] : bytes;
        validateChatFile(originalFilename, safeBytes.length);

        String cleanedName = cleanFileName(originalFilename, "file.bin");
        String fileExtension = getFileExtension(cleanedName);
        String fileName = UUID.randomUUID().toString() + (fileExtension.isBlank() ? "" : "." + fileExtension);

        if (!isLocalWorkspaceStorage()) {
            String objectKey = workspaceObjectKey(fileName);
            FileVaultService.EncryptedPayload payload = fileVaultService.encryptObject(safeBytes);
            try {
                putWorkspaceObject(objectKey, payload.ciphertext(), "application/octet-stream");
                putWorkspaceObject(workspaceMetaObjectKey(objectKey), payload.metaJson(), "application/json");
            } catch (Exception e) {
                try {
                    removeWorkspaceObjectIfExists(objectKey);
                } catch (IOException cleanupError) {
                    log.warn("工作区对象存储密文清理失败: {}", objectKey, cleanupError);
                }
                throw new IOException("工作区对象存储写入失败", e);
            }
            return new StoredFile(
                    objectKey,
                    cleanedName,
                    contentType,
                    safeBytes.length,
                    workspaceStorageProvider(),
                    objectKey
            );
        }

        Path targetLocation = Paths.get(fileStorageConfig.getFullWorkspaceFileDir()).resolve(fileName);
        storeEncryptedBytes(targetLocation, safeBytes);

        return new StoredFile(
                fileName,
                cleanedName,
                contentType,
                safeBytes.length,
                "LOCAL",
                fileName
        );
    }

    /**
     * 删除文件
     */
    public boolean deleteFile(String filePath) {
        try {
            if (filePath.startsWith("/api/files/avatar/")) {
                String fileName = filePath.substring("/api/files/avatar/".length());
                Path file = Paths.get(fileStorageConfig.getFullAvatarDir()).resolve(fileName);
                return deleteLocalFile(file);
            } else if (filePath.startsWith("/api/files/chat/")) {
                String fileName = filePath.substring("/api/files/chat/".length());
                Path file = Paths.get(fileStorageConfig.getFullChatFileDir()).resolve(fileName);
                return deleteLocalFile(file);
            } else if (filePath.startsWith("/api/files/image-gen/")) {
                String fileName = filePath.substring("/api/files/image-gen/".length());
                Path file = Paths.get(fileStorageConfig.getFullImageGenDir()).resolve(fileName);
                return deleteLocalFile(file);
            } else if (filePath.startsWith("/api/files/background/")) {
                String fileName = filePath.substring("/api/files/background/".length());
                Path file = Paths.get(fileStorageConfig.getFullBackgroundDir()).resolve(fileName);
                return deleteLocalFile(file);
            } else if (filePath.startsWith("workspace:")) {
                String fileName = filePath.substring("workspace:".length());
                return deleteWorkspaceFile(fileName);
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean deleteWorkspaceFile(String storageNameOrObjectKey) throws IOException {
        if (!isLocalWorkspaceStorage()) {
            try {
                removeWorkspaceObjectIfExists(storageNameOrObjectKey);
                removeWorkspaceObjectIfExists(workspaceMetaObjectKey(storageNameOrObjectKey));
                return true;
            } catch (Exception e) {
                throw new IOException("工作区对象存储删除失败", e);
            }
        }
        Path file = Paths.get(fileStorageConfig.getFullWorkspaceFileDir()).resolve(storageNameOrObjectKey);
        return deleteLocalFile(file);
    }

    /**
     * 获取文件
     */
    public byte[] getFile(String type, String fileName) throws IOException {
        Path filePath;
        if ("avatar".equals(type)) {
            filePath = Paths.get(fileStorageConfig.getFullAvatarDir()).resolve(fileName);
        } else if ("chat".equals(type)) {
            filePath = Paths.get(fileStorageConfig.getFullChatFileDir()).resolve(fileName);
        } else if ("image-gen".equals(type)) {
            filePath = Paths.get(fileStorageConfig.getFullImageGenDir()).resolve(fileName);
        } else if ("background".equals(type)) {
            filePath = Paths.get(fileStorageConfig.getFullBackgroundDir()).resolve(fileName);
        } else if ("workspace".equals(type)) {
            return getWorkspaceFile(null, fileName);
        } else {
            throw new IllegalArgumentException("不支持的文件类型: " + type);
        }
        
        if (fileVaultService.isEncrypted(filePath)) {
            return fileVaultService.loadDecrypted(filePath);
        }

        if (!Files.exists(filePath)) {
            throw new IOException("文件不存在: " + fileName);
        }
        
        return Files.readAllBytes(filePath);
    }

    public byte[] getWorkspaceFile(String storageProvider, String storageNameOrObjectKey) throws IOException {
        if (isObjectWorkspaceProvider(storageProvider) || (storageProvider == null && !isLocalWorkspaceStorage())) {
            byte[] objectBytes = getWorkspaceObjectBytes(storageNameOrObjectKey);
            try {
                byte[] metaJson = getWorkspaceObjectBytes(workspaceMetaObjectKey(storageNameOrObjectKey));
                return fileVaultService.decryptObject(objectBytes, metaJson);
            } catch (IOException e) {
                if (isNotFound(e)) {
                    return objectBytes;
                }
                throw e;
            }
        }

        Path filePath = Paths.get(fileStorageConfig.getFullWorkspaceFileDir()).resolve(storageNameOrObjectKey);
        if (fileVaultService.isEncrypted(filePath)) {
            return fileVaultService.loadDecrypted(filePath);
        }
        if (!Files.exists(filePath)) {
            throw new IOException("文件不存在: " + storageNameOrObjectKey);
        }
        return Files.readAllBytes(filePath);
    }

    public List<String> listWorkspaceFileNames() throws IOException {
        if (!isLocalWorkspaceStorage()) {
            try {
                List<String> names = new ArrayList<>();
                Iterable<Result<Item>> results = workspaceClient().listObjects(ListObjectsArgs.builder()
                        .bucket(workspaceBucket())
                        .prefix(workspacePrefix())
                        .recursive(true)
                        .build());
                for (Result<Item> result : results) {
                    Item item = result.get();
                    if (!item.isDir() && !item.objectName().endsWith(".meta.json")) {
                        names.add(item.objectName());
                    }
                }
                return names;
            } catch (Exception e) {
                throw new IOException("工作区对象存储列表读取失败", e);
            }
        }
        Path dir = Paths.get(fileStorageConfig.getFullWorkspaceFileDir());
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().endsWith(".meta.json"))
                    .map(path -> path.getFileName().toString())
                    .map(name -> name.endsWith(".enc") ? name.substring(0, name.length() - ".enc".length()) : name)
                    .toList();
        }
    }

    public boolean encryptLegacyWorkspaceObjectIfPlaintext(String storageProvider, String objectKey) throws IOException {
        if (!isObjectWorkspaceProvider(storageProvider) || objectKey == null || objectKey.isBlank()) {
            return false;
        }
        try {
            getWorkspaceObjectBytes(workspaceMetaObjectKey(objectKey));
            return false;
        } catch (IOException e) {
            if (!isNotFound(e)) {
                throw e;
            }
        }

        byte[] plaintext = getWorkspaceObjectBytes(objectKey);
        FileVaultService.EncryptedPayload payload = fileVaultService.encryptObject(plaintext);
        try {
            putWorkspaceObject(objectKey, payload.ciphertext(), "application/octet-stream");
            putWorkspaceObject(workspaceMetaObjectKey(objectKey), payload.metaJson(), "application/json");
            return true;
        } catch (Exception e) {
            throw new IOException("工作区对象存储 legacy 加密失败: " + objectKey, e);
        }
    }

    private void storeEncryptedFile(Path basePath, MultipartFile file) throws IOException {
        if (file.getSize() <= fileVaultService.getStreamingThreshold()) {
            fileVaultService.storeEncrypted(basePath, file.getBytes());
            return;
        }
        fileVaultService.storeEncryptedStream(basePath, file.getInputStream(), file.getSize());
    }

    private void storeEncryptedBytes(Path basePath, byte[] bytes) throws IOException {
        byte[] safeBytes = bytes == null ? new byte[0] : bytes;
        if (safeBytes.length <= fileVaultService.getStreamingThreshold()) {
            fileVaultService.storeEncrypted(basePath, safeBytes);
            return;
        }
        fileVaultService.storeEncryptedStream(basePath, new ByteArrayInputStream(safeBytes), safeBytes.length);
    }

    private void putWorkspaceObject(String objectKey, byte[] bytes, String contentType) throws Exception {
        byte[] safeBytes = bytes == null ? new byte[0] : bytes;
        try (ByteArrayInputStream input = new ByteArrayInputStream(safeBytes)) {
            workspaceClient().putObject(PutObjectArgs.builder()
                    .bucket(workspaceBucket())
                    .object(objectKey)
                    .stream(input, safeBytes.length, -1)
                    .contentType(contentType)
                    .build());
        }
    }

    private byte[] getWorkspaceObjectBytes(String objectKey) throws IOException {
        try (var stream = workspaceClient().getObject(GetObjectArgs.builder()
                .bucket(workspaceBucket())
                .object(objectKey)
                .build())) {
            return stream.readAllBytes();
        } catch (Exception e) {
            throw new IOException("工作区对象不存在: " + objectKey, e);
        }
    }

    private void removeWorkspaceObjectIfExists(String objectKey) throws IOException {
        try {
            workspaceClient().removeObject(RemoveObjectArgs.builder()
                    .bucket(workspaceBucket())
                    .object(objectKey)
                    .build());
        } catch (ErrorResponseException e) {
            if (!isNoSuchKey(e)) {
                throw new IOException("工作区对象删除失败: " + objectKey, e);
            }
        } catch (Exception e) {
            throw new IOException("工作区对象删除失败: " + objectKey, e);
        }
    }

    private boolean deleteLocalFile(Path basePath) throws IOException {
        boolean deletedEncrypted = fileVaultService.deleteEncrypted(basePath);
        boolean deletedPlain = Files.deleteIfExists(basePath);
        return deletedEncrypted || deletedPlain;
    }


    public List<String> listExpiredImageGenFileUrls(LocalDateTime cutoff, int maxFiles) throws IOException {
        if (cutoff == null || maxFiles <= 0) {
            return List.of();
        }
        Path dir = Paths.get(fileStorageConfig.getFullImageGenDir());
        if (!Files.exists(dir)) {
            return List.of();
        }

        Instant cutoffInstant = cutoff.atZone(ZoneId.systemDefault()).toInstant();
        Set<String> urls = new LinkedHashSet<>();
        try (var stream = Files.list(dir)) {
            var iterator = stream.filter(Files::isRegularFile).iterator();
            while (iterator.hasNext() && urls.size() < maxFiles) {
                Path path = iterator.next();
                FileTime modifiedAt = Files.getLastModifiedTime(path);
                if (!modifiedAt.toInstant().isBefore(cutoffInstant)) {
                    continue;
                }
                String baseName = imageGenBaseName(path.getFileName().toString());
                if (baseName != null && !baseName.isBlank()) {
                    urls.add("/api/files/image-gen/" + baseName);
                }
            }
        }
        return List.copyOf(urls);
    }

    /**
     * 验证头像文件
     */
    private void validateAvatarFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        
        if (file.getSize() > fileStorageConfig.getMaxAvatarSize()) {
            throw new IllegalArgumentException("头像文件大小不能超过 " + 
                (fileStorageConfig.getMaxAvatarSize() / 1024 / 1024) + "MB");
        }
        
        String fileExtension = getFileExtension(file.getOriginalFilename());
        if (!Arrays.asList(fileStorageConfig.getAllowedAvatarTypes()).contains(fileExtension.toLowerCase())) {
            throw new IllegalArgumentException("不支持的头像文件类型，仅支持: " + 
                String.join(", ", fileStorageConfig.getAllowedAvatarTypes()));
        }
    }

    /**
     * 验证聊天文件
     */
    private void validateChatFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }

        validateChatFile(file.getOriginalFilename(), file.getSize());
    }

    private void validateChatFile(String originalFilename, long fileSize) {
        if (fileSize <= 0) {
            throw new IllegalArgumentException("文件不能为空");
        }

        if (fileSize > fileStorageConfig.getMaxFileSize()) {
            throw new IllegalArgumentException("文件大小不能超过 " + 
                (fileStorageConfig.getMaxFileSize() / 1024 / 1024) + "MB");
        }

        String fileExtension = getFileExtension(originalFilename);
        if (!Arrays.asList(fileStorageConfig.getAllowedChatFileTypes()).contains(fileExtension.toLowerCase())) {
            throw new IllegalArgumentException("不支持的文件类型，仅支持: " + 
                String.join(", ", fileStorageConfig.getAllowedChatFileTypes()));
        }
    }

    private void validateImageFile(String originalFilename, String contentType, long fileSize) {
        validateChatFile(originalFilename, fileSize);
        String extension = getFileExtension(originalFilename).toLowerCase(Locale.ROOT);
        boolean imageExtension = List.of("png", "jpg", "jpeg", "gif", "webp").contains(extension);
        boolean imageContentType = contentType == null || contentType.isBlank()
                || contentType.toLowerCase(Locale.ROOT).startsWith("image/");
        if (!imageExtension || !imageContentType) {
            throw new IllegalArgumentException("贴纸仅支持 png, jpg, gif, webp 图片");
        }
    }

    private void validateBackgroundFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }

        if (file.getSize() > fileStorageConfig.getMaxBackgroundSize()) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "聊天背景不能超过 " + (fileStorageConfig.getMaxBackgroundSize() / 1024 / 1024) + "MB");
        }

        String fileExtension = getFileExtension(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        boolean allowedExtension = Arrays.asList(fileStorageConfig.getAllowedBackgroundTypes())
                .contains(fileExtension);
        String contentType = file.getContentType();
        boolean allowedContentType = contentType == null || contentType.isBlank()
                || contentType.toLowerCase(Locale.ROOT).matches("image/(jpeg|png|webp)");
        if (!allowedExtension || !allowedContentType) {
            throw new IllegalArgumentException("聊天背景仅支持 jpg, png, webp 图片");
        }
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }
        int lastDotIndex = filename.lastIndexOf('.');
        return lastDotIndex > 0 ? filename.substring(lastDotIndex + 1) : "";
    }


    private String imageGenBaseName(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.endsWith(".meta.json")) {
            return null;
        }
        return fileName.endsWith(".enc")
                ? fileName.substring(0, fileName.length() - ".enc".length())
                : fileName;
    }

    private String cleanFileName(String filename, String fallback) {
        return StringUtils.cleanPath(filename == null || filename.isBlank() ? fallback : filename);
    }

    private boolean isLocalWorkspaceStorage() {
        return !isObjectWorkspaceProvider(fileStorageConfig.getWorkspaceProvider());
    }

    private boolean isObjectWorkspaceProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return false;
        }
        String normalized = provider.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("S3") || normalized.equals("MINIO");
    }

    private String workspaceStorageProvider() {
        if (isLocalWorkspaceStorage()) {
            return "LOCAL";
        }
        String provider = fileStorageConfig.getWorkspaceProvider();
        return provider == null || provider.isBlank()
                ? "S3"
                : provider.trim().toUpperCase(Locale.ROOT);
    }

    private String workspaceBucket() {
        String bucket = fileStorageConfig.getWorkspaceBucket();
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("工作区对象存储 bucket 未配置");
        }
        return bucket;
    }

    private String workspacePrefix() {
        String prefix = fileStorageConfig.getWorkspacePrefix();
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    private String workspaceObjectKey(String fileName) {
        return workspacePrefix() + fileName;
    }

    private String workspaceMetaObjectKey(String objectKey) {
        return objectKey + ".meta.json";
    }

    private boolean isNotFound(IOException e) {
        Throwable cause = e.getCause();
        return cause instanceof ErrorResponseException error && isNoSuchKey(error);
    }

    private boolean isNoSuchKey(ErrorResponseException error) {
        String code = error.errorResponse() == null ? "" : error.errorResponse().code();
        return "NoSuchKey".equals(code) || "NoSuchBucket".equals(code);
    }

    private void ensureWorkspaceBucket() throws Exception {
        MinioClient client = workspaceClient();
        String bucket = workspaceBucket();
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    private MinioClient workspaceClient() {
        MinioClient client = workspaceObjectClient;
        if (client != null) {
            return client;
        }
        synchronized (this) {
            if (workspaceObjectClient == null) {
                String endpoint = fileStorageConfig.getWorkspaceEndpoint();
                if (endpoint == null || endpoint.isBlank()) {
                    throw new IllegalStateException("工作区对象存储 endpoint 未配置");
                }
                if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
                    endpoint = (fileStorageConfig.isWorkspaceSecure() ? "https://" : "http://") + endpoint;
                }
                MinioClient.Builder builder = MinioClient.builder()
                        .endpoint(endpoint)
                        .credentials(
                                fileStorageConfig.getWorkspaceAccessKey(),
                                fileStorageConfig.getWorkspaceSecretKey());
                if (fileStorageConfig.getWorkspaceRegion() != null
                        && !fileStorageConfig.getWorkspaceRegion().isBlank()) {
                    builder.region(fileStorageConfig.getWorkspaceRegion());
                }
                workspaceObjectClient = builder.build();
            }
            return workspaceObjectClient;
        }
    }

    public record StoredFile(
            String storageFileName,
            String originalFileName,
            String contentType,
            long size,
            String storageProvider,
            String objectKey
    ) {
        public StoredFile(
                String storageFileName,
                String originalFileName,
                String contentType,
                long size) {
            this(storageFileName, originalFileName, contentType, size, "LOCAL", storageFileName);
        }
    }
}
