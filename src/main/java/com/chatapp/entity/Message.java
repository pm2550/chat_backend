package com.chatapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 消息实体类
 */
@Entity
@Table(name = "messages")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false)
    private MessageType messageType = MessageType.TEXT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_room_id", nullable = false)
    private ChatRoom chatRoom;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reply_to_message_id")
    private Message replyToMessage;

    @Column(name = "file_url")
    private String fileUrl;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "file_type")
    private String fileType;

    @Column(name = "thumbnail_url")
    private String thumbnailUrl;

    @Column(name = "duration")
    private Integer duration; // 音频/视频时长（秒）

    @Column(name = "width")
    private Integer width; // 图片/视频宽度

    @Column(name = "height")
    private Integer height; // 图片/视频高度

    @Enumerated(EnumType.STRING)
    @Column(name = "message_status")
    private MessageStatus messageStatus = MessageStatus.SENT;

    @Column(name = "is_deleted")
    private Boolean isDeleted = false;

    @Column(name = "is_edited")
    private Boolean isEdited = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "read_count")
    private Integer readCount = 0;

    /**
     * 消息类型枚举
     */
    public enum MessageType {
        TEXT("文本"),
        IMAGE("图片"),
        FILE("文件"),
        VOICE("语音"),
        VIDEO("视频"),
        LOCATION("位置"),
        SYSTEM("系统消息");

        private final String description;

        MessageType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 消息状态枚举
     */
    public enum MessageStatus {
        SENDING("发送中"),
        SENT("已发送"),
        DELIVERED("已送达"),
        READ("已读"),
        FAILED("发送失败");

        private final String description;

        MessageStatus(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
} 