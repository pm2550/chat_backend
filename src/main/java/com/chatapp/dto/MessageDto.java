package com.chatapp.dto;

import com.chatapp.entity.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Base64;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 消息DTO类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageDto {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private Long id;
    private String content;
    private Message.MessageType messageType;
    private Message.MessageStatus messageStatus;
    private UserDto sender;
    private Long senderId;
    private String senderName;
    private String senderAvatar;
    private Long botConfigId;
    private Long botSenderId;
    private String botName;
    private String botAvatar;
    private Long chatRoomId;
    private Long replyToMessageId;
    private MessageDto replyToMessage;
    private List<Long> mentionedUserIds;
    private String fileUrl;
    private String fileName;
    private Long fileSize;
    private String fileType;
    private String thumbnailUrl;
    private UrlPreviewDto linkPreview;
    private Long stickerId;
    private Long pollId;
    private String imageGenPrompt;
    private Message.ImageGenerationStatus imageGenStatus;
    private String imageGenUrl;
    private String imageGenProviderTaskId;
    private Integer duration;
    private Integer width;
    private Integer height;
    private Boolean isDeleted;
    private Boolean isEdited;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer readCount;
    private String encryptedContent;
    private Integer encryptionVersion;
    private Boolean isAnonymous;
    private Long anonymousIdentityId;
    private String anonymousName;
    private String anonymousAvatar;
    private List<ReactionInfo> reactions = List.of();

    public static MessageDto fromEntity(Message message) {
        return fromEntity(message, true);
    }

    private static MessageDto fromEntity(Message message, boolean includeReply) {
        if (message == null) {
            return null;
        }

        MessageDto dto = new MessageDto();
        dto.setId(message.getId());
        dto.setContent(message.getContent());
        dto.setMessageType(message.getMessageType());
        dto.setMessageStatus(message.getMessageStatus());

        UserDto sender = toUserDto(message.getSender());
        dto.setSender(sender);
        if (sender != null) {
            dto.setSenderId(sender.getId());
            dto.setSenderName(sender.getDisplayName() != null && !sender.getDisplayName().isBlank()
                    ? sender.getDisplayName()
                    : sender.getUsername());
            dto.setSenderAvatar(sender.getAvatarUrl());
        }
        if (message.getBotConfig() != null) {
            dto.setBotConfigId(message.getBotConfig().getId());
            dto.setBotSenderId(message.getBotConfig().getId());
            dto.setBotName(message.getBotConfig().getBotName());
            dto.setBotAvatar(message.getBotConfig().getBotAvatar());
        }
        dto.setIsAnonymous(Boolean.TRUE.equals(message.getIsAnonymous()));
        if (Boolean.TRUE.equals(message.getIsAnonymous()) && message.getAnonymousIdentity() != null) {
            dto.setAnonymousIdentityId(message.getAnonymousIdentity().getId());
            dto.setAnonymousName(message.getAnonymousIdentity().getAnonymousName());
            dto.setAnonymousAvatar(message.getAnonymousIdentity().getAnonymousAvatar());
            dto.setSenderName(message.getAnonymousIdentity().getAnonymousName());
            dto.setSenderAvatar(message.getAnonymousIdentity().getAnonymousAvatar());
        }

        if (message.getChatRoom() != null) {
            dto.setChatRoomId(message.getChatRoom().getId());
        }
        if (message.getReplyToMessage() != null) {
            dto.setReplyToMessageId(message.getReplyToMessage().getId());
            if (includeReply) {
                dto.setReplyToMessage(fromEntity(message.getReplyToMessage(), false));
            }
        }
        dto.setMentionedUserIds(message.getMentionedUserIds() == null
                ? List.of()
                : new ArrayList<>(message.getMentionedUserIds()));
        dto.setFileUrl(message.getFileUrl());
        dto.setFileName(message.getFileName());
        dto.setFileSize(message.getFileSize());
        dto.setFileType(message.getFileType());
        dto.setThumbnailUrl(message.getThumbnailUrl());
        dto.setLinkPreview(parseLinkPreview(message.getLinkPreviewJson()));
        dto.setStickerId(message.getStickerId());
        dto.setPollId(message.getPollId());
        dto.setImageGenPrompt(message.getImageGenPrompt());
        dto.setImageGenStatus(message.getImageGenStatus());
        dto.setImageGenUrl(message.getImageGenUrl());
        dto.setImageGenProviderTaskId(message.getImageGenProviderTaskId());
        dto.setDuration(message.getDuration());
        dto.setWidth(message.getWidth());
        dto.setHeight(message.getHeight());
        dto.setIsDeleted(Boolean.TRUE.equals(message.getIsDeleted()));
        dto.setIsEdited(Boolean.TRUE.equals(message.getIsEdited()));
        dto.setCreatedAt(message.getCreatedAt());
        dto.setUpdatedAt(message.getUpdatedAt());
        dto.setReadCount(message.getReadCount());
        if (message.getEncryptedContent() != null && message.getEncryptedContent().length > 0) {
            dto.setEncryptedContent(Base64.getEncoder().encodeToString(message.getEncryptedContent()));
        }
        dto.setEncryptionVersion(message.getEncryptionVersion());
        return dto;
    }

    private static UrlPreviewDto parseLinkPreview(String linkPreviewJson) {
        if (linkPreviewJson == null || linkPreviewJson.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(linkPreviewJson, UrlPreviewDto.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static UserDto toUserDto(com.chatapp.entity.User user) {
        if (user == null) {
            return null;
        }
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setPhone(user.getPhone());
        dto.setDisplayName(user.getDisplayName());
        dto.setAvatarUrl(user.getAvatarUrl());
        dto.setTitle(user.getTitle());
        dto.setTitleColor(user.getTitleColor());
        dto.setTitleEffect(user.getTitleEffect());
        dto.setBio(user.getBio());
        dto.setOnlineStatus(user.getOnlineStatus());
        dto.setLastSeen(user.getLastSeen());
        dto.setIsActive(user.getIsActive());
        dto.setCreatedAt(user.getCreatedAt());
        return dto;
    }

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
        private String encryptedContent;
        private Integer encryptionVersion;
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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReactionInfo {
        private String emoji;
        private Integer count;
        private List<Long> userIds;
        private Boolean currentUserReacted;
    }
}
