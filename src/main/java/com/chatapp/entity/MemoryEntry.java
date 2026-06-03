package com.chatapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * A durable fact in a room's memory library (Phase 5a / F2). Authored either by a bot
 * (via the save_memory tool, {@link SourceType#BOT}) or by a user through the memory
 * API. {@link Visibility#ROOM} entries are shared room knowledge; {@link Visibility#PRIVATE}
 * entries are visible only to their author.
 */
@Entity
@Table(name = "memory_entries")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemoryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_room_id", nullable = false)
    private Long chatRoomId;

    /** The user who authored this entry, or null if it was written by a bot/service. */
    @Column(name = "author_user_id")
    private Long authorUserId;

    /** The bot that authored this entry, or null if a user wrote it. */
    @Column(name = "author_bot_config_id")
    private Long authorBotConfigId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** Optional comma/space-separated keywords to bias recall matching. */
    @Column(length = 500)
    private String keywords;

    // @JdbcTypeCode(VARCHAR): keep these @Enumerated(STRING) fields mapped onto the varchar
    // columns above (NOT a native MySQL enum) so ddl-auto:validate passes — see the
    // migration header for the content_format crash that this avoids.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source_type", nullable = false, length = 16)
    private SourceType sourceType = SourceType.USER;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16)
    private Visibility visibility = Visibility.ROOM;

    @Column(name = "is_pinned", nullable = false)
    private Boolean pinned = false;

    @Column(name = "is_archived", nullable = false)
    private Boolean archived = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum SourceType {
        BOT,
        USER,
        SERVICE
    }

    public enum Visibility {
        ROOM,
        PRIVATE
    }
}
