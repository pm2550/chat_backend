package com.chatapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件存储配置
 */
@Configuration
@ConfigurationProperties(prefix = "file.storage")
@Data
public class FileStorageConfig {

    /**
     * 文件上传根目录
     */
    private String uploadDir = "uploads";

    /**
     * 头像存储目录
     */
    private String avatarDir = "avatars";

    /**
     * 聊天文件存储目录
     */
    private String chatFileDir = "chat-files";

    /**
     * 工作区文件存储目录
     */
    private String workspaceFileDir = "workspace-files";

    /**
     * 聊天背景存储目录
     */
    private String backgroundDir = "backgrounds";

    /**
     * Workspace file storage provider. LOCAL keeps current disk behavior;
     * S3/MINIO stores workspace objects in an S3-compatible bucket.
     */
    private String workspaceProvider = "LOCAL";

    /**
     * S3-compatible endpoint, for example https://s3.example.com or http://minio:9000.
     */
    private String workspaceEndpoint = "";

    private String workspaceAccessKey = "";

    private String workspaceSecretKey = "";

    private String workspaceBucket = "pm-chat-workspace";

    private String workspaceRegion = "";

    private boolean workspaceSecure = true;

    private String workspacePrefix = "workspace-files/";

    /**
     * basic or clamav.
     */
    private String scanMode = "basic";

    /**
     * When ClamAV is configured but temporarily unavailable, fail-open records
     * a FAILED scan status and allows the upload. The production default is fail-closed.
     */
    private boolean scanFailOpen = false;

    private String clamavHost = "127.0.0.1";

    private int clamavPort = 3310;

    private int clamavTimeoutMs = 5000;

    /**
     * 最大文件大小（字节）- 默认10MB
     */
    private long maxFileSize = 10 * 1024 * 1024;

    /**
     * 最大头像大小（字节）- 默认5MB
     */
    private long maxAvatarSize = 5 * 1024 * 1024;

    /**
     * 最大聊天背景大小（字节）- 默认2MB
     */
    private long maxBackgroundSize = 2 * 1024 * 1024;

    /**
     * 允许的头像文件类型
     */
    private String[] allowedAvatarTypes = {"jpg", "jpeg", "png", "gif", "webp"};

    /**
     * 允许的聊天背景文件类型
     */
    private String[] allowedBackgroundTypes = {"jpg", "jpeg", "png", "webp"};

    /**
     * 允许的聊天文件类型
     */
    private String[] allowedChatFileTypes = {
            "jpg", "jpeg", "png", "gif", "webp",
            "pdf", "doc", "docx", "txt", "zip", "rar",
            "mp3", "m4a", "wav", "aac", "ogg", "webm", "mp4", "mov"
    };

    /**
     * 获取完整的上传目录路径
     */
    public String getFullUploadDir() {
        return resolveDir(uploadDir);
    }

    /**
     * 获取完整的头像目录路径
     */
    public String getFullAvatarDir() {
        return resolveChildDir(getFullUploadDir(), avatarDir);
    }

    /**
     * 获取完整的聊天文件目录路径
     */
    public String getFullChatFileDir() {
        return resolveChildDir(getFullUploadDir(), chatFileDir);
    }

    /**
     * 获取完整的工作区文件目录路径
     */
    public String getFullWorkspaceFileDir() {
        return resolveChildDir(getFullUploadDir(), workspaceFileDir);
    }

    /**
     * 获取完整的聊天背景目录路径
     */
    public String getFullBackgroundDir() {
        return resolveChildDir(getFullUploadDir(), backgroundDir);
    }

    private String resolveDir(String dir) {
        Path path = Paths.get(dir);
        return path.isAbsolute()
                ? path.normalize().toString()
                : Paths.get(System.getProperty("user.dir")).resolve(path).normalize().toString();
    }

    private String resolveChildDir(String base, String child) {
        Path childPath = Paths.get(child);
        return childPath.isAbsolute()
                ? childPath.normalize().toString()
                : Paths.get(base).resolve(childPath).normalize().toString();
    }
}
