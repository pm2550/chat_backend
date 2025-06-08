package com.chatapp.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天记录实体类
 */
@Entity
@Table(name = "chat_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 聊天室ID（群聊时使用）
     */
    @Column(name = "chat_room_id")
    private Long chatRoomId;

    /**
     * 发送者ID
     */
    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    /**
     * 接收者ID（私聊时使用）
     */
    @Column(name = "receiver_id")
    private Long receiverId;

    /**
     * 消息内容
     */
    @Column(columnDefinition = "TEXT")
    private String content;

    /**
     * 消息类型
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "message_type")
    private MessageType messageType = MessageType.TEXT;

    /**
     * 文件URL（当消息类型为文件时）
     */
    @Column(name = "file_url")
    private String fileUrl;

    /**
     * 文件名
     */
    @Column(name = "file_name")
    private String fileName;

    /**
     * 文件大小（字节）
     */
    @Column(name = "file_size")
    private Long fileSize;

    /**
     * 回复的消息ID
     */
    @Column(name = "reply_to_id")
    private Long replyToId;

    /**
     * 是否已撤回
     */
    @Column(name = "is_recalled")
    private Boolean isRecalled = false;

    /**
     * 撤回时间
     */
    @Column(name = "recalled_at")
    private LocalDateTime recalledAt;

    /**
     * 是否已删除
     */
    @Column(name = "is_deleted")
    private Boolean isDeleted = false;

    /**
     * 删除时间
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * 发送时间
     */
    @CreationTimestamp
    @Column(name = "sent_at", updatable = false)
    private LocalDateTime sentAt;

    /**
     * 发送者信息（冗余存储，避免用户删除后消息显示问题）
     */
    @Column(name = "sender_name")
    private String senderName;

    @Column(name = "sender_avatar")
    private String senderAvatar;

    /**
     * 消息类型枚举
     */
    public enum MessageType {
        TEXT("文本"),
        IMAGE("图片"),
        FILE("文件"),
        AUDIO("语音"),
        VIDEO("视频"),
        SYSTEM("系统消息");

        private final String description;

        MessageType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
} 