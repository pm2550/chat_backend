package com.chatapp.service;

import com.chatapp.config.FileStorageConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.UUID;

/**
 * 文件存储服务
 */
@Service
public class FileStorageService {

    @Autowired
    private FileStorageConfig fileStorageConfig;

    /**
     * 初始化存储目录
     */
    public void init() {
        try {
            Files.createDirectories(Paths.get(fileStorageConfig.getFullUploadDir()));
            Files.createDirectories(Paths.get(fileStorageConfig.getFullAvatarDir()));
            Files.createDirectories(Paths.get(fileStorageConfig.getFullChatFileDir()));
        } catch (IOException e) {
            throw new RuntimeException("无法创建文件存储目录", e);
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
            }
            return false;
        } catch (IOException e) {
            return false;
        }
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
        } else {
            throw new IllegalArgumentException("不支持的文件类型: " + type);
        }
        
        if (!Files.exists(filePath)) {
            throw new IOException("文件不存在: " + fileName);
        }
        
        return Files.readAllBytes(filePath);
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
        
        if (file.getSize() > fileStorageConfig.getMaxFileSize()) {
            throw new IllegalArgumentException("文件大小不能超过 " + 
                (fileStorageConfig.getMaxFileSize() / 1024 / 1024) + "MB");
        }
        
        String fileExtension = getFileExtension(file.getOriginalFilename());
        if (!Arrays.asList(fileStorageConfig.getAllowedChatFileTypes()).contains(fileExtension.toLowerCase())) {
            throw new IllegalArgumentException("不支持的文件类型，仅支持: " + 
                String.join(", ", fileStorageConfig.getAllowedChatFileTypes()));
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
} 