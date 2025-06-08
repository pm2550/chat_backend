package com.chatapp.service;

import java.io.IOException;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 云存储服务示例（阿里云OSS）
 * 生产环境推荐使用云存储替代本地文件系统
 */
@Service
public class CloudStorageService {

    @Value("${cloud.storage.enabled:false}")
    private boolean cloudStorageEnabled;

    @Value("${cloud.storage.endpoint:}")
    private String endpoint;

    @Value("${cloud.storage.access-key:}")
    private String accessKeyId;

    @Value("${cloud.storage.secret-key:}")
    private String accessKeySecret;

    @Value("${cloud.storage.bucket:}")
    private String bucketName;

    /**
     * 上传文件到云存储
     * 
     * 使用示例（需要添加阿里云OSS依赖）：
     * 
     * <dependency>
     *     <groupId>com.aliyun.oss</groupId>
     *     <artifactId>aliyun-sdk-oss</artifactId>
     *     <version>3.15.2</version>
     * </dependency>
     */
    public String uploadFile(MultipartFile file, String folder) throws IOException {
        if (!cloudStorageEnabled) {
            throw new UnsupportedOperationException("云存储未启用");
        }

        // 生成唯一文件名
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null ? 
            originalFilename.substring(originalFilename.lastIndexOf('.')) : "";
        String fileName = folder + "/" + UUID.randomUUID().toString() + extension;

        try {
            // 这里是阿里云OSS的示例代码
            // OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
            // PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, fileName, file.getInputStream());
            // ossClient.putObject(putObjectRequest);
            // ossClient.shutdown();
            
            // 返回文件的公网访问URL
            return "https://" + bucketName + "." + endpoint.replace("https://", "") + "/" + fileName;
            
        } catch (Exception e) {
            throw new IOException("云存储上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除云存储文件
     */
    public boolean deleteFile(String fileUrl) {
        if (!cloudStorageEnabled) {
            return false;
        }

        try {
            // 从URL中提取文件key
            String objectKey = extractObjectKeyFromUrl(fileUrl);
            
            // OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
            // ossClient.deleteObject(bucketName, objectKey);
            // ossClient.shutdown();
            
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String extractObjectKeyFromUrl(String fileUrl) {
        // 从完整URL中提取对象key
        // 例如：https://mybucket.oss-cn-hangzhou.aliyuncs.com/avatars/uuid.jpg
        // 提取：avatars/uuid.jpg
        String domain = "https://" + bucketName + "." + endpoint.replace("https://", "");
        return fileUrl.replace(domain + "/", "");
    }

    public boolean isCloudStorageEnabled() {
        return cloudStorageEnabled;
    }
} 