package com.chatapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 聊天室成员实体类
 */
@Entity
@Table(name = "chat_room_members", 
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "chat_room_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRoomMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_room_id", nullable = false)
    private ChatRoom chatRoom;

    @Enumerated(EnumType.STRING)
    @Column(name = "member_role", nullable = false)
    private MemberRole memberRole = MemberRole.MEMBER;

    @Column(name = "nickname", length = 100)
    private String nickname;

    @Column(name = "is_muted")
    private Boolean isMuted = false;

    @Column(name = "is_admin")
    private Boolean isAdmin = false;

    @Column(name = "joined_at")
    @CreationTimestamp
    private LocalDateTime joinedAt;

    @Column(name = "last_read_message_id")
    private Long lastReadMessageId;

    @Column(name = "unread_count")
    private Integer unreadCount = 0;

    /**
     * 成员角色枚举
     */
    public enum MemberRole {
        OWNER("群主"),
        ADMIN("管理员"),
        MODERATOR("版主"),
        MEMBER("普通成员");

        private final String description;

        MemberRole(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
} 