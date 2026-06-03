package com.chatapp.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
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
@EqualsAndHashCode(exclude = {"chatRoom", "user"})
@ToString(exclude = {"chatRoom", "user"})
public class ChatRoomMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_room_id", nullable = false)
    private ChatRoom chatRoom;

    @Enumerated(EnumType.STRING)
    @Column(name = "member_role", nullable = false)
    private MemberRole memberRole = MemberRole.MEMBER;

    @Column(name = "nickname", length = 100)
    private String nickname;

    @Column(name = "member_title", length = 100)
    private String memberTitle;

    /**
     * Legacy overloaded mute flag. KEPT as a dual-write shadow during the
     * is_muted-split transition (V20260603_2); dropped in a follow-up migration.
     * Prefer {@link #isBotMuted} / {@link #isNotificationMuted}.
     */
    @Column(name = "is_muted")
    private Boolean isMuted = false;

    /** Moderation / admin mute — blocks the member from SENDING messages. */
    @Column(name = "is_bot_muted")
    private Boolean isBotMuted = false;

    /** User's own notification mute — suppresses push, never blocks sending. */
    @Column(name = "is_notification_muted")
    private Boolean isNotificationMuted = false;

    @Column(name = "is_pinned")
    private Boolean isPinned = false;

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
