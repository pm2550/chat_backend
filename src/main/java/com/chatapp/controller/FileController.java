package com.chatapp.controller;

import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.service.AuditLogService;
import com.chatapp.service.FileStorageService;
import com.chatapp.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Optional;

/**
 * 文件访问控制器
 */
@RestController
@RequestMapping("/api/files")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;
    private final MessageRepository messageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserService userService;
    private final AuditLogService auditLogService;

    /**
     * 获取头像文件
     */
    @GetMapping("/avatar/{fileName}")
    public ResponseEntity<ByteArrayResource> getAvatar(@PathVariable String fileName) {
        try {
            byte[] fileData = fileStorageService.getFile("avatar", fileName);
            ByteArrayResource resource = new ByteArrayResource(fileData);
            
            // 确定文件类型
            String contentType = getContentType(fileName);
            
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"")
                    .body(resource);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 获取聊天背景文件。背景属于展示素材，和头像一样允许浏览器直接加载。
     */
    @GetMapping("/background/{fileName}")
    public ResponseEntity<ByteArrayResource> getBackground(@PathVariable String fileName) {
        try {
            byte[] fileData = fileStorageService.getFile("background", fileName);
            ByteArrayResource resource = new ByteArrayResource(fileData);
            String contentType = getContentType(fileName);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"")
                    .body(resource);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 获取聊天文件
     */
    @GetMapping("/chat/{fileName}")
    public ResponseEntity<ByteArrayResource> getChatFile(
            @PathVariable String fileName,
            Authentication auth) {
        return getMessageScopedFile(fileName, "/api/files/chat/" + fileName, "chat", auth);
    }

    @GetMapping("/image-gen/{fileName}")
    public ResponseEntity<ByteArrayResource> getGeneratedImage(
            @PathVariable String fileName,
            Authentication auth) {
        return getMessageScopedFile(fileName, "/api/files/image-gen/" + fileName, "image-gen", auth);
    }

    private ResponseEntity<ByteArrayResource> getMessageScopedFile(
            String fileName,
            String fileUrl,
            String storageType,
            Authentication auth) {
        try {
            if (auth == null || auth.getName() == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            User currentUser = userService.findUserByUsername(auth.getName());
            Optional<Message> message = messageRepository.findFirstByFileUrlAndIsDeletedFalse(fileUrl);
            if (message.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            Long roomId = message.get().getChatRoom().getId();
            if (!chatRoomRepository.isMember(roomId, currentUser.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            byte[] fileData = fileStorageService.getFile(storageType, fileName);
            ByteArrayResource resource = new ByteArrayResource(fileData);
            auditLogService.record(
                    currentUser,
                    "FILE_DOWNLOAD",
                    "MESSAGE",
                    message.get().getId(),
                    roomId,
                    fileName);
            
            // 确定文件类型
            String contentType = getContentType(fileName);
            
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .body(resource);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 根据文件名确定内容类型
     */
    private String getContentType(String fileName) {
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        
        switch (extension) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            case "pdf":
                return "application/pdf";
            case "doc":
                return "application/msword";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "txt":
                return "text/plain";
            case "zip":
                return "application/zip";
            case "rar":
                return "application/x-rar-compressed";
            default:
                return "application/octet-stream";
        }
    }
}
