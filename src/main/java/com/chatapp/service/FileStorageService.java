package com.chatapp.service;

import com.chatapp.config.FileStorageConfig;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 文件存储服务
 */
@Service
public class FileStorageService {

    @Autowired
    private FileStorageConfig fileStorageConfig;

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
        
        // 存储文件
        Path targetLocation = Paths.get(fileStorageConfig.getFullAvatarDir()).resolve(fileName);
        Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);
        
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
        
        // 存储文件
        Path targetLocation = Paths.get(fileStorageConfig.getFullChatFileDir()).resolve(fileName);
        Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);
        
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
        Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

        return "/api/files/background/" + fileName;
    }

    public String uploadStickerFile(String originalFilename, String contentType, byte[] bytes) throws IOException {
        String safeName = cleanFileName(originalFilename, "sticker.png");
        validateImageFile(safeName, contentType, bytes == null ? 0 : bytes.length);
        String fileExtension = getFileExtension(safeName);
        String fileName = UUID.randomUUID().toString() + "." + (fileExtension.isBlank() ? "png" : fileExtension);
        Path targetLocation = Paths.get(fileStorageConfig.getFullChatFileDir()).resolve(fileName);
        Files.write(targetLocation, bytes == null ? new byte[0] : bytes);
        return "/api/files/chat/" + fileName;
    }

    public String uploadGeneratedImage(String originalFilename, String contentType, byte[] bytes) throws IOException {
        String safeName = cleanFileName(originalFilename, "image.png");
        validateImageFile(safeName, contentType, bytes == null ? 0 : bytes.length);
        String fileExtension = getFileExtension(safeName);
        String fileName = UUID.randomUUID() + "." + (fileExtension.isBlank() ? "png" : fileExtension);
        Path targetLocation = Paths.get(fileStorageConfig.getFullImageGenDir()).resolve(fileName);
        Files.write(targetLocation, bytes == null ? new byte[0] : bytes);
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
            try (ByteArrayInputStream input = new ByteArrayInputStream(safeBytes)) {
                workspaceClient().putObject(PutObjectArgs.builder()
                        .bucket(workspaceBucket())
                        .object(objectKey)
                        .stream(input, safeBytes.length, -1)
                        .contentType(contentType == null || contentType.isBlank()
                                ? "application/octet-stream"
                                : contentType)
                        .build());
            } catch (Exception e) {
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
        Files.write(targetLocation, safeBytes);

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
                return Files.deleteIfExists(file);
            } else if (filePath.startsWith("/api/files/chat/")) {
                String fileName = filePath.substring("/api/files/chat/".length());
                Path file = Paths.get(fileStorageConfig.getFullChatFileDir()).resolve(fileName);
                return Files.deleteIfExists(file);
            } else if (filePath.startsWith("/api/files/background/")) {
                String fileName = filePath.substring("/api/files/background/".length());
                Path file = Paths.get(fileStorageConfig.getFullBackgroundDir()).resolve(fileName);
                return Files.deleteIfExists(file);
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
                workspaceClient().removeObject(RemoveObjectArgs.builder()
                        .bucket(workspaceBucket())
                        .object(storageNameOrObjectKey)
                        .build());
                return true;
            } catch (Exception e) {
                throw new IOException("工作区对象存储删除失败", e);
            }
        }
        Path file = Paths.get(fileStorageConfig.getFullWorkspaceFileDir()).resolve(storageNameOrObjectKey);
        return Files.deleteIfExists(file);
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
        
        if (!Files.exists(filePath)) {
            throw new IOException("文件不存在: " + fileName);
        }
        
        return Files.readAllBytes(filePath);
    }

    public byte[] getWorkspaceFile(String storageProvider, String storageNameOrObjectKey) throws IOException {
        if (isObjectWorkspaceProvider(storageProvider) || (storageProvider == null && !isLocalWorkspaceStorage())) {
            try (var stream = workspaceClient().getObject(GetObjectArgs.builder()
                    .bucket(workspaceBucket())
                    .object(storageNameOrObjectKey)
                    .build())) {
                return stream.readAllBytes();
            } catch (Exception e) {
                throw new IOException("工作区对象不存在: " + storageNameOrObjectKey, e);
            }
        }

        Path filePath = Paths.get(fileStorageConfig.getFullWorkspaceFileDir()).resolve(storageNameOrObjectKey);
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
                    if (!item.isDir()) {
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
                    .map(path -> path.getFileName().toString())
                    .toList();
        }
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
