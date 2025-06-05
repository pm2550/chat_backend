package com.chatapp.dto;

import com.chatapp.entity.Message;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 消息DTO类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageDto {

    private Long id;
    private String content;
    private Message.MessageType messageType;
    private UserDto sender;
    private Long chatRoomId;
    private Long replyToMessageId;
    private String fileUrl;
    private String fileName;
    private Long fileSize;
    private String fileType;
    private String thumbnailUrl;
    private Integer duration;
    private Integer width;
    private Integer height;
    private Message.MessageStatus messageStatus;
    private Boolean isDeleted;
    private Boolean isEdited;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer readCount;

    /**
     * 发送消息请求DTO
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SendMessageRequest {
        private String content;
        private Message.MessageType messageType;
        private Long chatRoomId;
        private Long replyToMessageId;
        private String fileUrl;
        private String fileName;
        private Long fileSize;
        private String fileType;
        private String thumbnailUrl;
        private Integer duration;
        private Integer width;
        private Integer height;
    }

    /**
     * WebSocket消息DTO
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WebSocketMessage {
        private String type; // "message", "typing", "read", etc.
        private Long chatRoomId;
        private MessageDto message;
        private UserDto user;
        private Object data;
    }

    /**
     * 消息分页查询请求DTO
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessagePageRequest {
        private Long chatRoomId;
        private Integer page = 0;
        private Integer size = 20;
        private Long beforeMessageId;
        private Long afterMessageId;
    }
} 