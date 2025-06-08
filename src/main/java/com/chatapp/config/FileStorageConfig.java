package com.chatapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

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
     * 最大文件大小（字节）- 默认10MB
     */
    private long maxFileSize = 10 * 1024 * 1024;

    /**
     * 最大头像大小（字节）- 默认5MB
     */
    private long maxAvatarSize = 5 * 1024 * 1024;

    /**
     * 允许的头像文件类型
     */
    private String[] allowedAvatarTypes = {"jpg", "jpeg", "png", "gif", "webp"};

    /**
     * 允许的聊天文件类型
     */
    private String[] allowedChatFileTypes = {"jpg", "jpeg", "png", "gif", "webp", "pdf", "doc", "docx", "txt", "zip", "rar"};

    /**
     * 获取完整的上传目录路径
     */
    public String getFullUploadDir() {
        return System.getProperty("user.dir") + "/" + uploadDir;
    }

    /**
     * 获取完整的头像目录路径
     */
    public String getFullAvatarDir() {
        return getFullUploadDir() + "/" + avatarDir;
    }

    /**
     * 获取完整的聊天文件目录路径
     */
    public String getFullChatFileDir() {
        return getFullUploadDir() + "/" + chatFileDir;
    }
} 