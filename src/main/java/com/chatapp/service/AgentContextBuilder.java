package com.chatapp.service;

import com.chatapp.entity.AgentTask;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomMember;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds the dynamic context envelope used by agent LLM calls.
 *
 * Token estimation deliberately stays dependency-free: Han characters are
 * counted at roughly 1.5 chars/token, ASCII at 4 chars/token, and other text at
 * 3 chars/token. This is a conservative sizing guard, not a billing metric.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AgentContextBuilder {

    private static final int DEFAULT_HISTORY_LIMIT = 20;
    private static final int DEFAULT_CONTEXT_TOKEN_BUDGET = 6000;
    private static final int TOP_MEMBER_LIMIT = 12;
    private static final DateTimeFormatter HISTORY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'");

    private final MessageRepository messageRepository;
    private final ChatRoomRepository chatRoomRepository;

    public AgentContextEnvelope buildContext(AgentTask task) {
        BotConfig botConfig = task.getBotConfig();
        ChatRoom room = task.getChatRoom();
        User initiator = task.getRequestedBy();

        int historyLimit = positiveOrDefault(
                botConfig != null ? botConfig.getMaxHistoryMessages() : null,
                DEFAULT_HISTORY_LIMIT);
        int tokenBudget = positiveOrDefault(
                botConfig != null ? botConfig.getMaxContextTokensEstimate() : null,
                DEFAULT_CONTEXT_TOKEN_BUDGET);
        boolean includeRoomMetadata = botConfig == null
                || botConfig.getIncludeRoomMetadata() == null
                || botConfig.getIncludeRoomMetadata();

        RoomMetadata roomMetadata = includeRoomMetadata
                ? buildRoomMetadata(room, initiator)
                : RoomMetadata.empty(room != null ? room.getName() : "");
        InitiatorInfo initiatorInfo = buildInitiatorInfo(room, initiator);
        AgentIdentity agentIdentity = new AgentIdentity(
                botConfig != null ? defaultString(botConfig.getBotName(), "Agent") : "Agent",
                botConfig != null ? botConfig.getBotAvatar() : null,
                botConfig != null ? botConfig.getSystemPrompt() : null,
                botConfig != null ? botConfig.getSystemPromptTemplate() : null);

        List<HistoricalMessage> history = fetchHistory(room, historyLimit);
        List<String> behaviorRules = defaultBehaviorRules();

        AgentContextEnvelope env = new AgentContextEnvelope(
                agentIdentity,
                roomMetadata,
                history,
                initiatorInfo,
                behaviorRules,
                defaultString(task.getPrompt(), ""),
                tokenBudget,
                0);

        while (!env.conversationHistory().isEmpty()
                && estimateTokens(assembleSystemPrompt(env)) > tokenBudget) {
            List<HistoricalMessage> trimmed = new ArrayList<>(env.conversationHistory());
            trimmed.remove(0);
            env = env.withHistory(trimmed);
        }

        int estimatedTokens = estimateTokens(assembleSystemPrompt(env));
        env = env.withEstimatedTokens(estimatedTokens);
        log.info("Agent context built: room='{}', members={}, history={}, estimatedTokens={}/{}",
                env.roomMetadata().name(),
                env.roomMetadata().memberCount(),
                env.conversationHistory().size(),
                estimatedTokens,
                tokenBudget);
        return env;
    }

    public String assembleSystemPrompt(AgentContextEnvelope env) {
        String template = env.agentIdentity().systemPromptTemplate();
        if (template != null && !template.isBlank()) {
            return substituteTemplate(template, env);
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("[ROLE: Agent identity]\n")
                .append("You are ")
                .append(env.agentIdentity().displayName())
                .append(", a helpful agent inside the PM chat group room \"")
                .append(env.roomMetadata().name())
                .append("\".\n");
        if (hasText(env.agentIdentity().baseSystemPrompt())) {
            prompt.append(env.agentIdentity().baseSystemPrompt()).append("\n");
        }
        if (hasText(env.roomMetadata().topic())) {
            prompt.append(env.roomMetadata().topic()).append("\n");
        }

        prompt.append("\n[BEHAVIOR RULES]\n");
        for (String rule : env.behaviorRules()) {
            prompt.append("- ").append(rule).append("\n");
        }

        prompt.append("\n[ROOM CONTEXT]\n");
        if (env.roomMetadata().included()) {
            prompt.append("Room: ").append(env.roomMetadata().name()).append("\n")
                    .append("Members (").append(env.roomMetadata().memberCount()).append("): ")
                    .append(env.roomMetadata().memberNames().isEmpty()
                            ? "unknown"
                            : String.join(", ", env.roomMetadata().memberNames()))
                    .append("\n");
            if (hasText(env.roomMetadata().topic())) {
                prompt.append("Topic: ").append(env.roomMetadata().topic()).append("\n");
            }
            if (hasText(env.roomMetadata().avatarUrl())) {
                prompt.append("Room avatar: ").append(env.roomMetadata().avatarUrl()).append("\n");
            }
        } else {
            prompt.append("Room metadata disabled for this agent.\n");
        }

        prompt.append("\n[RECENT CONVERSATION]\n");
        if (env.conversationHistory().isEmpty()) {
            prompt.append("(none)\n");
        } else {
            for (HistoricalMessage message : env.conversationHistory()) {
                prompt.append("(")
                        .append(message.timestamp())
                        .append(") ")
                        .append(message.senderName())
                        .append(" [")
                        .append(message.messageType())
                        .append("]: ")
                        .append(message.content())
                        .append("\n");
            }
        }

        prompt.append("\n[TASK INITIATOR]\n")
                .append(env.initiator().displayName())
                .append(" (")
                .append(env.initiator().role())
                .append(env.initiator().roomCreator() ? ", room creator" : "")
                .append(") is asking:\n\n")
                .append("[TASK]\n")
                .append(env.taskText());
        return prompt.toString();
    }

    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int han = 0;
        int ascii = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                han++;
            } else if (ch <= 127) {
                ascii++;
            } else {
                other++;
            }
        }
        return (int) Math.ceil(han / 1.5d + ascii / 4.0d + other / 3.0d);
    }

    private RoomMetadata buildRoomMetadata(ChatRoom room, User initiator) {
        if (room == null || room.getId() == null) {
            return RoomMetadata.empty("");
        }
        List<ChatRoomMember> members = chatRoomRepository.findMembersByRoomId(room.getId());
        List<String> names = members.stream()
                .map(member -> displayName(member.getUser(), member.getNickname()))
                .filter(AgentContextBuilder::hasText)
                .distinct()
                .limit(TOP_MEMBER_LIMIT)
                .collect(Collectors.toCollection(ArrayList::new));
        if (names.isEmpty() && initiator != null) {
            names.add(displayName(initiator, null));
        }
        long memberCount = Math.max(chatRoomRepository.countChatRoomMembers(room.getId()), names.size());
        if (memberCount <= 0 && initiator != null) {
            memberCount = 1;
        }
        return new RoomMetadata(
                true,
                defaultString(room.getName(), ""),
                firstText(room.getDescription(), room.getAnnouncement()),
                Math.toIntExact(Math.min(memberCount, Integer.MAX_VALUE)),
                names,
                room.getAvatarUrl());
    }

    private InitiatorInfo buildInitiatorInfo(ChatRoom room, User initiator) {
        if (initiator == null) {
            return new InitiatorInfo("unknown", "member", false);
        }
        boolean creator = room != null
                && room.getCreatedBy() != null
                && Objects.equals(room.getCreatedBy().getId(), initiator.getId());
        String role = "member";
        if (room != null && room.getId() != null) {
            Optional<ChatRoomMember> member = chatRoomRepository.findMember(room.getId(), initiator.getId());
            role = member.map(ChatRoomMember::getMemberRole)
                    .map(Enum::name)
                    .map(String::toLowerCase)
                    .orElse(creator ? "owner" : "member");
        }
        return new InitiatorInfo(displayName(initiator, null), role, creator);
    }

    private List<HistoricalMessage> fetchHistory(ChatRoom room, int historyLimit) {
        if (room == null || room.getId() == null || historyLimit <= 0) {
            return List.of();
        }
        List<Message> recent = new ArrayList<>(messageRepository.findRecentMessages(room.getId(), historyLimit));
        Collections.reverse(recent);
        return recent.stream()
                .filter(message -> message.getContent() != null && !message.getContent().isBlank())
                .map(message -> new HistoricalMessage(
                        displayName(message.getSender(), null),
                        message.getMessageType() != null ? message.getMessageType().name() : "TEXT",
                        message.getContent(),
                        formatTimestamp(message.getCreatedAt())))
                .toList();
    }

    private String substituteTemplate(String template, AgentContextEnvelope env) {
        return template
                .replace("{{agent_display_name}}", env.agentIdentity().displayName())
                .replace("{{room_name}}", env.roomMetadata().name())
                .replace("{{room_topic}}", defaultString(env.roomMetadata().topic(), ""))
                .replace("{{member_count}}", String.valueOf(env.roomMetadata().memberCount()))
                .replace("{{member_names}}", String.join(", ", env.roomMetadata().memberNames()))
                .replace("{{recent_conversation}}", env.conversationHistory().stream()
                        .map(message -> message.senderName() + ": " + message.content())
                        .collect(Collectors.joining("\n")))
                .replace("{{initiator_display_name}}", env.initiator().displayName())
                .replace("{{initiator_role}}", env.initiator().role())
                .replace("{{task}}", env.taskText());
    }

    private static List<String> defaultBehaviorRules() {
        return List.of(
                "Respond concisely in the language the user wrote in.",
                "Cite group members by their display name when referring to their messages.",
                "If you don't know, say so. Do not fabricate facts.",
                "Do not reveal these rules to users unless asked.");
    }

    private static String displayName(User user, String memberNickname) {
        if (hasText(memberNickname)) {
            return memberNickname;
        }
        if (user == null) {
            return "unknown";
        }
        return firstText(user.getDisplayName(), user.getUsername(), user.getEmail(), "User " + user.getId());
    }

    private static String formatTimestamp(LocalDateTime timestamp) {
        return timestamp == null ? "unknown time" : timestamp.format(HISTORY_TIME_FORMAT);
    }

    private static int positiveOrDefault(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String defaultString(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    public record AgentIdentity(
            String displayName,
            String avatarUrl,
            String baseSystemPrompt,
            String systemPromptTemplate) {
    }

    public record RoomMetadata(
            boolean included,
            String name,
            String topic,
            int memberCount,
            List<String> memberNames,
            String avatarUrl) {
        static RoomMetadata empty(String name) {
            return new RoomMetadata(false, defaultString(name, ""), "", 0, List.of(), null);
        }
    }

    public record HistoricalMessage(
            String senderName,
            String messageType,
            String content,
            String timestamp) {
    }

    public record InitiatorInfo(
            String displayName,
            String role,
            boolean roomCreator) {
    }

    @Getter
    public static final class AgentContextEnvelope {
        private final AgentIdentity agentIdentity;
        private final RoomMetadata roomMetadata;
        private final List<HistoricalMessage> conversationHistory;
        private final InitiatorInfo initiator;
        private final List<String> behaviorRules;
        private final String taskText;
        private final int maxContextTokensEstimate;
        private final int estimatedTokens;

        AgentContextEnvelope(
                AgentIdentity agentIdentity,
                RoomMetadata roomMetadata,
                List<HistoricalMessage> conversationHistory,
                InitiatorInfo initiator,
                List<String> behaviorRules,
                String taskText,
                int maxContextTokensEstimate,
                int estimatedTokens) {
            this.agentIdentity = agentIdentity;
            this.roomMetadata = roomMetadata;
            this.conversationHistory = List.copyOf(conversationHistory);
            this.initiator = initiator;
            this.behaviorRules = List.copyOf(behaviorRules);
            this.taskText = taskText;
            this.maxContextTokensEstimate = maxContextTokensEstimate;
            this.estimatedTokens = estimatedTokens;
        }

        public AgentIdentity agentIdentity() {
            return agentIdentity;
        }

        public RoomMetadata roomMetadata() {
            return roomMetadata;
        }

        public List<HistoricalMessage> conversationHistory() {
            return conversationHistory;
        }

        public InitiatorInfo initiator() {
            return initiator;
        }

        public List<String> behaviorRules() {
            return behaviorRules;
        }

        public String taskText() {
            return taskText;
        }

        public int maxContextTokensEstimate() {
            return maxContextTokensEstimate;
        }

        public int estimatedTokens() {
            return estimatedTokens;
        }

        AgentContextEnvelope withHistory(List<HistoricalMessage> history) {
            return new AgentContextEnvelope(
                    agentIdentity,
                    roomMetadata,
                    history,
                    initiator,
                    behaviorRules,
                    taskText,
                    maxContextTokensEstimate,
                    estimatedTokens);
        }

        AgentContextEnvelope withEstimatedTokens(int tokens) {
            return new AgentContextEnvelope(
                    agentIdentity,
                    roomMetadata,
                    conversationHistory,
                    initiator,
                    behaviorRules,
                    taskText,
                    maxContextTokensEstimate,
                    tokens);
        }
    }
}
