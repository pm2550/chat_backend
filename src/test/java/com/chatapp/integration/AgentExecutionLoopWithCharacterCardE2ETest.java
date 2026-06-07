package com.chatapp.integration;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.AgentTask;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomMember;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.AgentTaskRepository;
import com.chatapp.repository.BotConfigRepository;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.AgentContextBuilder;
import com.chatapp.service.AgentExecutionLoop;
import com.chatapp.service.AnonymousRerollQuotaService;
import com.chatapp.service.CloudStorageService;
import com.chatapp.service.LLMService;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.TokenBlacklistService;
import com.chatapp.service.UrlPreviewService;
import com.chatapp.websocket.RawWebSocketHandler;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
                "spring.main.allow-circular-references=true",
                "spring.main.allow-bean-definition-overriding=true",
                "server.servlet.context-path="
        }
)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Agent loop with character card E2E")
class AgentExecutionLoopWithCharacterCardE2ETest {

    @Autowired private AgentContextBuilder agentContextBuilder;
    @Autowired private AgentExecutionLoop agentExecutionLoop;
    @Autowired private AgentTaskRepository agentTaskRepository;
    @Autowired private BotConfigRepository botConfigRepository;
    @Autowired private ChatRoomRepository chatRoomRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    @MockBean private LLMService llmService;
    @MockBean private RawWebSocketHandler rawWebSocketHandler;
    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private CloudStorageService cloudStorageService;
    @MockBean private AnonymousRerollQuotaService anonymousRerollQuotaService;
    @MockBean private UrlPreviewService urlPreviewService;
    @MockBean private RedisConnectionFactory redisConnectionFactory;

    @Test
    @Transactional
    @DisplayName("card persona, lore, and post_history reach LLM through real agent loop")
    void cardPersonaAndPostHistoryReachLlmViaRealAgentLoop() {
        Fixture fixture = createFixture("yes-post", true);
        when(llmService.chat(any(BotConfig.class), anyList(), anyList()))
                .thenReturn(new BotDto.LLMResponse("ack", 11, "mock-model"));

        AgentContextBuilder.AgentContextEnvelope envelope =
                agentContextBuilder.buildContext(fixture.task());
        AgentExecutionLoop.AgentLoopResult result =
                agentExecutionLoop.runLoop(fixture.task(), envelope);

        assertEquals("ack", result.finalContent());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BotDto.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(llmService).chat(eq(fixture.bot()), captor.capture(), anyList());
        List<BotDto.ChatMessage> messages = captor.getValue();

        assertFalse(messages.isEmpty());
        BotDto.ChatMessage system = messages.get(0);
        assertEquals("system", system.getRole());
        assertTrue(system.textContent().contains("Moonlit archivist description"));
        assertTrue(system.textContent().contains("careful, warm, and exacting"));
        assertTrue(system.textContent().contains("Rain library scenario"));
        assertTrue(system.textContent().contains("Lore: aurora parcels must be catalogued"));

        int postHistoryIndex = indexOfContent(messages,
                "After reading history, answer in the archivist voice.");
        int userTaskIndex = indexOfRoleContent(messages, "user", fixture.task().getPrompt());
        assertTrue(postHistoryIndex >= 0, "post_history instruction must be a separate system message");
        assertTrue(userTaskIndex >= 0, "user task must be present");
        assertTrue(postHistoryIndex < userTaskIndex,
                "post_history instruction must appear before the user task message");
    }

    @Test
    @Transactional
    @DisplayName("card without post_history does not inject an extra system message")
    void cardWithoutPostHistoryInstructionsDoesNotInjectExtraSystemMessage() {
        Fixture fixture = createFixture("no-post", false);
        when(llmService.chat(any(BotConfig.class), anyList(), anyList()))
                .thenReturn(new BotDto.LLMResponse("ack", 9, "mock-model"));

        AgentContextBuilder.AgentContextEnvelope envelope =
                agentContextBuilder.buildContext(fixture.task());
        agentExecutionLoop.runLoop(fixture.task(), envelope);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BotDto.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(llmService).chat(eq(fixture.bot()), captor.capture(), anyList());
        List<BotDto.ChatMessage> messages = captor.getValue();

        long systemMessages = messages.stream()
                .filter(message -> "system".equals(message.getRole()))
                .count();
        assertEquals(1, systemMessages, "only the assembled system prompt should be present");
        assertFalse(messages.stream()
                .anyMatch(message -> !message.textContent().isBlank()
                        && message.textContent().contains("After reading history")));
    }

    private Fixture createFixture(String label, boolean includePostHistory) {
        String suffix = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        User owner = user("owner-" + suffix, "Owner " + label);
        User member = user("member-" + suffix, "Member " + label);
        userRepository.saveAll(List.of(owner, member));

        ChatRoom room = new ChatRoom();
        room.setName("Card Loop Room " + label);
        room.setDescription("A room for character-card loop proof");
        room.setRoomType(ChatRoom.RoomType.GROUP);
        room.setCreatedBy(owner);
        room = chatRoomRepository.save(room);

        persistMember(room, owner, ChatRoomMember.MemberRole.OWNER);
        persistMember(room, member, ChatRoomMember.MemberRole.MEMBER);

        BotConfig bot = new BotConfig();
        bot.setBotName("Archivist Bot " + label);
        bot.setLlmProvider(BotConfig.LLMProvider.OLLAMA);
        bot.setModelName("mock-model");
        bot.setCreatedBy(owner);
        bot.setMaxHistoryMessages(20);
        bot.setMaxAgentIterations(4);
        bot.setCharacterCardJson("""
                {"spec":"chara_card_v2","data":{"name":"Archivist"}}
                """);
        bot.setCharacterPersona("Moonlit archivist description\ncareful, warm, and exacting");
        bot.setCharacterScenario("Rain library scenario");
        bot.setCharacterSystemPrompt("Always preserve catalog truth.");
        bot.setCharacterPostHistoryInstructions(includePostHistory
                ? "After reading history, answer in the archivist voice."
                : "");
        bot.setCharacterBookJson("""
                {"entries":[
                  {"keys":["aurora"],"content":"Lore: aurora parcels must be catalogued","enabled":true,"insertion_order":1}
                ]}
                """);
        bot = botConfigRepository.save(bot);

        Message history = new Message();
        history.setChatRoom(room);
        history.setSender(member);
        history.setContent("The aurora parcel arrived before closing.");
        history.setMessageType(Message.MessageType.TEXT);
        messageRepository.save(history);

        AgentTask task = new AgentTask();
        task.setChatRoom(room);
        task.setRequestedBy(owner);
        task.setBotConfig(bot);
        task.setPrompt("Summarize what just happened.");
        task = agentTaskRepository.save(task);

        entityManager.flush();
        entityManager.clear();
        AgentTask hydratedTask = agentTaskRepository.findWithDetailsById(task.getId()).orElseThrow();
        BotConfig hydratedBot = botConfigRepository.findById(bot.getId()).orElseThrow();
        hydratedTask.setBotConfig(hydratedBot);
        return new Fixture(hydratedTask, hydratedBot);
    }

    private void persistMember(ChatRoom room, User user, ChatRoomMember.MemberRole role) {
        ChatRoomMember member = new ChatRoomMember();
        member.setChatRoom(room);
        member.setUser(user);
        member.setMemberRole(role);
        member.setIsAdmin(role == ChatRoomMember.MemberRole.OWNER);
        entityManager.persist(member);
    }

    private User user(String username, String displayName) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@test.example");
        user.setPassword("password123");
        user.setDisplayName(displayName);
        user.setIsActive(true);
        return user;
    }

    private int indexOfContent(List<BotDto.ChatMessage> messages, String content) {
        for (int i = 0; i < messages.size(); i++) {
            if (!messages.get(i).textContent().isBlank()
                    && messages.get(i).textContent().contains(content)) {
                return i;
            }
        }
        return -1;
    }

    private int indexOfRoleContent(List<BotDto.ChatMessage> messages, String role, String content) {
        for (int i = 0; i < messages.size(); i++) {
            BotDto.ChatMessage message = messages.get(i);
            if (role.equals(message.getRole()) && content.equals(message.textContent())) {
                return i;
            }
        }
        return -1;
    }

    private record Fixture(AgentTask task, BotConfig bot) {
    }
}
