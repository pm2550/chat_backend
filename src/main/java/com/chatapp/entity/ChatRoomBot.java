package com.chatapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "added_at")
    private LocalDateTime addedAt = LocalDateTime.now();

    public enum TriggerMode {
        MENTION,    // @机器人 触发
        KEYWORD,    // 关键词触发
        ALL         // 所有消息都触发
    }
}
