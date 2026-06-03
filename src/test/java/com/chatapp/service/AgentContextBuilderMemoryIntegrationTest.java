package com.chatapp.service;

import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomMember;
import com.chatapp.entity.MemoryEntry;
import com.chatapp.entity.User;
import com.chatapp.integration.TestConfig;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MemoryEntryRepository;
import com.chatapp.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "spring.main.allow-circular-references=true",
        "spring.main.allow-bean-definition-overriding=true",
        "server.servlet.context-path="
})
@ActiveProfiles("test")
@Import(TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Agent context memory SQL visibility")
class AgentContextBuilderMemoryIntegrationTest {

    @Autowired private MemoryService memoryService;
    @Autowired private MemoryEntryRepository memoryRepository;
    @Autowired private ChatRoomRepository chatRoomRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    @MockBean private FileStorageService fileStorageService;
    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private LLMService llmService;
    @MockBean private CloudStorageService cloudStorageService;
    @MockBean private AnonymousRerollQuotaService anonymousRerollQuotaService;
    @MockBean private UrlPreviewService urlPreviewService;

    @Test
    @Transactional
    @DisplayName("bot recall path sees ROOM memory only")
    void botRecallPathReturnsRoomOnly() {
        Fixture fixture = createFixture();

        List<MemoryEntry> recalled = memoryService.recall(fixture.roomId(), null, "", 10);
        Set<Long> ids = ids(recalled);

        assertEquals(Set.of(fixture.roomMemory().getId()), ids);
        assertFalse(ids.contains(fixture.ownerPrivateMemory().getId()));
        assertFalse(ids.contains(fixture.peerPrivateMemory().getId()));
    }

    @Test
    @Transactional
    @DisplayName("user recall path sees ROOM and own PRIVATE memory only")
    void userRecallPathReturnsRoomPlusOwnPrivateOnly() {
        Fixture fixture = createFixture();

        List<MemoryEntry> recalled = memoryService.recall(fixture.roomId(), fixture.ownerId(), "", 10);
        Set<Long> ids = ids(recalled);

        assertTrue(ids.contains(fixture.roomMemory().getId()));
        assertTrue(ids.contains(fixture.ownerPrivateMemory().getId()));
        assertFalse(ids.contains(fixture.peerPrivateMemory().getId()));
        assertEquals(2, ids.size());
    }

    private Fixture createFixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User owner = user("mem_owner_" + suffix);
        User peer = user("mem_peer_" + suffix);
        User third = user("mem_third_" + suffix);
        userRepository.saveAll(List.of(owner, peer, third));

        ChatRoom room = new ChatRoom();
        room.setName("Memory SQL Room " + suffix);
        room.setDescription("Memory integration room");
        room.setRoomType(ChatRoom.RoomType.GROUP);
        room.setCreatedBy(owner);
        room.setIsActive(true);
        room.setIsPrivate(false);
        room = chatRoomRepository.save(room);

        persistMember(room, owner, ChatRoomMember.MemberRole.OWNER);
        persistMember(room, peer, ChatRoomMember.MemberRole.MEMBER);
        persistMember(room, third, ChatRoomMember.MemberRole.MEMBER);

        MemoryEntry roomMemory = memoryRepository.save(memory(
                room.getId(),
                owner.getId(),
                "Room memory",
                "Shared room fact",
                MemoryEntry.Visibility.ROOM));
        MemoryEntry ownerPrivateMemory = memoryRepository.save(memory(
                room.getId(),
                owner.getId(),
                "Owner private",
                "Owner-only private fact",
                MemoryEntry.Visibility.PRIVATE));
        MemoryEntry peerPrivateMemory = memoryRepository.save(memory(
                room.getId(),
                peer.getId(),
                "Peer private",
                "Peer-only private fact",
                MemoryEntry.Visibility.PRIVATE));

        entityManager.flush();
        entityManager.clear();

        return new Fixture(
                room.getId(),
                owner.getId(),
                roomMemory,
                ownerPrivateMemory,
                peerPrivateMemory);
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("not-used");
        user.setEmail(username + "@test.com");
        user.setDisplayName(username);
        user.setIsActive(true);
        return user;
    }

    private void persistMember(ChatRoom room, User user, ChatRoomMember.MemberRole role) {
        ChatRoomMember member = new ChatRoomMember();
        member.setChatRoom(room);
        member.setUser(user);
        member.setMemberRole(role);
        member.setIsAdmin(role == ChatRoomMember.MemberRole.OWNER);
        entityManager.persist(member);
    }

    private MemoryEntry memory(
            Long roomId,
            Long authorUserId,
            String title,
            String content,
            MemoryEntry.Visibility visibility) {
        MemoryEntry memory = new MemoryEntry();
        memory.setChatRoomId(roomId);
        memory.setAuthorUserId(authorUserId);
        memory.setSourceType(MemoryEntry.SourceType.USER);
        memory.setVisibility(visibility);
        memory.setTitle(title);
        memory.setContent(content);
        memory.setKeywords(title);
        memory.setPinned(false);
        memory.setArchived(false);
        return memory;
    }

    private Set<Long> ids(List<MemoryEntry> entries) {
        return entries.stream().map(MemoryEntry::getId).collect(Collectors.toSet());
    }

    private record Fixture(
            Long roomId,
            Long ownerId,
            MemoryEntry roomMemory,
            MemoryEntry ownerPrivateMemory,
            MemoryEntry peerPrivateMemory) {
    }
}
