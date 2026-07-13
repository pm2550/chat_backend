package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.*;
import com.chatapp.repository.AgentTaskRepository;
import com.chatapp.repository.BotAllowedUserRepository;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private static final Pattern SENTENCE_BOUNDARY =
            Pattern.compile("(?<=[。！？!?；;])\\s+|(?<=[。！？!?；;])|\\n+");
    private static final String KIRARA_ANALYSIS_SYSTEM_PROMPT = """
            你是 Kirara/阿雷工作流的 R1 上下文判定器，只做分析，不直接聊天。
            你的任务是根据房间上下文、最近消息和当前触发文本，判断真正应该回应什么。
            规则：
            - 如果当前触发文本只有 @机器人、空白、或很短追问（例如：然后呢、真的吗、快说、？），主要根据最近一两条用户消息补全真实意图。
            - 如果当前触发文本本身是完整问题、判断或挑衅，优先回应当前文本，前文只当背景。
            - 不要假装知道没有出现在上下文里的事实。
            - 只输出紧凑 JSON，不要 Markdown，不要解释。
            JSON 字段：
            {
              "mention_only": boolean,
              "target_speaker": "应主要回应的发言人或空字符串",
              "target_message": "应主要回应的原话摘要",
              "reply_intent": "阿雷下一轮应该回应的重点",
              "tone": "建议语气，短语",
              "avoid": "应避免的错误"
            }
            """;
    private static final String KIRARA_SECOND_PASS_SYSTEM_PROMPT = """
            [KIRARA R1 CONTEXT ANALYSIS]
            下面是第一轮 R1 对上下文的隐藏判定。它不是要展示给用户看的内容。
            你必须用它来确定该接哪句前文、该回答什么、该用什么语气。
            禁止复述 JSON，禁止提到 R1、分析器、系统提示或工作流。
            如果 R1 说当前是 mention-only，就直接回应 target_message/reply_intent，不要泛泛问“有什么可以帮你”。
            输出仍按阿雷/Kirara 风格，用 <break> 分成 3-5 条短消息。
            """;
    private static final String MENTION_ONLY_MARKER = "[MENTION_ONLY]";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private final BotConfigRepository botConfigRepository;
    private final BotAllowedUserRepository botAllowedUserRepository;
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
    private final FileStorageService fileStorageService;
    private final BotVisionAttachmentSelector botVisionAttachmentSelector;
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
        bot.setMaxHistoryMessages(request.getMaxHistoryMessages() != null ? request.getMaxHistoryMessages() : 20);
        bot.setIncludeRoomMetadata(request.getIncludeRoomMetadata() == null || request.getIncludeRoomMetadata());
        bot.setVisionInputEnabled(request.getVisionInputEnabled() == null || request.getVisionInputEnabled());
        bot.setHistoryImageInspectionEnabled(request.getHistoryImageInspectionEnabled() == null
                || request.getHistoryImageInspectionEnabled());
        bot.setReplyMode(request.getReplyMode() != null
                ? request.getReplyMode()
                : BotConfig.ReplyMode.SINGLE);
        bot.setWorkflowMode(request.getWorkflowMode() != null
                ? request.getWorkflowMode()
                : BotConfig.WorkflowMode.SINGLE_PASS);
        applyImageGenerationSettings(
                bot,
                creatorId,
                request.getImageGenerationProvider(),
                request.getImageProviderCredentialId(),
                request.getImageApiKey(),
                request.getImageBaseUrl(),
                request.getImageModel(),
                request.getImageNegativePrompt());
        bot.setAccessPolicy(request.getAccessPolicy() != null
                ? request.getAccessPolicy()
                : BotConfig.AccessPolicy.PRIVATE);
        applyEnabledTools(bot, request.getEnabledTools());
        bot.setCreatedBy(creator);

        bot = botConfigRepository.save(bot);
        replaceAllowedUsers(bot, request.getAllowedUserIds(), request.getAllowedUsernames());
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
        if (request.getLlmProvider() != null) bot.setLlmProvider(request.getLlmProvider());
        if (request.getProviderCredentialId() != null || request.getApiKey() != null) {
            Long ownerId = bot.getCreatedBy() != null ? bot.getCreatedBy().getId() : null;
            applyCredentialSelection(bot, ownerId, request.getProviderCredentialId(), request.getApiKey());
        }
        if (request.getModelName() != null) bot.setModelName(request.getModelName());
        if (request.getSystemPrompt() != null) bot.setSystemPrompt(request.getSystemPrompt());
        if (request.getTemperature() != null) bot.setTemperature(request.getTemperature());
        if (request.getMaxTokens() != null) bot.setMaxTokens(request.getMaxTokens());
        if (request.getMaxHistoryMessages() != null) bot.setMaxHistoryMessages(request.getMaxHistoryMessages());
        if (request.getIncludeRoomMetadata() != null) bot.setIncludeRoomMetadata(request.getIncludeRoomMetadata());
        if (request.getVisionInputEnabled() != null) bot.setVisionInputEnabled(request.getVisionInputEnabled());
        if (request.getHistoryImageInspectionEnabled() != null) {
            bot.setHistoryImageInspectionEnabled(request.getHistoryImageInspectionEnabled());
        }
        if (request.getReplyMode() != null) bot.setReplyMode(request.getReplyMode());
        if (request.getWorkflowMode() != null) bot.setWorkflowMode(request.getWorkflowMode());
        if (request.getImageGenerationProvider() != null
                || request.getImageProviderCredentialId() != null
                || (request.getImageApiKey() != null && !request.getImageApiKey().isBlank())
                || request.getImageModel() != null
                || request.getImageNegativePrompt() != null) {
            applyImageGenerationSettings(
                    bot,
                    bot.getCreatedBy().getId(),
                    request.getImageGenerationProvider(),
                    request.getImageProviderCredentialId(),
                    request.getImageApiKey(),
                    request.getImageBaseUrl(),
                    request.getImageModel(),
                    request.getImageNegativePrompt());
        }
        if (request.getIsActive() != null) bot.setIsActive(request.getIsActive());
        if (request.getEnabledTools() != null) applyEnabledTools(bot, request.getEnabledTools());
        if (request.getAccessPolicy() != null) bot.setAccessPolicy(request.getAccessPolicy());

        bot = botConfigRepository.save(bot);
        if (request.getAllowedUserIds() != null || request.getAllowedUsernames() != null) {
            replaceAllowedUsers(bot, request.getAllowedUserIds(), request.getAllowedUsernames());
        }
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
    public BotDto updateBotAvatar(Long botId, Long operatorId, MultipartFile avatarFile) throws IOException {
        if (avatarFile == null || avatarFile.isEmpty()) {
            throw new IllegalArgumentException("请选择有效头像");
        }
        BotConfig bot = botConfigRepository.findById(botId)
                .orElseThrow(() -> new RuntimeException("机器人不存在"));
        ensureBotOwner(bot, operatorId);

        String avatarUrl = fileStorageService.uploadAvatar(avatarFile);
        bot.setBotAvatar(avatarUrl);
        bot = botConfigRepository.save(bot);
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
        return processMessageForBots(chatRoomId, messageContent, senderId, null);
    }

    @Transactional
    public List<Message> processMessageForBots(Long chatRoomId, String messageContent, Long senderId, Message sourceMessage) {
        String safeContent = messageContent != null ? messageContent : "";
        List<Message> botMessages = new ArrayList<>();
        List<ChatRoomBot> bots = chatRoomBotRepository.findActiveBotsWithConfig(chatRoomId);
        if (bots.isEmpty()) return botMessages;

        for (ChatRoomBot crb : bots) {
            if (Boolean.FALSE.equals(crb.getEnabledInRoom())) {
                continue;
            }
            if (!canTriggerBot(crb.getBotConfig(), senderId)) {
                log.info("机器人 {} 在聊天室 {} 被用户 {} 触发但无使用权限，已跳过",
                        crb.getBotConfig().getBotName(), chatRoomId, senderId);
                continue;
            }
            String displayName = roomDisplayName(crb);
            ChatRoomBot.TriggerMode triggerMode = crb.getTriggerMode() != null
                    ? crb.getTriggerMode()
                    : ChatRoomBot.TriggerMode.MENTION;
            boolean shouldRespond = switch (triggerMode) {
                case ALL -> true;
                case MENTION -> safeContent.contains("@" + displayName)
                        || safeContent.contains("@" + crb.getBotConfig().getBotName());
                case KEYWORD -> keywordTriggerMatches(crb.getTriggerKeywords(), safeContent);
                case REGEX -> regexTriggerMatches(crb, safeContent);
            };

            if (shouldRespond) {
                try {
                    BotConfig config = crb.getBotConfig();
                    // External bridge: if this bot has an active webhook subscription, forward
                    // the event to the external bot (it replies via the inbound gateway) and
                    // skip the in-app LLM entirely.
                    if (botWebhookService.dispatchIfSubscribed(config, chatRoomId, safeContent, senderId)) {
                        log.info("机器人 {} 已转发到外部 webhook (聊天室 {})", config.getBotName(), chatRoomId);
                        continue;
                    }
                    String replyContent;
                    if (agentToolRegistry.hasExplicitToolWhitelist(config)) {
                        // Tool-enabled bots run the full multi-turn agent loop
                        // (room history + tools), not a single LLM call.
                        replyContent = respondViaAgentLoop(chatRoomId, crb, safeContent, senderId, sourceMessage);
                    } else {
                        // Persona / tool-less bots keep the lightweight one-shot path.
                        if (isKiraraTwoPass(config)) {
                            replyContent = respondViaKiraraTwoPass(chatRoomId, crb, safeContent, sourceMessage);
                        } else {
                            List<BotDto.ChatMessage> chatMessages = buildContext(chatRoomId, crb, safeContent, sourceMessage);
                            BotDto.LLMResponse response = llmService.chat(config, chatMessages);
                            replyContent = response.getContent();
                            log.info("机器人 {} 在聊天室 {} 回复了消息 (tokens: {})",
                                    config.getBotName(), chatRoomId, response.getTokensUsed());
                        }
                    }

                    if (replyContent != null) {
                        botMessages.addAll(saveBotReplyMessages(chatRoomId, crb, replyContent));
                    }
                } catch (Exception e) {
                    log.error("机器人 {} 处理消息失败: {}", crb.getBotConfig().getBotName(), e.getMessage());
                    Message errorMessage = saveBotFailureMessage(chatRoomId, crb, e);
                    if (errorMessage != null) {
                        botMessages.add(errorMessage);
                    }
                }
            }
        }
        return botMessages;
    }

    private boolean keywordTriggerMatches(String rawKeywords, String safeContent) {
        if (rawKeywords == null || rawKeywords.isBlank()) {
            return false;
        }
        String[] keywords = rawKeywords.split(",");
        for (String kw : keywords) {
            String keyword = kw.trim();
            if (!keyword.isBlank() && safeContent.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean regexTriggerMatches(ChatRoomBot crb, String safeContent) {
        String pattern = crb.getTriggerKeywords();
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(safeContent)
                    .find();
        } catch (Exception e) {
            log.warn("机器人 {} 在聊天室 {} 的正则触发式无效，已跳过: {}",
                    crb.getBotConfig() != null ? crb.getBotConfig().getBotName() : "<unknown>",
                    crb.getChatRoom() != null ? crb.getChatRoom().getId() : "<unknown>",
                    e.getMessage());
            return false;
        }
    }

    private boolean isKiraraTwoPass(BotConfig config) {
        return config != null && config.getWorkflowMode() == BotConfig.WorkflowMode.KIRARA_TWO_PASS;
    }

    private String respondViaKiraraTwoPass(
            Long chatRoomId,
            ChatRoomBot crb,
            String messageContent,
            Message sourceMessage) {
        BotConfig config = crb.getBotConfig();
        try {
            String cleanMessage = kiraraTaskText(chatRoomId, crb, messageContent, sourceMessage);
            BotDto.LLMResponse analysis = llmService.chat(
                    config,
                    buildKiraraAnalysisMessages(chatRoomId, crb, cleanMessage, sourceMessage));
            String analysisText = normalizeAnalysis(analysis.getContent());

            List<BotDto.ChatMessage> finalMessages = buildContext(chatRoomId, crb, messageContent, sourceMessage);
            injectKiraraAnalysis(finalMessages, analysisText);

            BotDto.LLMResponse response = llmService.chat(config, finalMessages);
            String content = requireBotReplyContent(response.getContent(), "kirara-final");
            log.info("机器人 {} 在聊天室 {} 通过 Kirara two-pass 回复 (analysisTokens={} finalTokens={})",
                    config.getBotName(), chatRoomId, analysis.getTokensUsed(), response.getTokensUsed());
            return content;
        } catch (Exception e) {
            log.warn("机器人 {} 在聊天室 {} 的 Kirara two-pass 失败，回退到单次上下文回复: {}",
                    config.getBotName(), chatRoomId, e.getMessage());
            List<BotDto.ChatMessage> fallbackMessages = buildContext(chatRoomId, crb, messageContent, sourceMessage);
            BotDto.LLMResponse fallback = llmService.chat(config, fallbackMessages);
            if (isMentionOnlyTrigger(messageContent, crb)) {
                return fallback.getContent() != null && !fallback.getContent().isBlank()
                        ? fallback.getContent()
                        : defaultMentionOnlyReply(chatRoomId, crb, sourceMessage);
            }
            return requireBotReplyContent(fallback.getContent(), "kirara-fallback");
        }
    }

    private List<BotDto.ChatMessage> buildKiraraAnalysisMessages(
            Long chatRoomId,
            ChatRoomBot crb,
            String cleanMessage,
            Message sourceMessage) {
        List<BotDto.ChatMessage> messages = new ArrayList<>();
        String roomAwareContext = buildRoomAwareOneShotSystemPrompt(chatRoomId, crb, cleanMessage, sourceMessage);
        messages.add(new BotDto.ChatMessage(
                "system",
                KIRARA_ANALYSIS_SYSTEM_PROMPT + "\n\n[ROOM AND RECENT CONTEXT]\n" + roomAwareContext));
        messages.add(new BotDto.ChatMessage(
                "user",
                "当前触发文本：\n" + nullToEmpty(cleanMessage) + "\n\n只输出 JSON。"));
        return messages;
    }

    private String normalizeAnalysis(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{\"mention_only\":false,\"target_speaker\":\"\",\"target_message\":\"\",\"reply_intent\":\"按当前消息自然回复\",\"tone\":\"阿雷风格\",\"avoid\":\"不要泛泛问有什么可以帮你\"}";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("(?s)^```(?:json)?\\s*", "")
                    .replaceFirst("(?s)\\s*```$", "")
                    .trim();
        }
        return trimmed;
    }

    private void injectKiraraAnalysis(List<BotDto.ChatMessage> finalMessages, String analysisText) {
        if (finalMessages == null) {
            return;
        }
        BotDto.ChatMessage analysisMessage = new BotDto.ChatMessage(
                "system",
                KIRARA_SECOND_PASS_SYSTEM_PROMPT + "\n" + analysisText);
        int insertAt = Math.max(0, finalMessages.size() - 1);
        finalMessages.add(insertAt, analysisMessage);
    }

    private String requireBotReplyContent(String content, String phase) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("LLM returned empty bot reply at " + phase);
        }
        return content;
    }

    private String kiraraTaskText(
            Long chatRoomId,
            ChatRoomBot crb,
            String userMessage,
            Message sourceMessage) {
        String cleanMessage = cleanMentions(userMessage, crb);
        if (!cleanMessage.startsWith(MENTION_ONLY_MARKER)) {
            return cleanMessage;
        }
        Message previous = findPreviousHumanMessage(chatRoomId, sourceMessage);
        if (previous == null || previous.getContent() == null || previous.getContent().isBlank()) {
            return cleanMessage + "\n没有找到明确前文时，也要用阿雷/Kirara 风格自然接话，不要问“有什么可以帮你”。";
        }
        String speaker = previous.getSender() != null
                ? previous.getSender().getDisplayName() != null && !previous.getSender().getDisplayName().isBlank()
                    ? previous.getSender().getDisplayName()
                    : previous.getSender().getUsername()
                : "前一个用户";
        return MENTION_ONLY_MARKER + """
                
                用户这条消息只有 @ 机器人，没有新的正文。
                请主要回应最近一条真实用户发言，而不是泛泛打招呼：
                发言人：%s
                原话：%s
                如果这句本身也是召唤或很短，就结合再前面的群聊语境自然接话。
                """.formatted(speaker, previous.getContent().trim());
    }

    private boolean isMentionOnlyTrigger(String userMessage, ChatRoomBot crb) {
        return cleanMentions(userMessage, crb).startsWith(MENTION_ONLY_MARKER);
    }

    private Message findPreviousHumanMessage(Long chatRoomId, Message sourceMessage) {
        if (sourceMessage == null || sourceMessage.getCreatedAt() == null || chatRoomId == null) {
            return null;
        }
        List<Message> previousMessages = messageRepository.findContextBefore(
                chatRoomId,
                sourceMessage.getCreatedAt(),
                PageRequest.of(0, 12));
        for (Message message : previousMessages) {
            if (message == null) {
                continue;
            }
            if (sourceMessage.getId() != null && sourceMessage.getId().equals(message.getId())) {
                continue;
            }
            if (message.getBotConfig() != null) {
                continue;
            }
            if (message.getContent() != null && !message.getContent().isBlank()) {
                return message;
            }
        }
        return null;
    }

    private String defaultMentionOnlyReply(Long chatRoomId, ChatRoomBot crb, Message sourceMessage) {
        Message previous = findPreviousHumanMessage(chatRoomId, sourceMessage);
        if (previous != null && previous.getContent() != null && !previous.getContent().isBlank()) {
            return "看到了。<break>你刚才说的是：“%s”<break>我接这句。".formatted(previous.getContent().trim());
        }
        return "在。<break>别只 @ 我装高手。<break>把话扔出来。";
    }



    private Message saveBotFailureMessage(Long chatRoomId, ChatRoomBot crb, Exception cause) {
        try {
            return saveBotMessage(chatRoomId, crb, botFailureMessage(cause));
        } catch (Exception saveError) {
            log.warn("机器人 {} 错误提示保存失败: {}",
                    crb.getBotConfig().getBotName(), saveError.getMessage());
            return null;
        }
    }

    private List<Message> saveBotReplyMessages(Long chatRoomId, ChatRoomBot crb, String content) {
        List<String> chunks = splitReplyIntoChunks(crb.getBotConfig(), content);
        List<Message> messages = new ArrayList<>();
        for (String chunk : chunks) {
            Message botMessage = saveBotMessage(chatRoomId, crb, chunk);
            if (botMessage != null) {
                messages.add(botMessage);
            }
        }
        return messages;
    }

    private List<String> splitReplyIntoChunks(BotConfig config, String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        if (config == null || config.getReplyMode() != BotConfig.ReplyMode.CHUNKED) {
            return List.of(content);
        }
        if (extractMediaAttachment(content) != null || looksLikeMarkdown(content)) {
            return List.of(content);
        }

        String normalized = content.trim()
                .replace("\r\n", "\n")
                .replaceAll("(?i)<break>", "\n");
        List<String> chunks = new ArrayList<>();
        Matcher matcher = SENTENCE_BOUNDARY.matcher(normalized);
        int start = 0;
        while (matcher.find()) {
            int end = matcher.end();
            addReplyChunk(chunks, normalized.substring(start, end));
            start = end;
        }
        addReplyChunk(chunks, normalized.substring(start));

        if (chunks.size() <= 1) {
            return List.of(content);
        }
        return chunks;
    }

    private void addReplyChunk(List<String> chunks, String raw) {
        String chunk = raw == null ? "" : raw.trim();
        if (!chunk.isBlank()) {
            chunks.add(chunk);
        }
    }

    private String botFailureMessage(Exception e) {
        String message = e != null && e.getMessage() != null ? e.getMessage() : "";
        String lower = message.toLowerCase();
        if (lower.contains("401") || lower.contains("unauthorized") || lower.contains("invalid_request_error")
                || lower.contains("authentication") || lower.contains("api key")) {
            return "⚠️ 这个 bot 的模型 API 认证失败了。请检查它绑定的 Provider 凭据/API key 是否正确。";
        }
        if (lower.contains("429") || lower.contains("quota") || lower.contains("rate limit")
                || lower.contains("billing")) {
            return "⚠️ 这个 bot 的模型额度不足或被限流了。请检查模型账号余额、套餐或限流设置。";
        }
        return "⚠️ 这个 bot 调用模型失败了，后台已经记录错误；请检查它的模型配置和服务状态。";
    }

    /**
     * Runs the multi-turn agent loop for a tool-enabled room bot, persisting a
     * transient {@link AgentTask} for audit/observability. Returns the final answer,
     * or {@code null} when the per-(room,bot) rate limit is exceeded.
     */
    private String respondViaAgentLoop(Long chatRoomId, ChatRoomBot crb, String messageContent, Long senderId, Message sourceMessage) {
        BotConfig config = crb.getBotConfig();
        if (!botRateLimitService.tryAcquireAgentRun(chatRoomId, config.getId())) {
            log.warn("机器人 {} 在聊天室 {} 的 agent 运行被限流，返回可见提示", config.getBotName(), chatRoomId);
            return "⚠️ 请求太密集，我需要缓一下。请稍等几秒再试。";
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
        String cleanPrompt = cleanMentions(messageContent, crb);
        AgentVisionAttachmentService.ImageContext sourceImage = selectVisionImage(
                config, chatRoomId, sourceMessage, messageContent);
        if (sourceImage.annotation() != null && !sourceImage.annotation().isBlank()) {
            cleanPrompt = cleanPrompt + "\n" + sourceImage.annotation();
        }
        task.setPrompt(cleanPrompt);
        task.setImageAttachments(sourceImage.attachments());
        if (sourceMessage != null
                && Boolean.TRUE.equals(sourceMessage.getIsAnonymous())
                && sourceMessage.getAnonymousIdentity() != null) {
            task.setAnonymousRequester(true);
            task.setAnonymousRequesterName(sourceMessage.getAnonymousIdentity().getAnonymousName());
        }
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
        String cleaned = (userMessage != null ? userMessage : "")
                .replaceAll("@" + Pattern.quote(config.getBotName()) + "\\s*", "")
                .replaceAll("@" + Pattern.quote(roomDisplayName(crb)) + "\\s*", "")
                .trim();
        return cleaned.isEmpty()
                ? MENTION_ONLY_MARKER + " The user only mentioned this bot. Infer the intended request from the immediately preceding relevant user message in the recent conversation, and answer that request instead of greeting generically."
                : cleaned;
    }

    private List<BotDto.ChatMessage> buildContext(Long chatRoomId, ChatRoomBot crb, String userMessage, Message sourceMessage) {
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

        // Clean @mention and attach the relevant current/replied/recent room image.
        String cleanMessage = cleanMentions(userMessage, crb);
        AgentVisionAttachmentService.ImageContext sourceImage = selectVisionImage(
                config, chatRoomId, sourceMessage, userMessage);
        if (sourceImage.annotation() != null && !sourceImage.annotation().isBlank()) {
            cleanMessage = cleanMessage + "\n" + sourceImage.annotation();
        }

        messages.clear();
        messages.add(new BotDto.ChatMessage("system", buildRoomAwareOneShotSystemPrompt(chatRoomId, crb, cleanMessage, sourceMessage)));
        if (config.getCharacterPostHistoryInstructions() != null
                && !config.getCharacterPostHistoryInstructions().isBlank()) {
            messages.add(new BotDto.ChatMessage("system", config.getCharacterPostHistoryInstructions().trim()));
        }
        messages.add(BotDto.ChatMessage.userWithImages(cleanMessage, sourceImage.attachments()));

        return messages;
    }

    private String buildRoomAwareOneShotSystemPrompt(Long chatRoomId, ChatRoomBot crb, String cleanMessage, Message sourceMessage) {
        BotConfig config = crb.getBotConfig();
        ChatRoom chatRoom = crb.getChatRoom();
        if (chatRoom == null && sourceMessage != null) {
            chatRoom = sourceMessage.getChatRoom();
        }
        if (chatRoom == null && chatRoomId != null) {
            chatRoom = chatRoomRepository.findById(chatRoomId).orElse(null);
        }
        User requester = sourceMessage != null ? sourceMessage.getSender() : null;
        if (requester == null && config.getCreatedBy() != null) {
            requester = config.getCreatedBy();
        }
        if (requester == null && chatRoom != null) {
            requester = chatRoom.getCreatedBy();
        }

        AgentTask task = new AgentTask();
        task.setChatRoom(chatRoom);
        task.setRequestedBy(requester);
        task.setBotConfig(config);
        task.setPrompt(cleanMessage);
        if (sourceMessage != null
                && Boolean.TRUE.equals(sourceMessage.getIsAnonymous())
                && sourceMessage.getAnonymousIdentity() != null) {
            task.setAnonymousRequester(true);
            task.setAnonymousRequesterName(sourceMessage.getAnonymousIdentity().getAnonymousName());
        }

        AgentContextBuilder.AgentContextEnvelope envelope = agentContextBuilder.buildContext(task);
        String prompt = agentContextBuilder.assembleSystemPrompt(envelope);
        String displayName = roomDisplayName(crb);
        StringBuilder builder = new StringBuilder(prompt == null ? "" : prompt.strip());
        builder.append("\n\n[ROOM BOT BINDING]\n")
                .append("In this room, your visible bot name is \"")
                .append(displayName)
                .append("\". Know that you are currently speaking inside this room as that bot. ")
                .append("Use the room context and recent conversation when the user only mentions you or asks a short follow-up.\n");
        if (crb.getRoomPromptSuffix() != null && !crb.getRoomPromptSuffix().isBlank()) {
            builder.append("\n[ROOM-SPECIFIC INSTRUCTIONS]\n")
                    .append(crb.getRoomPromptSuffix().trim())
                    .append("\n");
        }
        return builder.toString();
    }

    private AgentVisionAttachmentService.ImageContext selectVisionImage(
            BotConfig config,
            Long chatRoomId,
            Message sourceMessage,
            String prompt) {
        BotVisionAttachmentSelector.Selection selection = botVisionAttachmentSelector.select(
                config, chatRoomId, sourceMessage, prompt);
        return selection != null && selection.image() != null
                ? selection.image()
                : AgentVisionAttachmentService.ImageContext.empty();
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
        message.setBotDisplayName(displayName);
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
        dto.setMaxHistoryMessages(entity.getMaxHistoryMessages() != null ? entity.getMaxHistoryMessages() : 20);
        dto.setIncludeRoomMetadata(entity.getIncludeRoomMetadata() == null || entity.getIncludeRoomMetadata());
        dto.setVisionInputEnabled(!Boolean.FALSE.equals(entity.getVisionInputEnabled()));
        dto.setHistoryImageInspectionEnabled(!Boolean.FALSE.equals(entity.getHistoryImageInspectionEnabled()));
        dto.setReplyMode(entity.getReplyMode() != null
                ? entity.getReplyMode()
                : BotConfig.ReplyMode.SINGLE);
        dto.setWorkflowMode(entity.getWorkflowMode() != null
                ? entity.getWorkflowMode()
                : BotConfig.WorkflowMode.SINGLE_PASS);
        dto.setImageGenerationProvider(entity.getImageGenerationProvider() != null
                ? entity.getImageGenerationProvider()
                : BotConfig.ImageGenerationProvider.HERMES);
        dto.setImageModel(entity.getImageModel());
        dto.setImageNegativePrompt(entity.getImageNegativePrompt());
        if (includeCredentialDetails && entity.getImageProviderCredential() != null) {
            dto.setImageProviderCredentialId(entity.getImageProviderCredential().getId());
            dto.setImageProviderCredentialLabel(entity.getImageProviderCredential().getLabel());
            dto.setImageProviderCredentialLast4(entity.getImageProviderCredential().getSecretLast4());
        }
        dto.setHasImageProviderCredential(entity.getImageProviderCredential() != null);
        dto.setIsActive(entity.getIsActive());
        if (includeCredentialDetails && entity.getProviderCredential() != null) {
            dto.setProviderCredentialId(entity.getProviderCredential().getId());
            dto.setProviderCredentialLabel(entity.getProviderCredential().getLabel());
            dto.setProviderCredentialLast4(entity.getProviderCredential().getSecretLast4());
        }
        dto.setHasCredential(entity.getProviderCredential() != null
                || (entity.getApiKeyEncrypted() != null && !entity.getApiKeyEncrypted().isBlank()));
        if (entity.getCreatedBy() != null) {
            dto.setCreatedById(entity.getCreatedBy().getId());
        }
        dto.setHasCharacterCard(entity.getCharacterCardJson() != null && !entity.getCharacterCardJson().isBlank());
        dto.setCharacterPersona(entity.getCharacterPersona());
        dto.setCharacterScenario(entity.getCharacterScenario());
        dto.setCharacterFirstMes(entity.getCharacterFirstMes());
        dto.setCharacterAlternateGreetings(readStringList(entity.getCharacterAlternateGreetings()));
        dto.setCharacterBookEntryCount(countCharacterBookEntries(entity.getCharacterBookJson()));
        dto.setEnabledTools(readStringList(entity.getEnabledTools()));
        dto.setAccessPolicy(entity.getAccessPolicy() != null
                ? entity.getAccessPolicy()
                : BotConfig.AccessPolicy.PRIVATE);
        dto.setAllowedUsers(includeCredentialDetails && entity.getId() != null
                ? readAllowedUsers(entity.getId())
                : List.of());
        dto.setInboundTokenLast4(entity.getInboundTokenLast4());
        dto.setInboundTokenScopes(readGatewayScopes(entity.getInboundTokenScopes()));
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    private List<BotDto.AllowedUser> readAllowedUsers(Long botId) {
        return botAllowedUserRepository.findByBotConfigIdOrderByUserUsernameAsc(botId).stream()
                .map(allowed -> new BotDto.AllowedUser(
                        allowed.getUser().getId(),
                        allowed.getUser().getUsername(),
                        allowed.getUser().getDisplayName()))
                .toList();
    }

    private List<String> readGatewayScopes(String rawScopes) {
        if (rawScopes == null || rawScopes.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rawScopes.split("[,\\s]+"))
                .map(String::trim)
                .filter(scope -> !scope.isBlank())
                .toList();
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

    private void applyImageGenerationSettings(
            BotConfig bot,
            Long ownerId,
            BotConfig.ImageGenerationProvider requestedProvider,
            Long credentialId,
            String rawApiKey,
            String baseUrl,
            String model,
            String negativePrompt) {
        BotConfig.ImageGenerationProvider provider = requestedProvider != null
                ? requestedProvider
                : bot.getImageGenerationProvider() != null
                        ? bot.getImageGenerationProvider()
                        : BotConfig.ImageGenerationProvider.HERMES;
        bot.setImageGenerationProvider(provider);
        if (model != null) {
            bot.setImageModel(model.isBlank() ? null : model.trim());
        }
        if (negativePrompt != null) {
            bot.setImageNegativePrompt(negativePrompt.isBlank() ? null : negativePrompt.trim());
        }

        if (provider == BotConfig.ImageGenerationProvider.HERMES) {
            bot.setImageProviderCredential(null);
            return;
        }
        if (credentialId != null) {
            ProviderCredential credential = providerCredentialService.getOwnedCredential(ownerId, credentialId);
            validateImageCredentialProvider(provider, credential);
            bot.setImageProviderCredential(credential);
            return;
        }
        if (rawApiKey != null && !rawApiKey.isBlank()) {
            BotConfig.LLMProvider credentialProvider = provider == BotConfig.ImageGenerationProvider.NOVELAI
                    ? BotConfig.LLMProvider.NOVELAI
                    : BotConfig.LLMProvider.IMAGE_API;
            String endpoint = baseUrl;
            if (provider == BotConfig.ImageGenerationProvider.NOVELAI
                    && (endpoint == null || endpoint.isBlank())) {
                endpoint = "https://image.novelai.net/ai/generate-image";
            }
            ProviderCredential credential = providerCredentialService.createForBot(
                    ownerId,
                    credentialProvider,
                    bot.getBotName() + " image key " + System.currentTimeMillis(),
                    rawApiKey.trim(),
                    endpoint,
                    model);
            bot.setImageProviderCredential(credential);
        }
    }

    private void validateImageCredentialProvider(
            BotConfig.ImageGenerationProvider provider,
            ProviderCredential credential) {
        boolean valid = switch (provider) {
            case HERMES -> false;
            case NOVELAI -> credential.getLlmProvider() == BotConfig.LLMProvider.NOVELAI;
            case OPENAI_COMPATIBLE -> credential.getLlmProvider() == BotConfig.LLMProvider.IMAGE_API
                    || credential.getLlmProvider() == BotConfig.LLMProvider.OPENAI;
        };
        if (!valid) {
            throw new IllegalArgumentException("画图凭据与选择的图片 Provider 不匹配");
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

    private void replaceAllowedUsers(BotConfig bot, List<Long> userIds, List<String> usernames) {
        if (bot.getId() == null) {
            return;
        }
        botAllowedUserRepository.deleteByBotConfigId(bot.getId());
        List<User> users = resolveAllowedUsers(userIds, usernames);
        for (User user : users) {
            if (bot.getCreatedBy() != null && bot.getCreatedBy().getId().equals(user.getId())) {
                continue;
            }
            BotAllowedUser allowed = new BotAllowedUser();
            allowed.setBotConfig(bot);
            allowed.setUser(user);
            botAllowedUserRepository.save(allowed);
        }
    }

    private List<User> resolveAllowedUsers(List<Long> userIds, List<String> usernames) {
        LinkedHashMap<Long, User> resolved = new LinkedHashMap<>();
        List<Long> ids = userIds == null ? List.of() : userIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!ids.isEmpty()) {
            for (User user : userRepository.findByIdIn(ids)) {
                resolved.put(user.getId(), user);
            }
        }

        List<String> tokens = normalizeUserTokens(usernames);
        List<String> names = new ArrayList<>();
        List<Long> numericIds = new ArrayList<>();
        for (String token : tokens) {
            try {
                numericIds.add(Long.valueOf(token));
            } catch (NumberFormatException ignored) {
                names.add(token);
            }
        }
        if (!numericIds.isEmpty()) {
            for (User user : userRepository.findByIdIn(numericIds.stream().distinct().toList())) {
                resolved.put(user.getId(), user);
            }
        }
        if (!names.isEmpty()) {
            for (User user : userRepository.findByUsernameIn(names.stream().distinct().toList())) {
                resolved.put(user.getId(), user);
            }
        }

        List<String> missing = new ArrayList<>();
        for (String token : tokens) {
            boolean found = resolved.values().stream().anyMatch(user ->
                    token.equals(user.getUsername()) || token.equals(String.valueOf(user.getId())));
            if (!found) missing.add(token);
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("找不到允许使用者: " + String.join(", ", missing));
        }
        return new ArrayList<>(resolved.values());
    }

    private List<String> normalizeUserTokens(List<String> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            return List.of();
        }
        return rawValues.stream()
                .filter(Objects::nonNull)
                .flatMap(value -> Arrays.stream(value.split("[,，\\s]+")))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
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
        boolean allowedToInstall = isBotOwner || (isRoomAdmin && canTriggerBot(bot, operatorId));
        if (!allowedToInstall) {
            throw new IllegalArgumentException("无权限管理该聊天室机器人");
        }
    }

    private boolean canTriggerBot(BotConfig bot, Long userId) {
        if (bot.getCreatedBy() == null) {
            return true;
        }
        if (userId == null) {
            return false;
        }
        if (bot.getCreatedBy().getId().equals(userId)) {
            return true;
        }
        BotConfig.AccessPolicy policy = bot.getAccessPolicy() != null
                ? bot.getAccessPolicy()
                : BotConfig.AccessPolicy.PRIVATE;
        return switch (policy) {
            case PUBLIC -> true;
            case ALLOWLIST -> botAllowedUserRepository.existsByBotConfigIdAndUserId(bot.getId(), userId);
            case PRIVATE -> false;
        };
    }
}
