package com.chatapp.service;

import com.chatapp.entity.MemoryEntry;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MemoryEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    @Mock private MemoryEntryRepository memoryRepository;
    @Mock private ChatRoomRepository chatRoomRepository;
    @InjectMocks private MemoryService service;

    private static MemoryEntry entry(Long id, Long roomId, MemoryEntry.Visibility vis, Long authorUserId) {
        MemoryEntry m = new MemoryEntry();
        m.setId(id);
        m.setChatRoomId(roomId);
        m.setVisibility(vis);
        m.setAuthorUserId(authorUserId);
        m.setTitle("t");
        m.setContent("c");
        return m;
    }

    // ---- create (user) ----

    @Test
    void createForUserPersistsAsUserRoomEntry() {
        when(chatRoomRepository.isMember(10L, 1L)).thenReturn(true);
        when(memoryRepository.countByChatRoomIdAndArchivedFalse(10L)).thenReturn(0L);
        when(memoryRepository.save(any(MemoryEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        MemoryEntry saved = service.createForUser(10L, 1L, "  Title  ", " body ", "k1 k2", null);

        assertEquals("Title", saved.getTitle());
        assertEquals("body", saved.getContent());
        assertEquals(MemoryEntry.SourceType.USER, saved.getSourceType());
        assertEquals(MemoryEntry.Visibility.ROOM, saved.getVisibility()); // null -> ROOM default
        assertEquals(1L, saved.getAuthorUserId());
    }

    @Test
    void createForUserRejectsNonMember() {
        when(chatRoomRepository.isMember(10L, 99L)).thenReturn(false);
        assertThrows(AccessDeniedException.class,
                () -> service.createForUser(10L, 99L, "t", "c", null, null));
        verify(memoryRepository, never()).save(any());
    }

    @Test
    void createForUserRejectsBlankContent() {
        when(chatRoomRepository.isMember(10L, 1L)).thenReturn(true);
        when(memoryRepository.countByChatRoomIdAndArchivedFalse(10L)).thenReturn(0L);
        assertThrows(IllegalArgumentException.class,
                () -> service.createForUser(10L, 1L, "title", "   ", null, null));
        verify(memoryRepository, never()).save(any());
    }

    @Test
    void createForUserEnforcesPerRoomCap() {
        when(chatRoomRepository.isMember(10L, 1L)).thenReturn(true);
        when(memoryRepository.countByChatRoomIdAndArchivedFalse(10L))
                .thenReturn((long) MemoryService.MAX_ACTIVE_PER_ROOM);
        assertThrows(IllegalStateException.class,
                () -> service.createForUser(10L, 1L, "t", "c", null, null));
        verify(memoryRepository, never()).save(any());
    }

    // ---- save (bot) ----

    @Test
    void saveForBotPersistsAsBotRoomEntry() {
        when(memoryRepository.countByChatRoomIdAndArchivedFalse(10L)).thenReturn(3L);
        when(memoryRepository.save(any(MemoryEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        MemoryEntry saved = service.saveForBot(10L, 5L, "fact", "the sky is blue", null);

        assertEquals(MemoryEntry.SourceType.BOT, saved.getSourceType());
        assertEquals(MemoryEntry.Visibility.ROOM, saved.getVisibility());
        assertEquals(5L, saved.getAuthorBotConfigId());
        assertEquals("the sky is blue", saved.getContent());
    }

    @Test
    void saveForBotRejectsNullRoom() {
        assertThrows(IllegalArgumentException.class,
                () -> service.saveForBot(null, 5L, "t", "c", null));
    }

    // ---- recall ----

    @Test
    void recallBlankQueryUsesRecent() {
        when(memoryRepository.recent(eq(10L), eq(null), any(Pageable.class)))
                .thenReturn(List.of(entry(1L, 10L, MemoryEntry.Visibility.ROOM, null)));

        List<MemoryEntry> out = service.recall(10L, null, "   ", 5);

        assertEquals(1, out.size());
        verify(memoryRepository).recent(eq(10L), eq(null), any(Pageable.class));
        verify(memoryRepository, never()).search(anyLong(), any(), any(), any());
    }

    @Test
    void recallWithQueryUsesSearchAndClampsLimit() {
        when(memoryRepository.search(eq(10L), eq(null), eq("blue"), any(Pageable.class)))
                .thenReturn(List.of());

        service.recall(10L, null, " blue ", 999); // over MAX_RECALL

        verify(memoryRepository).search(eq(10L), eq(null), eq("blue"), any(Pageable.class));
        verify(memoryRepository, never()).recent(anyLong(), any(), any());
    }

    // ---- access control on mutate ----

    @Test
    void updateRejectsNonMember() {
        when(memoryRepository.findById(7L))
                .thenReturn(Optional.of(entry(7L, 10L, MemoryEntry.Visibility.ROOM, 2L)));
        when(chatRoomRepository.isMember(10L, 1L)).thenReturn(false);
        assertThrows(AccessDeniedException.class,
                () -> service.update(7L, 1L, "x", "y", null, null));
    }

    @Test
    void privateEntryIsNotAccessibleByNonAuthorMember() {
        when(memoryRepository.findById(7L))
                .thenReturn(Optional.of(entry(7L, 10L, MemoryEntry.Visibility.PRIVATE, 2L)));
        when(chatRoomRepository.isMember(10L, 1L)).thenReturn(true); // member, but not the author
        assertThrows(AccessDeniedException.class, () -> service.delete(7L, 1L));
        verify(memoryRepository, never()).delete(any());
    }

    @Test
    void privateEntryIsAccessibleByItsAuthor() {
        MemoryEntry e = entry(7L, 10L, MemoryEntry.Visibility.PRIVATE, 1L);
        when(memoryRepository.findById(7L)).thenReturn(Optional.of(e));
        when(chatRoomRepository.isMember(10L, 1L)).thenReturn(true);
        when(memoryRepository.save(any(MemoryEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        MemoryEntry pinned = service.setPinned(7L, 1L, true);
        assertTrue(pinned.getPinned());
    }

    @Test
    void roomEntryIsEditableByAnyMember() {
        MemoryEntry e = entry(7L, 10L, MemoryEntry.Visibility.ROOM, 2L); // authored by user 2
        when(memoryRepository.findById(7L)).thenReturn(Optional.of(e));
        when(chatRoomRepository.isMember(10L, 1L)).thenReturn(true); // a different member
        when(memoryRepository.save(any(MemoryEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        MemoryEntry updated = service.update(7L, 1L, "new title", null, null, null);
        assertEquals("new title", updated.getTitle());
        assertEquals("c", updated.getContent()); // null content keeps the original
    }

    @Test
    void updateRejectsMissingEntry() {
        when(memoryRepository.findById(404L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.update(404L, 1L, "x", "y", null, null));
    }
}
