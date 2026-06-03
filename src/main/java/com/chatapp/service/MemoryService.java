package com.chatapp.service;

import com.chatapp.entity.MemoryEntry;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MemoryEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Per-room memory library (Phase 5a / F2). Backs both the user-facing memory API and the
 * bot save_memory / recall_memory tools.
 *
 * <p>Authorization model: every user-facing operation requires room membership. A
 * {@link MemoryEntry.Visibility#PRIVATE} entry is reachable only by its author; a
 * {@link MemoryEntry.Visibility#ROOM} entry is shared, collaborative room knowledge any
 * member may view/edit/pin/archive/delete. Bot tools operate on ROOM visibility only
 * (they never read or write a user's private memories).
 */
@Service
@RequiredArgsConstructor
public class MemoryService {

    /** Active (non-archived) entries allowed per room before the owner must archive some. */
    static final int MAX_ACTIVE_PER_ROOM = 1000;
    static final int MAX_TITLE_LEN = 200;
    static final int MAX_CONTENT_LEN = 8000;
    static final int MAX_KEYWORDS_LEN = 500;
    static final int MAX_RECALL = 20;

    private final MemoryEntryRepository memoryRepository;
    private final ChatRoomRepository chatRoomRepository;

    // ---- user-facing (membership-gated) ----

    @Transactional
    public MemoryEntry createForUser(Long roomId, Long userId, String title, String content,
                                     String keywords, MemoryEntry.Visibility visibility) {
        requireMember(roomId, userId);
        enforceCap(roomId);
        MemoryEntry entry = new MemoryEntry();
        entry.setChatRoomId(roomId);
        entry.setAuthorUserId(userId);
        entry.setSourceType(MemoryEntry.SourceType.USER);
        entry.setVisibility(visibility != null ? visibility : MemoryEntry.Visibility.ROOM);
        applyContent(entry, title, content, keywords);
        return memoryRepository.save(entry);
    }

    @Transactional(readOnly = true)
    public List<MemoryEntry> listForUser(Long roomId, Long userId, boolean includeArchived) {
        requireMember(roomId, userId);
        return includeArchived
                ? memoryRepository.findVisibleIncludingArchived(roomId, userId)
                : memoryRepository.findVisible(roomId, userId);
    }

    @Transactional(readOnly = true)
    public List<MemoryEntry> searchForUser(Long roomId, Long userId, String query, int limit) {
        requireMember(roomId, userId);
        return recall(roomId, userId, query, limit);
    }

    @Transactional
    public MemoryEntry update(Long memoryId, Long userId, String title, String content,
                              String keywords, MemoryEntry.Visibility visibility) {
        MemoryEntry entry = requireAccessible(memoryId, userId);
        applyContent(entry,
                title != null ? title : entry.getTitle(),
                content != null ? content : entry.getContent(),
                keywords != null ? keywords : entry.getKeywords());
        if (visibility != null) {
            entry.setVisibility(visibility);
        }
        return memoryRepository.save(entry);
    }

    @Transactional
    public MemoryEntry setPinned(Long memoryId, Long userId, boolean pinned) {
        MemoryEntry entry = requireAccessible(memoryId, userId);
        entry.setPinned(pinned);
        return memoryRepository.save(entry);
    }

    @Transactional
    public MemoryEntry setArchived(Long memoryId, Long userId, boolean archived) {
        MemoryEntry entry = requireAccessible(memoryId, userId);
        entry.setArchived(archived);
        return memoryRepository.save(entry);
    }

    @Transactional
    public void delete(Long memoryId, Long userId) {
        MemoryEntry entry = requireAccessible(memoryId, userId);
        memoryRepository.delete(entry);
    }

    // ---- bot tools (ROOM visibility only) ----

    /** save_memory: a bot persists a durable ROOM fact. No per-user gating — the bot is
     * already acting inside {@code roomId}. */
    @Transactional
    public MemoryEntry saveForBot(Long roomId, Long botConfigId, String title, String content,
                                  String keywords) {
        if (roomId == null) {
            throw new IllegalArgumentException("roomId 不能为空");
        }
        enforceCap(roomId);
        MemoryEntry entry = new MemoryEntry();
        entry.setChatRoomId(roomId);
        entry.setAuthorBotConfigId(botConfigId);
        entry.setSourceType(MemoryEntry.SourceType.BOT);
        entry.setVisibility(MemoryEntry.Visibility.ROOM);
        applyContent(entry, title, content, keywords);
        return memoryRepository.save(entry);
    }

    /**
     * Keyword recall over a room's memories. {@code userId} null restricts to ROOM-visible
     * entries (the bot path); a real member also sees their own PRIVATE entries. A blank
     * query returns pinned-first recent entries.
     */
    @Transactional(readOnly = true)
    public List<MemoryEntry> recall(Long roomId, Long userId, String query, int limit) {
        int lim = Math.max(1, Math.min(limit, MAX_RECALL));
        Pageable page = PageRequest.of(0, lim);
        if (query == null || query.isBlank()) {
            return memoryRepository.recent(roomId, userId, page);
        }
        return memoryRepository.search(roomId, userId, query.trim(), page);
    }

    // ---- helpers ----

    private void requireMember(Long roomId, Long userId) {
        if (userId == null || !chatRoomRepository.isMember(roomId, userId)) {
            throw new AccessDeniedException("不是该聊天室成员");
        }
    }

    private MemoryEntry requireAccessible(Long memoryId, Long userId) {
        MemoryEntry entry = memoryRepository.findById(memoryId)
                .orElseThrow(() -> new IllegalArgumentException("记忆不存在"));
        requireMember(entry.getChatRoomId(), userId);
        if (entry.getVisibility() == MemoryEntry.Visibility.PRIVATE
                && !userId.equals(entry.getAuthorUserId())) {
            throw new AccessDeniedException("无权访问该私有记忆");
        }
        return entry;
    }

    private void enforceCap(Long roomId) {
        if (memoryRepository.countByChatRoomIdAndArchivedFalse(roomId) >= MAX_ACTIVE_PER_ROOM) {
            throw new IllegalStateException(
                    "该聊天室记忆条数已达上限(" + MAX_ACTIVE_PER_ROOM + ")，请先归档部分条目");
        }
    }

    private void applyContent(MemoryEntry entry, String title, String content, String keywords) {
        String t = title == null ? "" : title.trim();
        String c = content == null ? "" : content.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        if (c.isEmpty()) {
            throw new IllegalArgumentException("内容不能为空");
        }
        if (t.length() > MAX_TITLE_LEN) {
            throw new IllegalArgumentException("标题过长（上限 " + MAX_TITLE_LEN + " 字符）");
        }
        if (c.length() > MAX_CONTENT_LEN) {
            throw new IllegalArgumentException("内容过长（上限 " + MAX_CONTENT_LEN + " 字符）");
        }
        String k = keywords == null ? null : keywords.trim();
        if (k != null && k.length() > MAX_KEYWORDS_LEN) {
            k = k.substring(0, MAX_KEYWORDS_LEN);
        }
        entry.setTitle(t);
        entry.setContent(c);
        entry.setKeywords(k == null || k.isEmpty() ? null : k);
    }
}
