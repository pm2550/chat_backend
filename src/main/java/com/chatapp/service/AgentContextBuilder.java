package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.AgentTask;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomMember;
import com.chatapp.entity.MemoryEntry;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final int LORE_SCAN_HISTORY_LIMIT = 10;
    private static final int LORE_ENTRY_LIMIT = 10;
    private static final int MEMORY_ENTRY_LIMIT = 10;
    private static final int MEMORY_SCAN_HISTORY_LIMIT = 10;
    private static final int MEMORY_RECALL_MIN_QUERY_LEN = 2;
    private static final DateTimeFormatter HISTORY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'");

    private final MessageRepository messageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final MemoryService memoryService;
    private final AgentVisionAttachmentService agentVisionAttachmentService;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        boolean includeMemorySection = botConfig == null
                || botConfig.getIncludeMemorySection() == null
                || botConfig.getIncludeMemorySection();

        RoomMetadata roomMetadata = includeRoomMetadata
                ? buildRoomMetadata(room, initiator)
                : RoomMetadata.empty(room != null ? room.getName() : "",
                        room == null || room.getRoomType() != ChatRoom.RoomType.PRIVATE);
        InitiatorInfo initiatorInfo = buildInitiatorInfo(room, initiator);
        AgentIdentity agentIdentity = new AgentIdentity(
                botConfig != null ? defaultString(botConfig.getBotName(), "Agent") : "Agent",
                botConfig != null ? botConfig.getBotAvatar() : null,
                botConfig != null ? botConfig.getSystemPrompt() : null,
                botConfig != null ? botConfig.getSystemPromptTemplate() : null);

        List<HistoricalMessage> history = fetchHistory(room, historyLimit);
        List<String> behaviorRules = defaultBehaviorRules();
        CharacterCardSection card = buildCharacterCardSection(botConfig);
        LoreBookSection loreBook = matchLoreBook(botConfig, history, LORE_ENTRY_LIMIT);
        MemoryBookSection memoryBook = includeMemorySection
                ? recallMemoriesForContext(room != null ? room.getId() : null, history, task)
                : MemoryBookSection.empty();

        AgentContextEnvelope env = new AgentContextEnvelope(
                agentIdentity,
                roomMetadata,
                history,
                initiatorInfo,
                behaviorRules,
                card,
                loreBook,
                memoryBook,
                includeMemorySection,
                defaultString(task.getPrompt(), ""),
                tokenBudget,
                0);

        while (!env.conversationHistory().isEmpty()
                && estimateTokens(assembleSystemPrompt(env)) > tokenBudget) {
            List<HistoricalMessage> trimmed = new ArrayList<>(env.conversationHistory());
            trimmed.remove(0);
            env = env.withHistory(trimmed);
        }

        env = trimLoreToBudget(env, tokenBudget);
        env = trimMemoryToBudget(env, tokenBudget);

        int estimatedTokens = estimateTokens(assembleSystemPrompt(env));
        env = env.withEstimatedTokens(estimatedTokens);
        log.info("Agent context built: room='{}', members={}, history={}, loreMatched={}, loreDropped={}, memoryMatched={}, memoryDropped={}, estimatedTokens={}/{}",
                env.roomMetadata().name(),
                env.roomMetadata().memberCount(),
                env.conversationHistory().size(),
                env.loreBook().matched().size(),
                env.loreBook().dropped().size(),
                env.memoryBook().matched().size(),
                env.memoryBook().dropped().size(),
                estimatedTokens,
                tokenBudget);
        return env;
    }

    public String assembleSystemPrompt(AgentContextEnvelope env) {
        String template = env.agentIdentity().systemPromptTemplate();
        if (!env.characterCard().hasCard() && template != null && !template.isBlank()) {
            return substituteTemplate(template, env);
        }

        StringBuilder prompt = new StringBuilder();
        if (env.characterCard().hasCard()) {
            // Character-card persona wins over system_prompt_template because the
            // template is a generic room envelope, while the card is the bot's
            // explicit identity. We still compose the room envelope below.
            prompt.append("[PERSONA]\n");
            appendIfPresent(prompt, env.characterCard().persona());
            appendIfPresent(prompt, env.characterCard().scenario());
            appendIfPresent(prompt, env.characterCard().systemPrompt());
        } else {
            prompt.append("[ROLE: Agent identity]\n")
                    .append("You are ")
                    .append(env.agentIdentity().displayName());
            if (env.roomMetadata().group()) {
                prompt.append(", a helpful agent inside the PM chat group room \"")
                        .append(env.roomMetadata().name())
                        .append("\".\n");
            } else {
                prompt.append(", a helpful assistant in a one-on-one direct chat with \"")
                        .append(env.roomMetadata().name())
                        .append("\".\n");
            }
            if (hasText(env.agentIdentity().baseSystemPrompt())) {
                prompt.append(env.agentIdentity().baseSystemPrompt()).append("\n");
            }
            if (hasText(env.roomMetadata().topic())) {
                prompt.append(env.roomMetadata().topic()).append("\n");
            }
        }

        prompt.append("\n[BEHAVIOR RULES]\n");
        for (String rule : env.behaviorRules()) {
            prompt.append("- ").append(rule).append("\n");
        }

        if (env.characterCard().hasCard()) {
            prompt.append("\n[LORE BOOK]\n");
            if (env.loreBook().matched().isEmpty()) {
                prompt.append("(none matched)\n");
            } else {
                for (LoreBookEntry entry : env.loreBook().matched()) {
                    prompt.append("- ").append(entry.content()).append("\n");
                }
            }
        }

        if (env.memorySectionEnabled()) {
            // Header stays inline so the extracted helper can be reused by
            // substituteTemplate's {{memory}} placeholder without forcing a header.
            prompt.append("\n[MEMORY]\n");
            prompt.append(formatMemoryBlock(env));
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
                room.getAvatarUrl(),
                room.getRoomType() != ChatRoom.RoomType.PRIVATE);
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

        boolean[] includeBinaryImage = new boolean[recent.size()];
        int imagesIncluded = 0;
        for (int i = recent.size() - 1; i >= 0; i--) {
            Message message = recent.get(i);
            if (agentVisionAttachmentService.isImageMessage(message)
                    && imagesIncluded < AgentVisionAttachmentService.MAX_HISTORY_IMAGES) {
                includeBinaryImage[i] = true;
                imagesIncluded++;
            }
        }

        List<HistoricalMessage> history = new ArrayList<>();
        for (int i = 0; i < recent.size(); i++) {
            Message message = recent.get(i);
            boolean isImage = agentVisionAttachmentService.isImageMessage(message);
            String content = message.getContent() != null ? message.getContent() : "";
            AgentVisionAttachmentService.ImageContext imageContext = isImage
                    ? agentVisionAttachmentService.resolve(message, includeBinaryImage[i])
                    : AgentVisionAttachmentService.ImageContext.empty();
            if (imageContext.annotation() != null && !imageContext.annotation().isBlank()) {
                content = content.isBlank() ? imageContext.annotation() : content + " " + imageContext.annotation();
            }
            if (content.isBlank() && imageContext.attachments().isEmpty()) {
                continue;
            }
            history.add(new HistoricalMessage(
                    displayName(message.getSender(), null),
                    message.getMessageType() != null ? message.getMessageType().name() : "TEXT",
                    content,
                    formatTimestamp(message.getCreatedAt()),
                    imageContext.attachments()));
        }
        return history;
    }

    private static final Pattern TEMPLATE_TOKEN = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    private String substituteTemplate(String template, AgentContextEnvelope env) {
        Map<String, String> values = Map.ofEntries(
                Map.entry("agent_display_name", env.agentIdentity().displayName()),
                Map.entry("room_name", env.roomMetadata().name()),
                Map.entry("room_topic", defaultString(env.roomMetadata().topic(), "")),
                Map.entry("member_count", String.valueOf(env.roomMetadata().memberCount())),
                Map.entry("member_names", String.join(", ", env.roomMetadata().memberNames())),
                Map.entry("recent_conversation", env.conversationHistory().stream()
                        .map(message -> message.senderName() + ": " + message.content())
                        .collect(Collectors.joining("\n"))),
                Map.entry("initiator_display_name", env.initiator().displayName()),
                Map.entry("initiator_role", env.initiator().role()),
                Map.entry("memory", env.memorySectionEnabled() ? formatMemoryBlock(env) : ""),
                Map.entry("task", env.taskText()));
        // Single non-recursive pass: each placeholder is resolved exactly once, so a token
        // that appears *inside* substituted content (e.g. memory text literally containing
        // "{{task}}") is never re-expanded. Unknown tokens are left verbatim.
        Matcher matcher = TEMPLATE_TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = values.containsKey(key) ? values.get(key) : matcher.group(0);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Renders the [MEMORY] body (no "[MEMORY]" header) so both the inline
     * assembleSystemPrompt path and the substituteTemplate {{memory}} placeholder
     * share one format. Returns "(none matched)\n" when no memories matched, mirroring
     * the inline path's empty branch.
     */
    private String formatMemoryBlock(AgentContextEnvelope env) {
        List<MemoryItem> memories = env.memoryBook().matched();
        if (memories.isEmpty()) {
            return "(none matched)\n";
        }
        StringBuilder block = new StringBuilder();
        for (MemoryItem item : memories) {
            block.append("- ");
            if (item.pinned()) {
                block.append("(pinned) ");
            }
            if (hasText(item.title())) {
                block.append(item.title()).append(": ");
            }
            block.append(item.content() == null ? "" : item.content()).append("\n");
        }
        return block.toString();
    }

    private CharacterCardSection buildCharacterCardSection(BotConfig bot) {
        if (bot == null || !hasText(bot.getCharacterCardJson())) {
            return CharacterCardSection.empty();
        }
        return new CharacterCardSection(
                true,
                firstText(bot.getCharacterPersona()),
                firstText(bot.getCharacterScenario()),
                firstText(bot.getCharacterSystemPrompt()),
                firstText(bot.getCharacterPostHistoryInstructions()));
    }

    private LoreBookSection matchLoreBook(BotConfig bot, List<HistoricalMessage> history, int maxEntries) {
        if (bot == null || !hasText(bot.getCharacterBookJson()) || history.isEmpty()) {
            return LoreBookSection.empty();
        }
        List<String> haystack = history.stream()
                .skip(Math.max(0, history.size() - LORE_SCAN_HISTORY_LIMIT))
                .map(HistoricalMessage::content)
                .filter(AgentContextBuilder::hasText)
                .map(String::toLowerCase)
                .toList();
        if (haystack.isEmpty()) {
            return LoreBookSection.empty();
        }

        List<LoreBookEntry> matched = new ArrayList<>();
        try {
            JsonNode entries = readJsonMaybeQuoted(bot.getCharacterBookJson()).path("entries");
            if (!entries.isArray()) {
                return LoreBookSection.empty();
            }
            int fallbackOrder = 0;
            for (JsonNode entry : entries) {
                if (entry.has("enabled") && !entry.path("enabled").asBoolean()) {
                    continue;
                }
                String content = entry.path("content").asText("");
                if (!hasText(content)) {
                    continue;
                }
                if (keysMatch(entry.path("keys"), haystack)) {
                    int insertionOrder = entry.has("insertion_order")
                            ? entry.path("insertion_order").asInt(fallbackOrder)
                            : fallbackOrder;
                    matched.add(new LoreBookEntry(insertionOrder, content));
                }
                fallbackOrder++;
            }
        } catch (Exception e) {
            log.warn("Failed to parse character book for bot {}: {}",
                    bot.getId(), e.getMessage());
            return LoreBookSection.empty();
        }

        matched.sort((left, right) -> Integer.compare(left.insertionOrder(), right.insertionOrder()));
        if (matched.size() <= maxEntries) {
            return new LoreBookSection(List.copyOf(matched), List.of());
        }
        List<LoreBookEntry> kept = new ArrayList<>(matched.subList(0, maxEntries));
        List<LoreBookEntry> dropped = new ArrayList<>(matched.subList(maxEntries, matched.size()));
        return new LoreBookSection(List.copyOf(kept), List.copyOf(dropped));
    }

    private MemoryBookSection recallMemoriesForContext(
            Long roomId,
            List<HistoricalMessage> history,
            AgentTask task) {
        if (roomId == null) {
            return MemoryBookSection.empty();
        }
        String query = buildMemoryQuery(history, task);
        List<MemoryEntry> entries;
        try {
            entries = memoryService.recall(roomId, null, query, MEMORY_ENTRY_LIMIT);
        } catch (Exception ex) {
            log.warn("memory recall for context failed roomId={} err={}", roomId, ex.getMessage());
            return MemoryBookSection.empty();
        }
        if (entries == null || entries.isEmpty()) {
            return MemoryBookSection.empty();
        }
        List<MemoryItem> matched = entries.stream()
                .map(entry -> new MemoryItem(
                        entry.getId(),
                        entry.getTitle(),
                        entry.getContent(),
                        Boolean.TRUE.equals(entry.getPinned()),
                        entry.getUpdatedAt()))
                .toList();
        return new MemoryBookSection(matched, List.of());
    }

    private String buildMemoryQuery(List<HistoricalMessage> history, AgentTask task) {
        StringBuilder sb = new StringBuilder();
        int scanFrom = Math.max(0, history.size() - MEMORY_SCAN_HISTORY_LIMIT);
        for (int i = scanFrom; i < history.size(); i++) {
            String content = history.get(i).content();
            if (hasText(content)) {
                sb.append(content).append(' ');
            }
        }
        if (task != null && hasText(task.getPrompt())) {
            sb.append(task.getPrompt());
        }
        String query = sb.toString().trim();
        return query.length() < MEMORY_RECALL_MIN_QUERY_LEN ? "" : query;
    }

    private boolean keysMatch(JsonNode keysNode, List<String> haystack) {
        if (!keysNode.isArray()) {
            return false;
        }
        for (JsonNode keyNode : keysNode) {
            String key = keyNode.asText("").toLowerCase();
            if (!hasText(key)) {
                continue;
            }
            for (String content : haystack) {
                if (content.contains(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    private AgentContextEnvelope trimLoreToBudget(AgentContextEnvelope env, int tokenBudget) {
        if (!env.characterCard().hasCard() || env.loreBook().matched().isEmpty()) {
            return env;
        }
        AgentContextEnvelope current = env;
        while (!current.loreBook().matched().isEmpty()
                && estimateTokens(assembleSystemPrompt(current)) > tokenBudget) {
            List<LoreBookEntry> kept = new ArrayList<>(current.loreBook().matched());
            LoreBookEntry dropped = kept.remove(kept.size() - 1);
            List<LoreBookEntry> droppedAll = new ArrayList<>(current.loreBook().dropped());
            droppedAll.add(0, dropped);
            current = current.withLoreBook(new LoreBookSection(List.copyOf(kept), List.copyOf(droppedAll)));
        }
        return current;
    }

    private AgentContextEnvelope trimMemoryToBudget(AgentContextEnvelope env, int tokenBudget) {
        if (!env.memorySectionEnabled() || env.memoryBook().matched().isEmpty()) {
            return env;
        }
        AgentContextEnvelope current = env;
        List<MemoryItem> pinned = new ArrayList<>();
        List<MemoryItem> unpinned = new ArrayList<>();
        for (MemoryItem item : current.memoryBook().matched()) {
            if (item.pinned()) {
                pinned.add(item);
            } else {
                unpinned.add(item);
            }
        }
        List<MemoryItem> droppedAll = new ArrayList<>(current.memoryBook().dropped());

        while (!unpinned.isEmpty()
                && estimateTokens(assembleSystemPrompt(current)) > tokenBudget) {
            MemoryItem dropped = unpinned.remove(unpinned.size() - 1);
            droppedAll.add(0, dropped);
            List<MemoryItem> kept = new ArrayList<>(pinned);
            kept.addAll(unpinned);
            current = current.withMemoryBook(new MemoryBookSection(List.copyOf(kept), List.copyOf(droppedAll)));
        }

        if (!pinned.isEmpty()
                && estimateTokens(assembleSystemPrompt(current)) > tokenBudget) {
            log.warn("memory section over budget but only pinned remain; keeping pinned "
                    + "(pinnedCount={}, budget={})", pinned.size(), tokenBudget);
        }
        return current;
    }

    private JsonNode readJsonMaybeQuoted(String value) throws Exception {
        JsonNode root = objectMapper.readTree(value);
        if (root.isTextual() && hasText(root.asText())) {
            return objectMapper.readTree(root.asText());
        }
        return root;
    }

    private static void appendIfPresent(StringBuilder prompt, String value) {
        if (hasText(value)) {
            prompt.append(value).append("\n\n");
        }
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
            String avatarUrl,
            boolean group) {
        static RoomMetadata empty(String name) {
            return empty(name, true);
        }

        static RoomMetadata empty(String name, boolean group) {
            return new RoomMetadata(false, defaultString(name, ""), "", 0, List.of(), null, group);
        }
    }

    public record HistoricalMessage(
            String senderName,
            String messageType,
            String content,
            String timestamp,
            List<BotDto.ImageAttachment> imageAttachments) {
        public HistoricalMessage(String senderName, String messageType, String content, String timestamp) {
            this(senderName, messageType, content, timestamp, List.of());
        }
    }

    public record InitiatorInfo(
            String displayName,
            String role,
            boolean roomCreator) {
    }

    public record CharacterCardSection(
            boolean hasCard,
            String persona,
            String scenario,
            String systemPrompt,
            String postHistoryInstructions) {
        static CharacterCardSection empty() {
            return new CharacterCardSection(false, "", "", "", "");
        }
    }

    public record LoreBookEntry(int insertionOrder, String content) {
    }

    public record LoreBookSection(
            List<LoreBookEntry> matched,
            List<LoreBookEntry> dropped) {
        public LoreBookSection {
            matched = List.copyOf(matched);
            dropped = List.copyOf(dropped);
        }

        static LoreBookSection empty() {
            return new LoreBookSection(List.of(), List.of());
        }
    }

    public record MemoryItem(
            Long id,
            String title,
            String content,
            boolean pinned,
            LocalDateTime updatedAt) {
    }

    public record MemoryBookSection(
            List<MemoryItem> matched,
            List<MemoryItem> dropped) {
        public MemoryBookSection {
            matched = List.copyOf(matched);
            dropped = List.copyOf(dropped);
        }

        static MemoryBookSection empty() {
            return new MemoryBookSection(List.of(), List.of());
        }
    }

    @Getter
    public static final class AgentContextEnvelope {
        private final AgentIdentity agentIdentity;
        private final RoomMetadata roomMetadata;
        private final List<HistoricalMessage> conversationHistory;
        private final InitiatorInfo initiator;
        private final List<String> behaviorRules;
        private final CharacterCardSection characterCard;
        private final LoreBookSection loreBook;
        private final MemoryBookSection memoryBook;
        private final boolean memorySectionEnabled;
        private final String taskText;
        private final int maxContextTokensEstimate;
        private final int estimatedTokens;

        AgentContextEnvelope(
                AgentIdentity agentIdentity,
                RoomMetadata roomMetadata,
                List<HistoricalMessage> conversationHistory,
                InitiatorInfo initiator,
                List<String> behaviorRules,
                CharacterCardSection characterCard,
                LoreBookSection loreBook,
                MemoryBookSection memoryBook,
                boolean memorySectionEnabled,
                String taskText,
                int maxContextTokensEstimate,
                int estimatedTokens) {
            this.agentIdentity = agentIdentity;
            this.roomMetadata = roomMetadata;
            this.conversationHistory = List.copyOf(conversationHistory);
            this.initiator = initiator;
            this.behaviorRules = List.copyOf(behaviorRules);
            this.characterCard = characterCard != null ? characterCard : CharacterCardSection.empty();
            this.loreBook = loreBook != null ? loreBook : LoreBookSection.empty();
            this.memoryBook = memoryBook != null ? memoryBook : MemoryBookSection.empty();
            this.memorySectionEnabled = memorySectionEnabled;
            this.taskText = taskText;
            this.maxContextTokensEstimate = maxContextTokensEstimate;
            this.estimatedTokens = estimatedTokens;
        }

        AgentContextEnvelope(
                AgentIdentity agentIdentity,
                RoomMetadata roomMetadata,
                List<HistoricalMessage> conversationHistory,
                InitiatorInfo initiator,
                List<String> behaviorRules,
                CharacterCardSection characterCard,
                LoreBookSection loreBook,
                String taskText,
                int maxContextTokensEstimate,
                int estimatedTokens) {
            this(agentIdentity, roomMetadata, conversationHistory, initiator, behaviorRules,
                    characterCard, loreBook, MemoryBookSection.empty(), false, taskText,
                    maxContextTokensEstimate, estimatedTokens);
        }

        AgentContextEnvelope(
                AgentIdentity agentIdentity,
                RoomMetadata roomMetadata,
                List<HistoricalMessage> conversationHistory,
                InitiatorInfo initiator,
                List<String> behaviorRules,
                String taskText,
                int maxContextTokensEstimate,
                int estimatedTokens) {
            this(agentIdentity, roomMetadata, conversationHistory, initiator, behaviorRules,
                    CharacterCardSection.empty(), LoreBookSection.empty(),
                    MemoryBookSection.empty(), false, taskText,
                    maxContextTokensEstimate, estimatedTokens);
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

        public CharacterCardSection characterCard() {
            return characterCard;
        }

        public LoreBookSection loreBook() {
            return loreBook;
        }

        public MemoryBookSection memoryBook() {
            return memoryBook;
        }

        public boolean memorySectionEnabled() {
            return memorySectionEnabled;
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
                    characterCard,
                    loreBook,
                    memoryBook,
                    memorySectionEnabled,
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
                    characterCard,
                    loreBook,
                    memoryBook,
                    memorySectionEnabled,
                    taskText,
                    maxContextTokensEstimate,
                    tokens);
        }

        AgentContextEnvelope withLoreBook(LoreBookSection newLoreBook) {
            return new AgentContextEnvelope(
                    agentIdentity,
                    roomMetadata,
                    conversationHistory,
                    initiator,
                    behaviorRules,
                    characterCard,
                    newLoreBook,
                    memoryBook,
                    memorySectionEnabled,
                    taskText,
                    maxContextTokensEstimate,
                    estimatedTokens);
        }

        AgentContextEnvelope withMemoryBook(MemoryBookSection newMemoryBook) {
            return new AgentContextEnvelope(
                    agentIdentity,
                    roomMetadata,
                    conversationHistory,
                    initiator,
                    behaviorRules,
                    characterCard,
                    loreBook,
                    newMemoryBook,
                    memorySectionEnabled,
                    taskText,
                    maxContextTokensEstimate,
                    estimatedTokens);
        }
    }
}
