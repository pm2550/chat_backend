package com.chatapp.service;

import com.chatapp.entity.AgentTask;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomMember;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentContextBuilder")
class AgentContextBuilderTest {

    @Mock private MessageRepository messageRepository;
    @Mock private ChatRoomRepository chatRoomRepository;

    private AgentContextBuilder builder;
    private User alice;
    private User bob;
    private ChatRoom room;
    private BotConfig bot;

    @BeforeEach
    void setUp() {
        builder = new AgentContextBuilder(messageRepository, chatRoomRepository);
        alice = user(1L, "alice", "Alice");
        bob = user(2L, "bob", "Bob");
        room = new ChatRoom();
        room.setId(10L);
        room.setName("Context Lab");
        room.setDescription("Project planning");
        room.setAvatarUrl("/rooms/context.png");
        room.setCreatedBy(alice);

        bot = new BotConfig();
        bot.setId(99L);
        bot.setBotName("Agent");
        bot.setBotAvatar("/assets/agent-avatar.png");
        bot.setSystemPrompt("Base identity");
        bot.setLlmProvider(BotConfig.LLMProvider.HERMES);
        bot.setModelName("hermes-agent");
        bot.setMaxHistoryMessages(5);
        bot.setIncludeRoomMetadata(true);
        bot.setMaxContextTokensEstimate(6000);
    }

    @Test
    @DisplayName("builds envelope with requested history size")
    void buildsEnvelopeWithRequestedHistorySize() {
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(5)))
                .thenReturn(messages(5, "history"));

        AgentContextBuilder.AgentContextEnvelope env = builder.buildContext(task("summarize"));

        assertEquals(5, env.conversationHistory().size());
        assertEquals("Context Lab", env.roomMetadata().name());
        assertEquals("Alice", env.initiator().displayName());
    }

    @Test
    @DisplayName("respects max_history_messages from bot config")
    void respectsMaxHistoryMessagesFromBotConfig() {
        bot.setMaxHistoryMessages(3);
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(3)))
                .thenReturn(messages(3, "short"));

        AgentContextBuilder.AgentContextEnvelope env = builder.buildContext(task("summarize"));

        assertEquals(3, env.conversationHistory().size());
    }

    @Test
    @DisplayName("can disable room metadata from bot config")
    void disablesRoomMetadata() {
        bot.setIncludeRoomMetadata(false);
        when(messageRepository.findRecentMessages(eq(10L), eq(5)))
                .thenReturn(messages(2, "metadata-off"));

        AgentContextBuilder.AgentContextEnvelope env = builder.buildContext(task("summarize"));

        assertFalse(env.roomMetadata().included());
        assertEquals(0, env.roomMetadata().memberCount());
        assertTrue(builder.assembleSystemPrompt(env).contains("Room metadata disabled"));
    }

    @Test
    @DisplayName("trims oldest history to respect token budget")
    void trimsHistoryByTokenBudget() {
        bot.setMaxHistoryMessages(200);
        bot.setMaxContextTokensEstimate(500);
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(200)))
                .thenReturn(messages(200, "long message ".repeat(12)));

        AgentContextBuilder.AgentContextEnvelope env = builder.buildContext(task("summarize"));

        assertTrue(env.conversationHistory().size() < 200);
        assertTrue(env.estimatedTokens() <= 500);
    }

    @Test
    @DisplayName("estimates English and Chinese text in expected range")
    void estimatesTokens() {
        assertTrue(builder.estimateTokens("Hello") >= 1);
        assertTrue(builder.estimateTokens("Hello") <= 3);
        assertTrue(builder.estimateTokens("你好") >= 1);
        assertTrue(builder.estimateTokens("你好") <= 3);
    }

    @Test
    @DisplayName("assembled prompt contains all envelope components")
    void assembledPromptContainsEnvelopeComponents() {
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(5)))
                .thenReturn(List.of(message(1L, bob, "Bob prior note", 2)));

        AgentContextBuilder.AgentContextEnvelope env = builder.buildContext(task("what room is this?"));
        String prompt = builder.assembleSystemPrompt(env);

        assertTrue(prompt.contains("[ROLE: Agent identity]"));
        assertTrue(prompt.contains("Agent"));
        assertTrue(prompt.contains("Context Lab"));
        assertTrue(prompt.contains("Members (2): Alice, Bob"));
        assertTrue(prompt.contains("Bob prior note"));
        assertTrue(prompt.contains("Alice (owner"));
        assertTrue(prompt.contains("what room is this?"));
    }

    @Test
    @DisplayName("system prompt template can use envelope variables")
    void systemPromptTemplateCanUseVariables() {
        bot.setSystemPromptTemplate("Room={{room_name}}; Members={{member_names}}; AskedBy={{initiator_display_name}}; Task={{task}}");
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), anyInt()))
                .thenReturn(List.of());

        String prompt = builder.assembleSystemPrompt(builder.buildContext(task("ping")));

        assertTrue(prompt.contains("Room=Context Lab"));
        assertTrue(prompt.contains("Members=Alice, Bob"));
        assertTrue(prompt.contains("AskedBy=Alice"));
        assertTrue(prompt.contains("Task=ping"));
    }

    private AgentTask task(String prompt) {
        AgentTask task = new AgentTask();
        task.setId(77L);
        task.setPrompt(prompt);
        task.setChatRoom(room);
        task.setRequestedBy(alice);
        task.setBotConfig(bot);
        return task;
    }

    private void mockMembers() {
        ChatRoomMember owner = member(alice, ChatRoomMember.MemberRole.OWNER);
        ChatRoomMember member = member(bob, ChatRoomMember.MemberRole.MEMBER);
        when(chatRoomRepository.findMembersByRoomId(10L)).thenReturn(List.of(owner, member));
        when(chatRoomRepository.countChatRoomMembers(10L)).thenReturn(2L);
        when(chatRoomRepository.findMember(10L, 1L)).thenReturn(Optional.of(owner));
    }

    private ChatRoomMember member(User user, ChatRoomMember.MemberRole role) {
        ChatRoomMember member = new ChatRoomMember();
        member.setChatRoom(room);
        member.setUser(user);
        member.setMemberRole(role);
        return member;
    }

    private List<Message> messages(int count, String contentPrefix) {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            messages.add(message((long) i + 1, i % 2 == 0 ? alice : bob, contentPrefix + " " + i, count - i));
        }
        return messages;
    }

    private Message message(Long id, User sender, String content, int minutesAgo) {
        Message message = new Message();
        message.setId(id);
        message.setSender(sender);
        message.setChatRoom(room);
        message.setMessageType(Message.MessageType.TEXT);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.of(2026, 6, 1, 8, 0).minusMinutes(minutesAgo));
        return message;
    }

    private User user(Long id, String username, String displayName) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setEmail(username + "@test.com");
        return user;
    }
}
