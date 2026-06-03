package com.chatapp.repository;

import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomMember;
import com.chatapp.entity.User;
import com.chatapp.integration.TestConfig;
import com.chatapp.service.AnonymousRerollQuotaService;
import com.chatapp.service.CloudStorageService;
import com.chatapp.service.FileStorageService;
import com.chatapp.service.LLMService;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.TokenBlacklistService;
import com.chatapp.service.UrlPreviewService;
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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Item 5 — SQL-level proof of the is_muted split. The dual-write and the column-isolation
 * live inside the repository @Query strings, which a Mockito service test cannot exercise.
 * Runs against H2 (ddl-auto:create-drop builds is_bot_muted / is_notification_muted from
 * the entity). The @Modifying queries do not clearAutomatically, so each test flushes +
 * clears the persistence context before re-reading to observe the real DB state.
 *
 * Uses the project's full @SpringBootTest bootstrap (mirroring the integration tests) rather
 * than @DataJpaTest because ChatAppApplication wires service beans that a JPA slice cannot
 * satisfy.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "spring.main.allow-circular-references=true",
        "spring.main.allow-bean-definition-overriding=true",
        "server.servlet.context-path="
})
@ActiveProfiles("test")
@Import(TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("is_muted split — SQL dual-write")
class ChatRoomMemberMuteRepositoryTest {

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

    private long[] seed(boolean muted, boolean botMuted, boolean notifMuted) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User u = new User();
        u.setUsername("mute_" + suffix);
        u.setPassword("not-used");
        u.setEmail("mute_" + suffix + "@test.com");
        u.setDisplayName("mute_" + suffix);
        u.setIsActive(true);
        u = userRepository.save(u);

        ChatRoom room = new ChatRoom();
        room.setName("Mute Room " + suffix);
        room.setRoomType(ChatRoom.RoomType.GROUP);
        room.setCreatedBy(u);
        room.setIsActive(true);
        room.setIsPrivate(false);
        room = chatRoomRepository.save(room);

        ChatRoomMember m = new ChatRoomMember();
        m.setUser(u);
        m.setChatRoom(room);
        m.setMemberRole(ChatRoomMember.MemberRole.MEMBER);
        m.setIsMuted(muted);
        m.setIsBotMuted(botMuted);
        m.setIsNotificationMuted(notifMuted);
        entityManager.persist(m);
        entityManager.flush();
        entityManager.clear();
        return new long[]{room.getId(), u.getId()};
    }

    @Test
    @Transactional
    void toggleMuteStatus_setsBothIsBotMutedAndIsMuted() {
        long[] ids = seed(false, false, false);
        long roomId = ids[0], userId = ids[1];

        chatRoomRepository.toggleMuteStatus(roomId, userId);
        entityManager.flush();
        entityManager.clear();

        ChatRoomMember after = chatRoomRepository.findMember(roomId, userId).orElseThrow();
        assertTrue(after.getIsMuted(), "legacy is_muted shadow flips");
        assertTrue(after.getIsBotMuted(), "is_bot_muted flips coherently");
        assertFalse(after.getIsNotificationMuted(), "moderation toggle never touches notification mute");
    }

    @Test
    @Transactional
    void updateNotificationSettings_doesNotTouchIsBotMuted() {
        long[] ids = seed(false, false, false);
        long roomId = ids[0], userId = ids[1];

        chatRoomRepository.updateNotificationMuted(roomId, userId, true);
        entityManager.flush();
        entityManager.clear();

        ChatRoomMember after = chatRoomRepository.findMember(roomId, userId).orElseThrow();
        assertTrue(after.getIsNotificationMuted(), "notification mute is set");
        assertFalse(after.getIsBotMuted(), "send-block flag stays clear — this is the bug fix");
        assertFalse(after.getIsMuted(), "legacy shadow not written by the notification path");
    }

    @Test
    @Transactional
    void botModerationMuteTool_stillBlocksSending() {
        long[] ids = seed(false, false, false);
        long roomId = ids[0], userId = ids[1];

        // The bot moderation tool path explicitly sets the moderation mute.
        chatRoomRepository.setMemberMuted(roomId, userId, true);
        entityManager.flush();
        entityManager.clear();

        // validateCanSendMessage reads isBotMuted — it must report true (sending blocked).
        assertTrue(chatRoomRepository.isBotMuted(roomId, userId), "send-block gate sees the bot mute");
        ChatRoomMember after = chatRoomRepository.findMember(roomId, userId).orElseThrow();
        assertTrue(after.getIsBotMuted());
        assertTrue(after.getIsMuted(), "legacy shadow dual-written");
        assertFalse(after.getIsNotificationMuted());
    }

    @Test
    @Transactional
    void isBotMuted_readsOnlyBotMuteColumn() {
        // A member who only self-muted notifications must NOT be reported as send-blocked.
        long[] ids = seed(false, false, true);
        long roomId = ids[0], userId = ids[1];

        assertFalse(chatRoomRepository.isBotMuted(roomId, userId));
    }
}
