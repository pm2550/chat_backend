package com.chatapp.repository;

import com.chatapp.entity.MemoryEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MemoryEntryRepository extends JpaRepository<MemoryEntry, Long> {

    long countByChatRoomIdAndArchivedFalse(Long chatRoomId);

    /**
     * Room-visible, non-archived entries plus the requesting user's own PRIVATE ones,
     * pinned first then most-recently updated. ({@code userId} may be null for a
     * bot-only caller, which then sees ROOM entries only.)
     */
    @Query("SELECT m FROM MemoryEntry m WHERE m.chatRoomId = :roomId AND m.archived = false AND "
            + "(m.visibility = com.chatapp.entity.MemoryEntry$Visibility.ROOM "
            + "OR m.authorUserId = :userId) "
            + "ORDER BY m.pinned DESC, m.updatedAt DESC")
    List<MemoryEntry> findVisible(@Param("roomId") Long roomId, @Param("userId") Long userId);

    /** Same visibility rule, but including archived entries (for the management view). */
    @Query("SELECT m FROM MemoryEntry m WHERE m.chatRoomId = :roomId AND "
            + "(m.visibility = com.chatapp.entity.MemoryEntry$Visibility.ROOM "
            + "OR m.authorUserId = :userId) "
            + "ORDER BY m.pinned DESC, m.updatedAt DESC")
    List<MemoryEntry> findVisibleIncludingArchived(@Param("roomId") Long roomId, @Param("userId") Long userId);

    /**
     * Keyword/substring recall over title, content and keywords. ROOM-visible non-archived
     * entries plus the caller's own PRIVATE ones; pinned first. Used by recall_memory and
     * the search API. Pass {@code userId = null} to restrict to ROOM-visible entries.
     */
    @Query("SELECT m FROM MemoryEntry m WHERE m.chatRoomId = :roomId AND m.archived = false AND "
            + "(m.visibility = com.chatapp.entity.MemoryEntry$Visibility.ROOM "
            + "OR m.authorUserId = :userId) AND "
            + "(LOWER(m.title) LIKE LOWER(CONCAT('%', :q, '%')) "
            + "OR LOWER(m.content) LIKE LOWER(CONCAT('%', :q, '%')) "
            + "OR LOWER(COALESCE(m.keywords, '')) LIKE LOWER(CONCAT('%', :q, '%'))) "
            + "ORDER BY m.pinned DESC, m.updatedAt DESC")
    List<MemoryEntry> search(@Param("roomId") Long roomId, @Param("userId") Long userId,
                             @Param("q") String q, Pageable pageable);

    /** Pinned-first, most-recent ROOM-visible (+ own PRIVATE) entries, capped by pageable. */
    @Query("SELECT m FROM MemoryEntry m WHERE m.chatRoomId = :roomId AND m.archived = false AND "
            + "(m.visibility = com.chatapp.entity.MemoryEntry$Visibility.ROOM "
            + "OR m.authorUserId = :userId) "
            + "ORDER BY m.pinned DESC, m.updatedAt DESC")
    List<MemoryEntry> recent(@Param("roomId") Long roomId, @Param("userId") Long userId, Pageable pageable);
}
