package com.chatapp.dto;

import com.chatapp.entity.MemoryEntry;

import java.time.LocalDateTime;

/** Read view of a {@link MemoryEntry} for the memory API. */
public record MemoryDto(
        Long id,
        Long chatRoomId,
        String title,
        String content,
        String keywords,
        String sourceType,
        String visibility,
        boolean pinned,
        boolean archived,
        Long authorUserId,
        Long authorBotConfigId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static MemoryDto from(MemoryEntry m) {
        return new MemoryDto(
                m.getId(),
                m.getChatRoomId(),
                m.getTitle(),
                m.getContent(),
                m.getKeywords(),
                m.getSourceType() != null ? m.getSourceType().name() : null,
                m.getVisibility() != null ? m.getVisibility().name() : null,
                Boolean.TRUE.equals(m.getPinned()),
                Boolean.TRUE.equals(m.getArchived()),
                m.getAuthorUserId(),
                m.getAuthorBotConfigId(),
                m.getCreatedAt(),
                m.getUpdatedAt());
    }
}
