package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.*;
import com.chatapp.repository.AgentTaskRepository;
import com.chatapp.repository.BotConfigRepository;
import com.chatapp.repository.ChatRoomBotRepository;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.service.tool.AgentToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class BotService {

    private static final Pattern URL_PATTERN = Pattern.compile("(https?://[^\\s)\\]\"'<>]+)");
    // Conservative GFM signals — only fire on table separator / heading / code fence,
    // never on stray * or _ in casual prose (rendering is also gated to bot messages).
    private static final Pattern MD_HEADING = Pattern.compile("(?m)^#{1,6}\\s+\\S");
    private static final Pattern MD_TABLE_SEP = Pattern.compile("(?m)^\\s*\\|?[ :|-]*-{2,}[ :|-]*\\|?\\s*$");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private final BotConfigRepository botConfigRepository;
    private final ChatRoomBotRepository chatRoomBotRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final LLMService llmService;
    private final ProviderCredentialService providerCredentialService;
    private final AgentToolRegistry agentToolRegistry;
    private final AgentContextBuilder agentContextBuilder;
    private final AgentTaskRepository agentTaskRepository;
    private final BotRateLimitService botRateLimitService;
    private final RichContentSanitizer richContentSanitizer;
    private final BotWebhookService botWebhookService;
    // Lazy to break the cycle: AgentExecutionLoop -> AgentToolDispatcher ->
    // RawWebSocketHandler -> BotService.
    private final ObjectProvider<AgentExecutionLoop> agentExecutionLoopProvider;

    @Transactional
    public BotDto createBot(Long creatorId, BotDto.CreateRequest request) {
        User creator = userRepository.findById(creatorId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        BotConfig bot = new BotConfig();
        bot.setBotName(request.getBotName());
        bot.setBotAvatar(request.getBotAvatar());
        bot.setLlmProvider(request.getLlmProvider());
        applyCredentialSelection(bot, creatorId, request.getProviderCredentialId(), request.getApiKey());
        bot.setModelName(request.getModelName());
        bot.setSystemPrompt(request.getSystemPrompt());
        bot.setTemperature(request.getTemperature() != null ? request.getTemperature() : 0.7);
        bot.setMaxTokens(request.getMaxTokens() != null ? request.getMaxTokens() : 2048);
        applyEnabledTools(bot, request.getEnabledTools());
        bot.setCreatedBy(creator);

        bot = botConfigRepository.save(bot);
        log.info("用户 {} 创建了机器人: {} ({})", creatorId, bot.getBotName(), bot.getLlmProvider());
        return toDto(bot, true);
    }

    @Transactional
    public BotDto updateBot(Long botId, Long operatorId, BotDto.UpdateRequest request) {
        BotConfig bot = botConfigRepository.findById(botId)
                .orElseThrow(() -> new RuntimeException("机器人不存在"));
        if (bot.getCreatedBy() == null || !bot.getCreatedBy().getId().equals(operatorId)) {
            throw new IllegalArgumentException("只能更新自己创建的机器人");
        }

        if (request.getBotName() != null) bot.setBotName(request.getBotName());
        if (request.getBotAvatar() != null) bot.setBotAvatar(request.getBotAvatar());
        if (request.getProviderCredentialId() != null || request.getApiKey() != null) {
            Long ownerId = bot.getCreatedBy() != null ? bot.getCreatedBy().getId() : null;
            applyCredentialSelection(bot, ownerId, request.getProviderCredentialId(), request.getApiKey());
        }
        if (request.getModelName() != null) bot.setModelName(request.getModelName());
        if (request.getSystemPrompt() != null) bot.setSystemPrompt(request.getSystemPrompt());
        if (request.getTemperature() != null) bot.setTemperature(request.getTemperature());
        if (request.getMaxTokens() != null) bot.setMaxTokens(request.getMaxTokens());
        if (request.getIsActive() != null) bot.setIsActive(request.getIsActive());
        if (request.getEnabledTools() != null) applyEnabledTools(bot, request.getEnabledTools());

        bot = botConfigRepository.save(bot);
        return toDto(bot, true);
    }

    public List<BotDto> getMyBots(Long userId) {
        return botConfigRepository.findByCreatedById(userId).stream()
                .map(bot -> toDto(bot, true)).collect(Collectors.toList());
    }

    public BotDto getBot(Long botId, Long operatorId) {
        BotConfig bot = botConfigRepository.findById(botId)
                .orElseThrow(() -> new RuntimeException("机器人不存在"));
        if (bot.getCreatedBy() == null || !bot.getCreatedBy().getId().equals(operatorId)) {
            throw new AccessDeniedException("无权限查看该机器人");
        }
        return toDto(bot, true);
    }

    @Transactional
    public BotDto importCharacterCard(Long botId, Long operatorId, Map<String, Object> card) {
        BotConfig bot = botConfigRepository.findById(botId)
                .orElseThrow(() -> new RuntimeException("机器人不存在"));
        ensureBotOwner(bot, operatorId);

        Map<String, Object> data = extractCharacterData(card);
        String name = stringValue(data.get("name"));
        if (!name.isBlank()) {
            bot.setBotName(name);
        }
        bot.setCharacterCardJson(writeJson(card));
        bot.setCharacterPersona(joinSections(
                stringValue(data.get("description")),
                stringValue(data.get("personality"))));
        bot.setCharacterScenario(blankToNull(stringValue(data.get("scenario"))));
        bot.setCharacterFirstMes(blankToNull(stringValue(data.get("first_mes"))));
        bot.setCharacterMesExample(blankToNull(stringValue(data.get("mes_example"))));
        bot.setCharacterCreatorNotes(blankToNull(stringValue(data.get("creator_notes"))));
        bot.setCharacterSystemPrompt(blankToNull(stringValue(data.get("system_prompt"))));
        bot.setCharacterPostHistoryInstructions(blankToNull(stringValue(data.get("post_history_instructions"))));
        bot.setCharacterAlternateGreetings(writeJson(readStringList(data.get("alternate_greetings"))));
        Object characterBook = data.get("character_book");
        bot.setCharacterBookJson(characterBook == null ? null : writeJson(characterBook));

        bot = botConfigRepository.save(bot);
        return toDto(bot, true);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exportCharacterCard(Long botId, Long operatorId) {
        BotConfig bot = botConfigRepository.findById(botId)
                .orElseThrow(() -> new RuntimeException("机器人不存在"));
        ensureBotOwner(bot, operatorId);
        if (bot.getCharacterCardJson() != null && !bot.getCharacterCardJson().isBlank()) {
            return readJsonMap(bot.getCharacterCardJson());
        }

        Map<String, Object> data = new HashMap<>();
        data.put("name", bot.getBotName());
        data.put("description", nullToEmpty(bot.getCharacterPersona()));
        data.put("personality", "");
        data.put("scenario", nullToEmpty(bot.getCharacterScenario()));
        data.put("first_mes", nullToEmpty(bot.getCharacterFirstMes()));
        data.put("mes_example", nullToEmpty(bot.getCharacterMesExample()));
        data.put("creator_notes", nullToEmpty(bot.getCharacterCreatorNotes()));
        data.put("system_prompt", nullToEmpty(bot.getCharacterSystemPrompt()));
        data.put("post_history_instructions", nullToEmpty(bot.getCharacterPostHistoryInstructions()));
        data.put("alternate_greetings", readStringList(bot.getCharacterAlternateGreetings()));
        if (bot.getCharacterBookJson() != null && !bot.getCharacterBookJson().isBlank()) {
            data.put("character_book", readJsonMap(bot.getCharacterBookJson()));
        }

        Map<String, Object> card = new HashMap<>();
        card.put("spec", "chara_card_v2");
        card.put("spec_version", "2.0");
        card.put("data", data);
        return card;
    }

    @Transactional
    public void addBotToChatRoom(Long chatRoomId, Long botId, BotDto.AddToChatRoomRequest request) {
        addBotToChatRoom(chatRoomId, botId, request, null);
    }

    @Transactional
    public void addBotToChatRoom(Long chatRoomId, Long botId, BotDto.AddToChatRoomRequest request, Long operatorId) {
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));
        BotConfig bot = botConfigRepository.findById(botId)
                .orElseThrow(() -> new RuntimeException("机器人不存在"));

        validateCanManageRoomBot(chatRoomId, bot, operatorId);

        if (chatRoomBotRepository.findByChatRoomIdAndBotConfigId(chatRoomId, botId).isPresent()) {
            throw new IllegalArgumentException("该机器人已在聊天室中");
        }

        ChatRoomBot crb = new ChatRoomBot();
        crb.setChatRoom(chatRoom);
        crb.setBotConfig(bot);
        crb.setTriggerMode(request != null && request.getTriggerMode() != null ?
                request.getTriggerMode() : ChatRoomBot.TriggerMode.MENTION);
        crb.setTriggerKeywords(request != null ? request.getTriggerKeywords() : null);
        crb.setRoomNickname(request != null ? request.getRoomNickname() : null);
        crb.setRoomPromptSuffix(request != null ? request.getRoomPromptSuffix() : null);
        crb.setEnabledInRoom(request == null || request.getEnabledInRoom() == null
                ? true
                : request.getEnabledInRoom());
        chatRoomBotRepository.save(crb);

        log.info("机器人 {} 已添加到聊天室 {}", bot.getBotName(), chatRoomId);
    }

    @Transactional
    public BotDto updateRoomBotConfig(
            Long chatRoomId,
            Long botId,
            BotDto.AddToChatRoomRequest request,
            Long operatorId) {
        BotConfig bot = botConfigRepository.findById(botId)
                .orElseThrow(() -> new RuntimeException("机器人不存在"));
        validateCanManageRoomBot(chatRoomId, bot, operatorId);
        ChatRoomBot crb = chatRoomBotRepository.findByChatRoomIdAndBotConfigId(chatRoomId, botId)
                .orElseThrow(() -> new RuntimeException("机器人未加入该聊天室"));

        if (request.getTriggerMode() != null) crb.setTriggerMode(request.getTriggerMode());
        if (request.getTriggerKeywords() != null) crb.setTriggerKeywords(request.getTriggerKeywords());
        if (request.getRoomNickname() != null) crb.setRoomNickname(request.getRoomNickname());
        if (request.getRoomPromptSuffix() != null) crb.setRoomPromptSuffix(request.getRoomPromptSuffix());
        if (request.getEnabledInRoom() != null) crb.setEnabledInRoom(request.getEnabledInRoom());

        crb = chatRoomBotRepository.save(crb);
        return toDto(crb, bot.getCreatedBy() != null && bot.getCreatedBy().getId().equals(operatorId));
    }

    @Transactional
    public void removeBotFromChatRoom(Long chatRoomId, Long botId) {
        chatRoomBotRepository.deleteByChatRoomIdAndBotConfigId(chatRoomId, botId);
        log.info("机器人 {} 已从聊天室 {} 移除", botId, chatRoomId);
    }

    @Transactional
    public void removeBotFromChatRoom(Long chatRoomId, Long botId, Long operatorId) {
        BotConfig bot = botConfigRepository.findById(botId)
                .orElseThrow(() -> new RuntimeException("机器人不存在"));
        validateCanManageRoomBot(chatRoomId, bot, operatorId);
        chatRoomBotRepository.deleteByChatRoomIdAndBotConfigId(chatRoomId, botId);
        log.info("机器人 {} 已从聊天室 {} 移除", botId, chatRoomId);
    }

    @Transactional(readOnly = true)
    public List<BotDto> getBotsInChatRoom(Long chatRoomId) {
        return chatRoomBotRepository.findActiveBotsWithConfig(chatRoomId).stream()
                .map(crb -> toDto(crb, false))
                .collect(Collectors.toList());
    }

    @Transactional
    public List<Message> processMessageForBots(Long chatRoomId, String messageContent, Long senderId) {
        List<Message> botMessages = new ArrayList<>();
        List<ChatRoomBot> bots = chatRoomBotRepository.findActiveBotsWithConfig(chatRoomId);
        if (bots.isEmpty()) return botMessages;

        for (ChatRoomBot crb : bots) {
            if (Boolean.FALSE.equals(crb.getEnabledInRoom())) {
                continue;
            }
            String displayName = roomDisplayName(crb);
            boolean shouldRespond = switch (crb.getTriggerMode()) {
                case ALL -> true;
                case MENTION -> messageContent.contains("@" + displayName)
                        || messageContent.contains("@" + crb.getBotConfig().getBotName());
                case KEYWORD -> {
                    if (crb.getTriggerKeywords() == null) yield false;
                    String[] keywords = crb.getTriggerKeywords().split(",");
                    boolean matched = false;
                    for (String kw : keywords) {
                        if (messageContent.contains(kw.trim())) {
                            matched = true;
                            break;
                        }
                    }
                    yield matched;
                }
            };

            if (shouldRespond) {
                try {
                    BotConfig config = crb.getBotConfig();
                    // External bridge: if this bot has an active webhook subscription, forward
                    // the event to the external bot (it replies via the inbound gateway) and
                    // skip the in-app LLM entirely.
                    if (botWebhookService.dispatchIfSubscribed(config, chatRoomId, messageContent, senderId)) {
                        log.info("机器人 {} 已转发到外部 webhook (聊天室 {})", config.getBotName(), chatRoomId);
                        continue;
                    }
                    String replyContent;
                    if (agentToolRegistry.hasExplicitToolWhitelist(config)) {
                        // Tool-enabled bots run the full multi-turn agent loop
                        // (room history + tools), not a single LLM call.
                        replyContent = respondViaAgentLoop(chatRoomId, crb, messageContent, senderId);
                    } else {
                        // Persona / tool-less bots keep the lightweight one-shot path.
                        List<BotDto.ChatMessage> chatMessages = buildContext(crb, messageContent);
                        BotDto.LLMResponse response = llmService.chat(config, chatMessages);
                        replyContent = response.getContent();
                        log.info("机器人 {} 在聊天室 {} 回复了消息 (tokens: {})",
                                config.getBotName(), chatRoomId, response.getTokensUsed());
                    }

                    if (replyContent != null) {
                        Message botMessage = saveBotMessage(chatRoomId, crb, replyContent);
                        if (botMessage != null) {
                            botMessages.add(botMessage);
                        }
                    }
                } catch (Exception e) {
                    log.error("机器人 {} 处理消息失败: {}", crb.getBotConfig().getBotName(), e.getMessage());
                }
            }
        }
        return botMessages;
    }

    /**
     * Runs the multi-turn agent loop for a tool-enabled room bot, persisting a
     * transient {@link AgentTask} for audit/observability. Returns the final answer,
     * or {@code null} when the per-(room,bot) rate limit is exceeded.
     */
    private String respondViaAgentLoop(Long chatRoomId, ChatRoomBot crb, String messageContent, Long senderId) {
        BotConfig config = crb.getBotConfig();
        if (!botRateLimitService.tryAcquireAgentRun(chatRoomId, config.getId())) {
            log.warn("机器人 {} 在聊天室 {} 的 agent 运行被限流，跳过本次回复", config.getBotName(), chatRoomId);
            return null;
        }

        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));
        User requester = senderId != null ? userRepository.findById(senderId).orElse(null) : null;
        if (requester == null) {
            Long fallbackId = config.getCreatedBy() != null
                    ? config.getCreatedBy().getId()
                    : chatRoom.getCreatedBy().getId();
            requester = userRepository.findById(fallbackId)
                    .orElseThrow(() -> new RuntimeException("任务发起人不存在"));
        }

        AgentTask task = new AgentTask();
        task.setChatRoom(chatRoom);
        task.setRequestedBy(requester);
        task.setBotConfig(config);
        task.setPrompt(cleanMentions(messageContent, crb));
        task.setStatus(AgentTask.Status.RUNNING);
        task = agentTaskRepository.save(task);

        try {
            AgentContextBuilder.AgentContextEnvelope envelope = agentContextBuilder.buildContext(task);
            AgentExecutionLoop.AgentLoopResult result =
                    agentExecutionLoopProvider.getObject().runLoop(task, envelope);
            String finalContent = result.finalContent() != null && !result.finalContent().isBlank()
                    ? result.finalContent()
                    : "任务已完成";
            task.setResult(finalContent);
            task.setStatus(AgentTask.Status.SUCCEEDED);
            task.setCompletedAt(LocalDateTime.now());
            agentTaskRepository.save(task);
            log.info("机器人 {} 在聊天室 {} 通过 agent loop 回复 (reason={} iterations={} toolCalls={})",
                    config.getBotName(), chatRoomId, result.terminationReason(),
                    result.iterations(), result.toolCallsMade().size());
            return finalContent;
        } catch (RuntimeException e) {
            task.setStatus(AgentTask.Status.FAILED);
            task.setErrorMessage(e.getMessage());
            task.setCompletedAt(LocalDateTime.now());
            agentTaskRepository.save(task);
            throw e;
        }
    }

    private String cleanMentions(String userMessage, ChatRoomBot crb) {
        BotConfig config = crb.getBotConfig();
        String cleaned = userMessage
                .replaceAll("@" + Pattern.quote(config.getBotName()) + "\\s*", "")
                .replaceAll("@" + Pattern.quote(roomDisplayName(crb)) + "\\s*", "")
                .trim();
        return cleaned.isEmpty() ? "你好" : cleaned;
    }

    private List<BotDto.ChatMessage> buildContext(ChatRoomBot crb, String userMessage) {
        BotConfig config = crb.getBotConfig();
        List<BotDto.ChatMessage> messages = new ArrayList<>();

        String systemPrompt = joinSections(
                config.getSystemPrompt(),
                config.getCharacterSystemPrompt(),
                config.getCharacterPersona(),
                config.getCharacterScenario(),
                matchedLore(config, userMessage));
        if (crb.getRoomPromptSuffix() != null && !crb.getRoomPromptSuffix().isBlank()) {
            systemPrompt = (systemPrompt == null ? "" : systemPrompt + "\n\n") + crb.getRoomPromptSuffix();
        }
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new BotDto.ChatMessage("system", systemPrompt));
        }

        // Clean @mention from message
        String cleanMessage = cleanMentions(userMessage, crb);
        messages.add(new BotDto.ChatMessage("user", cleanMessage));
        if (config.getCharacterPostHistoryInstructions() != null
                && !config.getCharacterPostHistoryInstructions().isBlank()) {
            messages.add(new BotDto.ChatMessage("system", config.getCharacterPostHistoryInstructions().trim()));
        }

        return messages;
    }

    private Message saveBotMessage(Long chatRoomId, ChatRoomBot crb, String content) {
        BotConfig config = crb.getBotConfig();
        String displayName = roomDisplayName(crb);
        // Use first admin user as bot sender, or create a system message
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId).orElse(null);
        if (chatRoom == null) return null;

        Message message = new Message();
        BotMediaAttachment attachment = extractMediaAttachment(content);
        if (attachment != null) {
            message.setContent(attachment.fileName());
            message.setMessageType(attachment.messageType());
            message.setFileUrl(attachment.url());
            message.setFileName(attachment.fileName());
            message.setFileType(attachment.fileType());
        } else {
            if (looksLikeMarkdown(content)) {
                message.setContent(richContentSanitizer.sanitizeMarkdown(content));
                message.setContentFormat(Message.ContentFormat.MARKDOWN);
            } else {
                message.setContent(content);
            }
            message.setMessageType(Message.MessageType.TEXT);
        }
        message.setChatRoom(chatRoom);
        message.setBotConfig(config);
        // Bot messages need a sender - use the bot creator or chat room creator
        Long botUserId = config.getCreatedBy() != null
                ? config.getCreatedBy().getId()
                : chatRoom.getCreatedBy().getId();
        User botUser = userRepository.findById(botUserId)
                .orElseThrow(() -> new RuntimeException("机器人发送者不存在"));
        message.setSender(botUser);
        message.setMessageStatus(Message.MessageStatus.SENT);
        return messageRepository.save(message);
    }

    /** Bot reply is treated as markdown only on strong GFM signals (table/heading/code fence). */
    private boolean looksLikeMarkdown(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        if (content.contains("```")) {
            return true;
        }
        if (MD_HEADING.matcher(content).find()) {
            return true;
        }
        return content.contains("|") && MD_TABLE_SEP.matcher(content).find();
    }

    private BotMediaAttachment extractMediaAttachment(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }

        Matcher matcher = URL_PATTERN.matcher(content);
        while (matcher.find()) {
            String url = stripTrailingPunctuation(matcher.group(1));
            String lowerUrl = url.toLowerCase();
            MediaDescriptor descriptor = descriptorForUrl(lowerUrl);
            if (descriptor != null) {
                return new BotMediaAttachment(
                        url,
                        resolveFileName(url, descriptor.defaultExtension()),
                        descriptor.fileType(),
                        descriptor.messageType());
            }
        }
        return null;
    }

    private String stripTrailingPunctuation(String url) {
        String trimmed = url;
        while (!trimmed.isEmpty()) {
            char last = trimmed.charAt(trimmed.length() - 1);
            if (last == '.' || last == ',' || last == ';' || last == ':') {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            } else {
                break;
            }
        }
        return trimmed;
    }

    private MediaDescriptor descriptorForUrl(String lowerUrl) {
        String path = lowerUrl.split("[?#]", 2)[0];
        if (path.endsWith(".png")) return new MediaDescriptor("image/png", "png", Message.MessageType.IMAGE);
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return new MediaDescriptor("image/jpeg", "jpg", Message.MessageType.IMAGE);
        if (path.endsWith(".gif")) return new MediaDescriptor("image/gif", "gif", Message.MessageType.IMAGE);
        if (path.endsWith(".webp")) return new MediaDescriptor("image/webp", "webp", Message.MessageType.IMAGE);
        if (path.endsWith(".mp4")) return new MediaDescriptor("video/mp4", "mp4", Message.MessageType.VIDEO);
        if (path.endsWith(".webm")) return new MediaDescriptor("video/webm", "webm", Message.MessageType.VIDEO);
        if (path.endsWith(".mov")) return new MediaDescriptor("video/quicktime", "mov", Message.MessageType.VIDEO);
        if (path.endsWith(".mp3")) return new MediaDescriptor("audio/mpeg", "mp3", Message.MessageType.AUDIO);
        if (path.endsWith(".wav")) return new MediaDescriptor("audio/wav", "wav", Message.MessageType.AUDIO);
        if (path.endsWith(".ogg")) return new MediaDescriptor("audio/ogg", "ogg", Message.MessageType.AUDIO);
        if (path.endsWith(".pdf")) return new MediaDescriptor("application/pdf", "pdf", Message.MessageType.FILE);
        if (path.endsWith(".txt")) return new MediaDescriptor("text/plain", "txt", Message.MessageType.FILE);
        if (path.endsWith(".zip")) return new MediaDescriptor("application/zip", "zip", Message.MessageType.FILE);
        return null;
    }

    private String resolveFileName(String url, String defaultExtension) {
        String path = url.split("[?#]", 2)[0];
        int slash = path.lastIndexOf('/');
        String candidate = slash >= 0 ? path.substring(slash + 1) : path;
        if (candidate.isBlank() || !candidate.contains(".")) {
            return "bot-media." + defaultExtension;
        }
        return candidate;
    }

    private record BotMediaAttachment(
            String url,
            String fileName,
            String fileType,
            Message.MessageType messageType) {
    }

    private record MediaDescriptor(
            String fileType,
            String defaultExtension,
            Message.MessageType messageType) {
    }

    @Transactional
    public void deleteBot(Long botId, Long userId) {
        BotConfig bot = botConfigRepository.findById(botId)
                .orElseThrow(() -> new RuntimeException("机器人不存在"));
        if (!bot.getCreatedBy().getId().equals(userId)) {
            throw new IllegalArgumentException("只能删除自己创建的机器人");
        }
        botConfigRepository.delete(bot);
        log.info("机器人 {} 已删除", bot.getBotName());
    }

    private BotDto toDto(BotConfig entity) {
        return toDto(entity, false);
    }

    private BotDto toDto(BotConfig entity, boolean includeCredentialDetails) {
        BotDto dto = new BotDto();
        dto.setId(entity.getId());
        dto.setBotName(entity.getBotName());
        dto.setBotAvatar(entity.getBotAvatar());
        dto.setLlmProvider(entity.getLlmProvider());
        dto.setModelName(entity.getModelName());
        dto.setSystemPrompt(entity.getSystemPrompt());
        dto.setTemperature(entity.getTemperature());
        dto.setMaxTokens(entity.getMaxTokens());
        dto.setIsActive(entity.getIsActive());
        if (includeCredentialDetails && entity.getProviderCredential() != null) {
            dto.setProviderCredentialId(entity.getProviderCredential().getId());
            dto.setProviderCredentialLabel(entity.getProviderCredential().getLabel());
            dto.setProviderCredentialLast4(entity.getProviderCredential().getSecretLast4());
        }
        dto.setHasCredential(entity.getProviderCredential() != null
                || (entity.getApiKeyEncrypted() != null && !entity.getApiKeyEncrypted().isBlank()));
        dto.setHasCharacterCard(entity.getCharacterCardJson() != null && !entity.getCharacterCardJson().isBlank());
        dto.setCharacterPersona(entity.getCharacterPersona());
        dto.setCharacterScenario(entity.getCharacterScenario());
        dto.setCharacterFirstMes(entity.getCharacterFirstMes());
        dto.setCharacterAlternateGreetings(readStringList(entity.getCharacterAlternateGreetings()));
        dto.setCharacterBookEntryCount(countCharacterBookEntries(entity.getCharacterBookJson()));
        dto.setEnabledTools(readStringList(entity.getEnabledTools()));
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    /** Stores the enabled-tools whitelist as a JSON array; an empty list clears it (no tools = one-shot path). */
    private void applyEnabledTools(BotConfig bot, List<String> tools) {
        if (tools == null) {
            return;
        }
        bot.setEnabledTools(tools.isEmpty() ? null : writeJson(tools));
    }

    private void applyCredentialSelection(
            BotConfig bot,
            Long ownerId,
            Long providerCredentialId,
            String rawApiKey) {
        if (ownerId == null) {
            throw new IllegalArgumentException("机器人所有者不存在，无法保存凭据");
        }
        if (providerCredentialId != null) {
            ProviderCredential credential = providerCredentialService.getOwnedCredential(ownerId, providerCredentialId);
            if (credential.getLlmProvider() != bot.getLlmProvider()) {
                throw new IllegalArgumentException("凭据提供者与机器人 LLM 提供者不一致");
            }
            bot.setProviderCredential(credential);
            bot.setApiKeyEncrypted(null);
            return;
        }
        if (rawApiKey != null && !rawApiKey.isBlank()) {
            ProviderCredential credential = providerCredentialService.createForBot(
                    ownerId,
                    bot.getLlmProvider(),
                    bot.getBotName() + " " + bot.getLlmProvider() + " key " + System.currentTimeMillis(),
                    rawApiKey.trim());
            bot.setProviderCredential(credential);
            bot.setApiKeyEncrypted(null);
        }
    }

    private BotDto toDto(ChatRoomBot entity) {
        return toDto(entity, false);
    }

    private BotDto toDto(ChatRoomBot entity, boolean includeCredentialDetails) {
        BotDto dto = toDto(entity.getBotConfig(), includeCredentialDetails);
        dto.setTriggerMode(entity.getTriggerMode());
        dto.setTriggerKeywords(entity.getTriggerKeywords());
        dto.setRoomNickname(entity.getRoomNickname());
        dto.setRoomPromptSuffix(entity.getRoomPromptSuffix());
        dto.setEnabledInRoom(entity.getEnabledInRoom());
        return dto;
    }

    private void ensureBotOwner(BotConfig bot, Long operatorId) {
        if (bot.getCreatedBy() == null || !bot.getCreatedBy().getId().equals(operatorId)) {
            throw new AccessDeniedException("无权限管理该机器人");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractCharacterData(Map<String, Object> card) {
        if (card == null || card.isEmpty()) {
            throw new IllegalArgumentException("角色卡不能为空");
        }
        Object spec = card.get("spec");
        if (spec != null && !"chara_card_v2".equalsIgnoreCase(spec.toString())) {
            throw new IllegalArgumentException("仅支持 SillyTavern v2 角色卡");
        }
        Object data = card.get("data");
        if (!(data instanceof Map<?, ?> rawData)) {
            throw new IllegalArgumentException("角色卡缺少 data");
        }
        Map<String, Object> result = new HashMap<>();
        rawData.forEach((key, value) -> {
            if (key != null) result.put(key.toString(), value);
        });
        return result;
    }

    private String matchedLore(BotConfig config, String userMessage) {
        if (config.getCharacterBookJson() == null || config.getCharacterBookJson().isBlank()) {
            return null;
        }
        Map<String, Object> book = readJsonMap(config.getCharacterBookJson());
        Object rawEntries = book.get("entries");
        if (!(rawEntries instanceof List<?> entries)) {
            return null;
        }
        String text = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        for (Object rawEntry : entries) {
            if (!(rawEntry instanceof Map<?, ?> entry)) continue;
            if (Boolean.FALSE.equals(entry.get("enabled"))) continue;
            String content = stringValue(entry.get("content"));
            if (content.isBlank()) continue;
            for (String key : readStringList(entry.get("keys"))) {
                if (!key.isBlank() && text.contains(key.toLowerCase(Locale.ROOT))) {
                    matched.add(content);
                    break;
                }
            }
        }
        return matched.isEmpty() ? null : "Relevant lore:\n" + String.join("\n\n", matched);
    }

    private String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("角色卡 JSON 无法序列化", e);
        }
    }

    private Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("角色卡 JSON 无法解析", e);
        }
    }

    private List<String> readStringList(Object value) {
        if (value == null) return Collections.emptyList();
        if (value instanceof List<?> list) {
            return list.stream().map(this::stringValue).filter(item -> !item.isBlank()).toList();
        }
        if (value instanceof String text) {
            if (text.isBlank()) return Collections.emptyList();
            String trimmed = text.trim();
            if (trimmed.startsWith("[")) {
                try {
                    return JSON.readValue(trimmed, STRING_LIST_TYPE);
                } catch (JsonProcessingException ignored) {
                    return Collections.emptyList();
                }
            }
            return List.of(trimmed);
        }
        return Collections.emptyList();
    }

    private String joinSections(String... sections) {
        List<String> values = new ArrayList<>();
        for (String section : sections) {
            if (section != null && !section.isBlank()) {
                values.add(section.trim());
            }
        }
        return values.isEmpty() ? null : String.join("\n\n", values);
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private int countCharacterBookEntries(String json) {
        if (json == null || json.isBlank()) return 0;
        Object entries = readJsonMap(json).get("entries");
        return entries instanceof List<?> list ? list.size() : 0;
    }

    private String roomDisplayName(ChatRoomBot crb) {
        if (crb.getRoomNickname() != null && !crb.getRoomNickname().isBlank()) {
            return crb.getRoomNickname().trim();
        }
        return crb.getBotConfig().getBotName();
    }

    private void validateCanManageRoomBot(Long chatRoomId, BotConfig bot, Long operatorId) {
        if (operatorId == null) {
            return;
        }
        boolean isBotOwner = bot.getCreatedBy() != null && bot.getCreatedBy().getId().equals(operatorId);
        boolean isRoomAdmin = chatRoomRepository.isAdmin(chatRoomId, operatorId);
        if (!isBotOwner && !isRoomAdmin) {
            throw new IllegalArgumentException("无权限管理该聊天室机器人");
        }
    }
}
