package com.chatapp.service;

import com.chatapp.entity.AgentTask;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomMember;
import com.chatapp.entity.MemoryEntry;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentContextBuilder")
class AgentContextBuilderTest {

    @Mock private MessageRepository messageRepository;
    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private MemoryService memoryService;
    @Mock private AgentVisionAttachmentService agentVisionAttachmentService;

    private AgentContextBuilder builder;
    private User alice;
    private User bob;
    private ChatRoom room;
    private BotConfig bot;

    @BeforeEach
    void setUp() {
        builder = new AgentContextBuilder(messageRepository, chatRoomRepository, memoryService, agentVisionAttachmentService);
        lenient().when(memoryService.recall(any(), isNull(), anyString(), anyInt()))
                .thenReturn(List.of());
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
    @DisplayName("generic Agent prompt keeps a lively non-corporate voice")
    void genericAgentPromptKeepsLivelyVoice() {
        bot.setSystemPrompt("You are PM chat's built-in Agent. Do not act like a lifeless support bot. Be candid and playful when the room allows it. Avoid canned phrases like 'as an AI'.");
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(5))).thenReturn(List.of());

        String prompt = builder.assembleSystemPrompt(builder.buildContext(task("guess my IQ")));

        assertTrue(prompt.contains("lively, sharp PM chat participant"));
        assertTrue(prompt.contains("not a corporate help desk"));
        assertTrue(prompt.contains("Avoid sterile phrases like 'as an AI'"));
        assertTrue(prompt.contains("Do not act like a lifeless support bot"));
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
    @DisplayName("default rules allow public figure and policy evaluation")
    void defaultRulesAllowPublicFigureEvaluation() {
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(5)))
                .thenReturn(List.of());

        String prompt = builder.assembleSystemPrompt(builder.buildContext(task("如何评价某位政治人物")));

        assertTrue(prompt.contains("evaluate public figures"));
        assertTrue(prompt.contains("politicians"));
        assertTrue(prompt.contains("public facts"));
        assertTrue(prompt.contains("Do not hide behind phrases like 'as an AI, I have no personal opinion'"));
        assertTrue(prompt.contains("avoid voting persuasion"));
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

    @Test
    @DisplayName("character card persona wins over generic template")
    void characterCardPersonaWinsOverGenericTemplate() {
        bot.setSystemPromptTemplate("generic template should not win");
        bot.setCharacterCardJson("{\"spec\":\"chara_card_v2\"}");
        bot.setCharacterPersona("A fox courier who loves precise deliveries.");
        bot.setCharacterScenario("Guild delivery work in a rainy city.");
        bot.setCharacterSystemPrompt("Stay in character and never mention the system.");
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(5))).thenReturn(List.of());

        AgentContextBuilder.AgentContextEnvelope env = builder.buildContext(task("who are you?"));
        String prompt = builder.assembleSystemPrompt(env);

        assertTrue(prompt.contains("[PERSONA]"));
        assertTrue(prompt.contains("A fox courier"));
        assertTrue(prompt.contains("Guild delivery work"));
        assertTrue(prompt.contains("Stay in character"));
        assertTrue(prompt.contains("[ROOM CONTEXT]"));
        assertFalse(prompt.contains("generic template should not win"));
    }

    @Test
    @DisplayName("bot without character card keeps generic template behavior")
    void botWithoutCharacterCardKeepsTemplateBehavior() {
        bot.setSystemPromptTemplate("Room={{room_name}}; Task={{task}}");
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(5))).thenReturn(List.of());

        String prompt = builder.assembleSystemPrompt(builder.buildContext(task("ping")));

        assertEquals("Room=Context Lab; Task=ping", prompt);
    }

    @Test
    @DisplayName("lore book matches enabled entries by recent message key")
    void loreBookMatchesEnabledEntries() {
        bot.setCharacterCardJson("{\"spec\":\"chara_card_v2\"}");
        bot.setCharacterPersona("Persona");
        bot.setCharacterBookJson("""
                {"entries":[
                  {"keys":["parcel"],"content":"Lore: parcels are sacred","enabled":true,"insertion_order":2},
                  {"keys":["dragon"],"content":"Lore: dragons are asleep","enabled":true,"insertion_order":1},
                  {"keys":["parcel"],"content":"Lore: disabled parcel","enabled":false,"insertion_order":0}
                ]}
                """);
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(5)))
                .thenReturn(List.of(message(1L, bob, "The parcel is late", 1)));

        AgentContextBuilder.AgentContextEnvelope env = builder.buildContext(task("help"));
        String prompt = builder.assembleSystemPrompt(env);

        assertEquals(1, env.loreBook().matched().size());
        assertTrue(prompt.contains("Lore: parcels are sacred"));
        assertFalse(prompt.contains("Lore: dragons are asleep"));
        assertFalse(prompt.contains("disabled parcel"));
    }

    @Test
    @DisplayName("lore book caps matches by insertion order")
    void loreBookCapsMatchesByInsertionOrder() {
        bot.setCharacterCardJson("{\"spec\":\"chara_card_v2\"}");
        bot.setCharacterPersona("Persona");
        StringBuilder entries = new StringBuilder("{\"entries\":[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) entries.append(',');
            entries.append("{\"keys\":[\"parcel\"],\"content\":\"Lore ")
                    .append(i)
                    .append("\",\"enabled\":true,\"insertion_order\":")
                    .append(i)
                    .append('}');
        }
        entries.append("]}");
        bot.setCharacterBookJson(entries.toString());
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(5)))
                .thenReturn(List.of(message(1L, bob, "parcel parcel parcel", 1)));

        AgentContextBuilder.AgentContextEnvelope env = builder.buildContext(task("help"));

        assertEquals(10, env.loreBook().matched().size());
        assertEquals(10, env.loreBook().dropped().size());
        assertEquals("Lore 0", env.loreBook().matched().get(0).content());
        assertEquals("Lore 9", env.loreBook().matched().get(9).content());
    }

    @Test
    @DisplayName("character card exposes post history instructions on envelope")
    void characterCardExposesPostHistoryInstructions() {
        bot.setCharacterCardJson("{\"spec\":\"chara_card_v2\"}");
        bot.setCharacterPersona("Persona");
        bot.setCharacterPostHistoryInstructions("Reply as the courier after reading history.");
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(5))).thenReturn(List.of());

        AgentContextBuilder.AgentContextEnvelope env = builder.buildContext(task("help"));

        assertEquals("Reply as the courier after reading history.",
                env.characterCard().postHistoryInstructions());
    }

    @Test
    @DisplayName("malformed character book is ignored without breaking context")
    void malformedCharacterBookIsIgnored() {
        bot.setCharacterCardJson("{\"spec\":\"chara_card_v2\"}");
        bot.setCharacterPersona("Persona");
        bot.setCharacterBookJson("{bad json");
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(5)))
                .thenReturn(List.of(message(1L, bob, "parcel", 1)));

        AgentContextBuilder.AgentContextEnvelope env = builder.buildContext(task("help"));

        assertTrue(env.loreBook().matched().isEmpty());
        assertTrue(builder.assembleSystemPrompt(env).contains("[LORE BOOK]"));
    }

    @Test
    @DisplayName("lore book scans only the most recent ten history messages")
    void loreBookScansOnlyMostRecentTenMessages() {
        bot.setMaxHistoryMessages(12);
        bot.setCharacterCardJson("{\"spec\":\"chara_card_v2\"}");
        bot.setCharacterPersona("Persona");
        bot.setCharacterBookJson("""
                {"entries":[
                  {"keys":["old-only"],"content":"Lore: old only","enabled":true,"insertion_order":1},
                  {"keys":["fresh"],"content":"Lore: fresh match","enabled":true,"insertion_order":2}
                ]}
                """);
        mockMembers();
        List<Message> history = new ArrayList<>();
        history.add(message(12L, alice, "fresh keyword", 0));
        for (int i = 0; i < 9; i++) {
            history.add(message(3L + i, alice, "ordinary " + i, i + 1));
        }
        history.add(message(2L, bob, "still old-only keyword", 11));
        history.add(message(1L, bob, "old-only keyword", 12));
        when(messageRepository.findRecentMessages(eq(10L), eq(12))).thenReturn(history);

        AgentContextBuilder.AgentContextEnvelope env = builder.buildContext(task("help"));

        assertEquals(1, env.loreBook().matched().size());
        assertEquals("Lore: fresh match", env.loreBook().matched().get(0).content());
    }

    @Test
    @DisplayName("memory section includes pinned entries unconditionally")
    void memorySection_includesPinnedEntriesUnconditionally() {
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(5))).thenReturn(List.of());
        when(memoryService.recall(eq(10L), isNull(), anyString(), eq(10)))
                .thenReturn(List.of(
                        memoryEntry(1L, "Pinned fact", "Pinned context survives", true,
                                MemoryEntry.Visibility.ROOM),
                        memoryEntry(2L, "Normal fact", "Normal context", false,
                                MemoryEntry.Visibility.ROOM)));

        AgentContextBuilder.AgentContextEnvelope env = builder.buildContext(task("help"));
        String prompt = builder.assembleSystemPrompt(env);

        assertTrue(prompt.contains("[MEMORY]"));
        assertTrue(prompt.contains("(pinned) Pinned fact: Pinned context survives"));
        assertEquals(2, env.memoryBook().matched().size());
    }

    @Test
    @DisplayName("memory section includes keyword matched entries")
    void memorySection_includesKeywordMatchedEntries() {
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(5)))
                .thenReturn(List.of(message(1L, bob, "widgets library update", 1)));
        when(memoryService.recall(eq(10L), isNull(), contains("widgets"), eq(10)))
                .thenReturn(List.of(memoryEntry(3L, "Widgets", "Widgets are room context", false,
                        MemoryEntry.Visibility.ROOM)));

        String prompt = builder.assembleSystemPrompt(builder.buildContext(task("help")));

        assertTrue(prompt.contains("Widgets are room context"));
        verify(memoryService).recall(eq(10L), isNull(), contains("widgets"), eq(10));
    }

    @Test
    @DisplayName("memory section passes null user id to service for bot privacy")
    void memorySection_passesNullUserIdToServiceForBotPrivacy() {
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(5))).thenReturn(List.of());

        AgentContextBuilder.AgentContextEnvelope env = builder.buildContext(task("help"));
        String prompt = builder.assembleSystemPrompt(env);

        assertTrue(prompt.contains("[MEMORY]\n(none matched)"));
        verify(memoryService).recall(eq(10L), isNull(), anyString(), eq(10));
    }

    @Test
    @DisplayName("memory token trim protects pinned entries")
    void memorySection_trimmedByTokenBudget_protectsPinned() {
        bot.setMaxContextTokensEstimate(200);
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(5))).thenReturn(List.of());
        List<MemoryEntry> entries = new ArrayList<>();
        entries.add(memoryEntry(1L, "Pinned 1", "pinned one " + "very long memory ".repeat(20),
                true, MemoryEntry.Visibility.ROOM));
        entries.add(memoryEntry(2L, "Pinned 2", "pinned two " + "very long memory ".repeat(20),
                true, MemoryEntry.Visibility.ROOM));
        for (int i = 3; i <= 10; i++) {
            entries.add(memoryEntry((long) i, "Normal " + i,
                    "normal memory " + i + " " + "very long memory ".repeat(20),
                    false, MemoryEntry.Visibility.ROOM));
        }
        when(memoryService.recall(eq(10L), isNull(), anyString(), eq(10))).thenReturn(entries);

        AgentContextBuilder.AgentContextEnvelope env = builder.buildContext(task("help"));

        assertTrue(env.memoryBook().matched().size() < 10);
        assertTrue(env.memoryBook().dropped().size() > 0);
        assertTrue(env.memoryBook().matched().stream().anyMatch(item -> item.id().equals(1L)));
        assertTrue(env.memoryBook().matched().stream().anyMatch(item -> item.id().equals(2L)));
    }

    @Test
    @DisplayName("memory opt-out omits header and avoids recall")
    void memorySection_respectsIncludeMemorySectionFlag_omitsHeaderWhenDisabled() {
        bot.setIncludeMemorySection(false);
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(5))).thenReturn(List.of());

        AgentContextBuilder.AgentContextEnvelope env = builder.buildContext(task("help"));
        String prompt = builder.assembleSystemPrompt(env);

        assertFalse(env.memorySectionEnabled());
        assertFalse(prompt.contains("[MEMORY]"));
        verify(memoryService, never()).recall(any(), any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("system prompt template substitutes {{memory}} placeholder")
    void systemPromptTemplate_substitutesMemoryPlaceholder() {
        bot.setSystemPromptTemplate("Task={{task}}\nMEM:\n{{memory}}");
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(5))).thenReturn(List.of());
        when(memoryService.recall(eq(10L), isNull(), anyString(), eq(10)))
                .thenReturn(List.of(
                        memoryEntry(1L, "Pinned fact", "Pinned context survives", true,
                                MemoryEntry.Visibility.ROOM),
                        memoryEntry(2L, "Normal fact", "Normal context", false,
                                MemoryEntry.Visibility.ROOM)));

        String prompt = builder.assembleSystemPrompt(builder.buildContext(task("note")));

        assertTrue(prompt.contains("Task=note"));
        assertTrue(prompt.contains("(pinned) Pinned fact: Pinned context survives"));
        assertTrue(prompt.contains("Normal fact: Normal context"));
        assertFalse(prompt.contains("{{memory}}"));
    }

    @Test
    @DisplayName("template {{memory}} respects includeMemorySection=false")
    void systemPromptTemplate_memoryPlaceholder_respectsIncludeMemorySectionFlag() {
        bot.setSystemPromptTemplate("Task={{task}}\nMEM:\n{{memory}}");
        bot.setIncludeMemorySection(false);
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(5))).thenReturn(List.of());

        String prompt = builder.assembleSystemPrompt(builder.buildContext(task("note")));

        assertTrue(prompt.contains("Task=note"));
        // {{memory}} replaced with empty string when section disabled — no body, no (none matched).
        assertFalse(prompt.contains("{{memory}}"));
        assertFalse(prompt.contains("(none matched)"));
        verify(memoryService, never()).recall(any(), any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("template substitution does not re-expand tokens embedded in memory content")
    void systemPromptTemplate_memoryContentTokensAreNotReExpanded() {
        bot.setSystemPromptTemplate("MEM:{{memory}} TASK={{task}}");
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(5))).thenReturn(List.of());
        when(memoryService.recall(eq(10L), isNull(), anyString(), eq(10)))
                .thenReturn(List.of(memoryEntry(1L, "Tricky", "remember to {{task}}", false,
                        MemoryEntry.Visibility.ROOM)));

        String prompt = builder.assembleSystemPrompt(builder.buildContext(task("ping")));

        // {{task}} embedded in memory content stays literal (single non-recursive pass);
        // only the template's own {{task}} is replaced.
        assertTrue(prompt.contains("remember to {{task}}"));
        assertTrue(prompt.contains("TASK=ping"));
    }

    @Test
    @DisplayName("private (1-on-1) room uses direct-chat wording, not group room")
    void privateRoomUsesDirectChatWording() {
        room.setRoomType(ChatRoom.RoomType.PRIVATE);
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(5))).thenReturn(List.of());

        String prompt = builder.assembleSystemPrompt(builder.buildContext(task("hi")));

        assertTrue(prompt.contains("one-on-one direct chat"));
        assertFalse(prompt.contains("group room"));
    }

    @Test
    @DisplayName("group room keeps group wording")
    void groupRoomUsesGroupWording() {
        room.setRoomType(ChatRoom.RoomType.GROUP);
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(5))).thenReturn(List.of());

        String prompt = builder.assembleSystemPrompt(builder.buildContext(task("hi")));

        assertTrue(prompt.contains("group room"));
        assertFalse(prompt.contains("one-on-one"));
    }

    @Test
    @DisplayName("mention-only task is instructed to answer the previous relevant message")
    void mentionOnlyTaskUsesPreviousRelevantMessage() {
        room.setRoomType(ChatRoom.RoomType.GROUP);
        mockMembers();
        when(messageRepository.findRecentMessages(eq(10L), eq(5))).thenReturn(List.of(
                message(1L, alice, "为何管理员没有积分", 2),
                message(2L, alice, "@Agent", 1)
        ));

        String prompt = builder.assembleSystemPrompt(builder.buildContext(task("@Agent")));

        assertTrue(prompt.contains("only an @mention"));
        assertTrue(prompt.contains("immediately preceding relevant user message"));
        assertTrue(prompt.contains("为何管理员没有积分"));
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

    private MemoryEntry memoryEntry(Long id, String title, String content, boolean pinned,
                                    MemoryEntry.Visibility visibility) {
        MemoryEntry entry = new MemoryEntry();
        entry.setId(id);
        entry.setChatRoomId(10L);
        entry.setTitle(title);
        entry.setContent(content);
        entry.setVisibility(visibility);
        entry.setPinned(pinned);
        entry.setArchived(false);
        entry.setUpdatedAt(LocalDateTime.of(2026, 6, 1, 9, 0).minusMinutes(id));
        return entry;
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
