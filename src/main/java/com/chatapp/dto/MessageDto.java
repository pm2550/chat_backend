package com.chatapp.dto;

import com.chatapp.entity.Message;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
 *
 * <p>匿名消息的真实身份只给发送者本人：{@link #fromEntity(Message)} 产出的是"公开版"，
 * 匿名消息里没有 sender / senderId / 真名真头像，只有匿名身份；
 * {@link #fromEntity(Message, Long)} 按查看者生成，查看者就是发送者时才带回真实 sender，
 * 并用 {@code sentByMe} 告诉客户端"这是我发的"。服务器内部需要真实发送者时用
 * {@link #getRealSenderId()}，它不会被序列化。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageDto {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private Long id;
    private String content;
    private Message.MessageType messageType;
    private Message.ContentFormat contentFormat;
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
    private Long forwardedFromMessageId;
    private List<Long> mentionedUserIds;
    private String fileUrl;
    private String fileName;
    private Long fileSize;
    private String fileType;
    private String thumbnailUrl;
    private String previewUrl;
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
    private LocalDateTime editedAt;
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
    // 当前用户是否收藏了该消息；只有带用户上下文的列表接口才会填充，其余情况为 null
    private Boolean starredByMe;
    // 这条消息是不是查看者自己发的（按查看者计算；不知道查看者时为 null）。
    // 匿名消息对别人不带 senderId，客户端只能靠它认出"我发的"。
    private Boolean sentByMe;
    // 真实发送者，仅供服务器内部判断（已读遮蔽、推送排除等），永不下发。
    @JsonIgnore
    private Long realSenderId;

    public static final String ANONYMOUS_FALLBACK_NAME = "匿名用户";

    /** 公开版：不针对任何查看者，匿名消息不带真实身份。广播、机器人接口用它。 */
    public static MessageDto fromEntity(Message message) {
        return fromEntity(message, true, null);
    }

    /** 按查看者生成：viewerId 是发送者本人时带回真实 sender，并填 sentByMe。 */
    public static MessageDto fromEntity(Message message, Long viewerId) {
        return fromEntity(message, true, viewerId);
    }

    /** 对外展示的发送者名字：匿名消息只给匿名名，机器人给机器人名，其余给显示名/用户名。 */
    public static String publicSenderName(Message message) {
        if (message == null) {
            return null;
        }
        if (Boolean.TRUE.equals(message.getIsAnonymous())) {
            return message.getAnonymousIdentity() != null
                    ? prefer(message.getAnonymousIdentity().getAnonymousName(), ANONYMOUS_FALLBACK_NAME)
                    : ANONYMOUS_FALLBACK_NAME;
        }
        if (message.getBotConfig() != null) {
            return prefer(message.getBotDisplayName(), message.getBotConfig().getBotName());
        }
        if (message.getSender() == null) {
            return null;
        }
        return prefer(message.getSender().getDisplayName(), message.getSender().getUsername());
    }

    private static MessageDto fromEntity(Message message, boolean includeReply, Long viewerId) {
        if (message == null) {
            return null;
        }

        MessageDto dto = new MessageDto();
        dto.setId(message.getId());
        dto.setContent(message.getContent());
        dto.setMessageType(message.getMessageType());
        dto.setContentFormat(message.getContentFormat());
        dto.setMessageStatus(message.getMessageStatus());

        boolean anonymous = Boolean.TRUE.equals(message.getIsAnonymous());
        Long realSenderId = message.getSender() == null ? null : message.getSender().getId();
        boolean viewerIsSender = viewerId != null && viewerId.equals(realSenderId);
        dto.setRealSenderId(realSenderId);
        // 匿名消息只有发送者本人能看到真实 sender；别人（以及不针对查看者的公开版）一律不带。
        if (!anonymous || viewerIsSender) {
            UserDto sender = toUserDto(message.getSender());
            dto.setSender(sender);
            if (sender != null) {
                dto.setSenderId(sender.getId());
                dto.setSenderName(sender.getDisplayName() != null && !sender.getDisplayName().isBlank()
                        ? sender.getDisplayName()
                        : sender.getUsername());
                dto.setSenderAvatar(sender.getAvatarUrl());
            }
        }
        if (viewerId != null) {
            // 机器人消息的 sender 是机器人的主人，但那不是"我发的"。
            dto.setSentByMe(viewerIsSender && message.getBotConfig() == null);
        }
        if (message.getBotConfig() != null) {
            dto.setBotConfigId(message.getBotConfig().getId());
            dto.setBotSenderId(message.getBotConfig().getId());
            dto.setBotName(prefer(message.getBotDisplayName(), message.getBotConfig().getBotName()));
            dto.setBotAvatar(message.getBotConfig().getBotAvatar());
        }
        dto.setIsAnonymous(anonymous);
        if (anonymous) {
            dto.setSenderName(ANONYMOUS_FALLBACK_NAME);
            dto.setSenderAvatar(null);
        }
        if (anonymous && message.getAnonymousIdentity() != null) {
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
                dto.setReplyToMessage(fromEntity(message.getReplyToMessage(), false, viewerId));
            }
        }
        if (message.getForwardedFromMessage() != null) {
            dto.setForwardedFromMessageId(message.getForwardedFromMessage().getId());
        }
        dto.setMentionedUserIds(message.getMentionedUserIds() == null
                ? List.of()
                : new ArrayList<>(message.getMentionedUserIds()));
        dto.setFileUrl(message.getFileUrl());
        dto.setFileName(message.getFileName());
        dto.setFileSize(message.getFileSize());
        dto.setFileType(message.getFileType());
        dto.setThumbnailUrl(message.getThumbnailUrl());
        dto.setPreviewUrl(message.getPreviewUrl());
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
        if (Boolean.TRUE.equals(message.getIsEdited())) {
            dto.setEditedAt(message.getUpdatedAt());
        }
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

    private static String prefer(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private static UserDto toUserDto(com.chatapp.entity.User user) {
        if (user == null) {
            return null;
        }
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setDisplayName(user.getDisplayName());
        dto.setAvatarUrl(user.getAvatarUrl());
        dto.setTitle(user.getTitle());
        dto.setTitleColor(user.getTitleColor());
        dto.setTitleEffect(user.getTitleEffect());
        dto.setBio(user.getBio());
        // 消息里的发送者不带在线状态/最后在线时间：消息不是在线状态的通道，
        // 带上就会绕过"显示在线状态"的隐私设置（在线状态走成员/好友列表和 status 事件）。
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
