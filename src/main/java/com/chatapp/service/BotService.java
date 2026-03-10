package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.*;
import com.chatapp.repository.BotConfigRepository;
import com.chatapp.repository.ChatRoomBotRepository;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BotService {

    private final BotConfigRepository botConfigRepository;
    private final ChatRoomBotRepository chatRoomBotRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final LLMService llmService;

    @Transactional
    public BotDto createBot(Long creatorId, BotDto.CreateRequest request) {
        User creator = userRepository.findById(creatorId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        BotConfig bot = new BotConfig();
        bot.setBotName(request.getBotName());
        bot.setBotAvatar(request.getBotAvatar());
        bot.setLlmProvider(request.getLlmProvider());
        bot.setApiKeyEncrypted(request.getApiKey());
        bot.setModelName(request.getModelName());
        bot.setSystemPrompt(request.getSystemPrompt());
        bot.setTemperature(request.getTemperature() != null ? request.getTemperature() : 0.7);
        bot.setMaxTokens(request.getMaxTokens() != null ? request.getMaxTokens() : 2048);
        bot.setCreatedBy(creator);

        bot = botConfigRepository.save(bot);
        log.info("用户 {} 创建了机器人: {} ({})", creatorId, bot.getBotName(), bot.getLlmProvider());
        return toDto(bot);
    }

    @Transactional
    public BotDto updateBot(Long botId, BotDto.UpdateRequest request) {
        BotConfig bot = botConfigRepository.findById(botId)
                .orElseThrow(() -> new RuntimeException("机器人不存在"));

        if (request.getBotName() != null) bot.setBotName(request.getBotName());
        if (request.getBotAvatar() != null) bot.setBotAvatar(request.getBotAvatar());
        if (request.getApiKey() != null) bot.setApiKeyEncrypted(request.getApiKey());
        if (request.getModelName() != null) bot.setModelName(request.getModelName());
        if (request.getSystemPrompt() != null) bot.setSystemPrompt(request.getSystemPrompt());
        if (request.getTemperature() != null) bot.setTemperature(request.getTemperature());
        if (request.getMaxTokens() != null) bot.setMaxTokens(request.getMaxTokens());
        if (request.getIsActive() != null) bot.setIsActive(request.getIsActive());

        bot = botConfigRepository.save(bot);
        return toDto(bot);
    }

    public List<BotDto> getMyBots(Long userId) {
        return botConfigRepository.findByCreatedById(userId).stream()
                .map(this::toDto).collect(Collectors.toList());
    }

    public BotDto getBot(Long botId) {
        BotConfig bot = botConfigRepository.findById(botId)
                .orElseThrow(() -> new RuntimeException("机器人不存在"));
        return toDto(bot);
    }

    @Transactional
    public void addBotToChatRoom(Long chatRoomId, Long botId, BotDto.AddToChatRoomRequest request) {
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));
        BotConfig bot = botConfigRepository.findById(botId)
                .orElseThrow(() -> new RuntimeException("机器人不存在"));

        if (chatRoomBotRepository.findByChatRoomIdAndBotConfigId(chatRoomId, botId).isPresent()) {
            throw new IllegalArgumentException("该机器人已在聊天室中");
        }

        ChatRoomBot crb = new ChatRoomBot();
        crb.setChatRoom(chatRoom);
        crb.setBotConfig(bot);
        crb.setTriggerMode(request != null && request.getTriggerMode() != null ?
                request.getTriggerMode() : ChatRoomBot.TriggerMode.MENTION);
        crb.setTriggerKeywords(request != null ? request.getTriggerKeywords() : null);
        chatRoomBotRepository.save(crb);

        log.info("机器人 {} 已添加到聊天室 {}", bot.getBotName(), chatRoomId);
    }

    @Transactional
    public void removeBotFromChatRoom(Long chatRoomId, Long botId) {
        chatRoomBotRepository.deleteByChatRoomIdAndBotConfigId(chatRoomId, botId);
        log.info("机器人 {} 已从聊天室 {} 移除", botId, chatRoomId);
    }

    public List<BotDto> getBotsInChatRoom(Long chatRoomId) {
        return chatRoomBotRepository.findByChatRoomIdAndIsActiveTrue(chatRoomId).stream()
                .map(crb -> toDto(crb.getBotConfig()))
                .collect(Collectors.toList());
    }

    @Async
    public void processMessageForBots(Long chatRoomId, String messageContent, Long senderId) {
        List<ChatRoomBot> bots = chatRoomBotRepository.findActiveBotsWithConfig(chatRoomId);
        if (bots.isEmpty()) return;

        for (ChatRoomBot crb : bots) {
            boolean shouldRespond = switch (crb.getTriggerMode()) {
                case ALL -> true;
                case MENTION -> messageContent.contains("@" + crb.getBotConfig().getBotName());
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
                    List<BotDto.ChatMessage> chatMessages = buildContext(config, messageContent);
                    BotDto.LLMResponse response = llmService.chat(config, chatMessages);

                    // Save bot response as a message
                    saveBotMessage(chatRoomId, config, response.getContent());

                    log.info("机器人 {} 在聊天室 {} 回复了消息 (tokens: {})",
                            config.getBotName(), chatRoomId, response.getTokensUsed());
                } catch (Exception e) {
                    log.error("机器人 {} 处理消息失败: {}", crb.getBotConfig().getBotName(), e.getMessage());
                }
            }
        }
    }

    private List<BotDto.ChatMessage> buildContext(BotConfig config, String userMessage) {
        List<BotDto.ChatMessage> messages = new ArrayList<>();

        if (config.getSystemPrompt() != null && !config.getSystemPrompt().isEmpty()) {
            messages.add(new BotDto.ChatMessage("system", config.getSystemPrompt()));
        }

        // Clean @mention from message
        String cleanMessage = userMessage.replaceAll("@" + config.getBotName() + "\\s*", "").trim();
        if (cleanMessage.isEmpty()) {
            cleanMessage = "你好";
        }
        messages.add(new BotDto.ChatMessage("user", cleanMessage));

        return messages;
    }

    private void saveBotMessage(Long chatRoomId, BotConfig config, String content) {
        // Use first admin user as bot sender, or create a system message
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId).orElse(null);
        if (chatRoom == null) return;

        Message message = new Message();
        message.setContent("[" + config.getBotName() + "] " + content);
        message.setMessageType(Message.MessageType.SYSTEM);
        message.setChatRoom(chatRoom);
        // Bot messages need a sender - use the bot creator or chat room creator
        User botUser = config.getCreatedBy() != null ? config.getCreatedBy() : chatRoom.getCreatedBy();
        message.setSender(botUser);
        message.setMessageStatus(Message.MessageStatus.SENT);
        messageRepository.save(message);
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
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }
}
