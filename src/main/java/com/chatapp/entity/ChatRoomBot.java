package com.chatapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_room_bots",
       uniqueConstraints = @UniqueConstraint(columnNames = {"chat_room_id", "bot_config_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRoomBot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_room_id", nullable = false)
    private ChatRoom chatRoom;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bot_config_id", nullable = false)
    private BotConfig botConfig;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_mode")
    private TriggerMode triggerMode = TriggerMode.MENTION;

    @Column(name = "trigger_keywords", length = 500)
    private String triggerKeywords;

    @Column(name = "room_nickname", length = 100)
    private String roomNickname;

    @Column(name = "room_prompt_suffix", columnDefinition = "TEXT")
    private String roomPromptSuffix;

    @Column(name = "enabled_in_room")
    private Boolean enabledInRoom = true;

    @Column(name = "is_active")
    private Boolean isActive = true;

    // F5 Slice 2: moderation powers a room OWNER granted this bot in this room.
    // varchar column + @JdbcTypeCode(VARCHAR) keeps @Enumerated(STRING) validate-safe.
    // Monotonic: KICK implies MUTE (see ordinal-based check in ModerationService).
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "moderation_grant", nullable = false, length = 16)
    private ModerationGrant moderationGrant = ModerationGrant.NONE;

    @Column(name = "added_at")
    private LocalDateTime addedAt = LocalDateTime.now();

    public enum TriggerMode {
        MENTION,    // @机器人 触发
        KEYWORD,    // 关键词触发
        REGEX,      // 正则触发
        ALL         // 所有消息都触发
    }

    /** Order matters: a higher grant implies all lower ones (NONE < MUTE < KICK). */
    public enum ModerationGrant {
        NONE,
        MUTE,
        KICK
    }
}
