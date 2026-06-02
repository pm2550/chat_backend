package com.chatapp.service;

import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomBot;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomBotRepository;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.websocket.RawWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Inbound bot gateway (Phase 4 / F1): an external service authenticated by a bot
 * token posts a message AS the bot into a room it is bound to. Reuses the normal
 * Message + WebSocket fan-out path so the post renders exactly like an in-app bot reply.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BotGatewayService {

    private static final int MAX_CONTENT_LENGTH = 8000;

    private final ChatRoomBotRepository chatRoomBotRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final RawWebSocketHandler rawWebSocketHandler;

    /** Room ids this bot is actively bound to (where it may post). */
    @Transactional(readOnly = true)
    public List<Long> boundRoomIds(BotConfig bot) {
        return chatRoomBotRepository.findByBotConfigIdAndIsActiveTrue(bot.getId()).stream()
                .map(crb -> crb.getChatRoom().getId())
                .toList();
    }

    /**
     * Post a plain-text message as {@code bot} into {@code chatRoomId}. The bot must be
     * bound to the room. Author is the bot's owner (or room creator) so existing
     * sender-not-null invariants hold; the bot identity rides on message.botConfig.
     */
    @Transactional
    public Message postAsBot(BotConfig bot, Long chatRoomId, String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        ChatRoomBot binding = chatRoomBotRepository
                .findByChatRoomIdAndBotConfigId(chatRoomId, bot.getId())
                .orElseThrow(() -> new AccessDeniedException("机器人未加入该聊天室"));
        if (Boolean.FALSE.equals(binding.getIsActive())) {
            throw new AccessDeniedException("机器人在该聊天室已禁用");
        }
        ChatRoom room = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new IllegalArgumentException("聊天室不存在"));

        String content = rawContent.length() > MAX_CONTENT_LENGTH
                ? rawContent.substring(0, MAX_CONTENT_LENGTH)
                : rawContent;

        Long senderId = bot.getCreatedBy() != null
                ? bot.getCreatedBy().getId()
                : room.getCreatedBy().getId();
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new IllegalStateException("机器人发送者不存在"));

        Message message = new Message();
        message.setChatRoom(room);
        message.setSender(sender);
        message.setBotConfig(bot);
        message.setMessageType(Message.MessageType.TEXT);
        message.setMessageStatus(Message.MessageStatus.SENT);
        message.setContent(content);
        message = messageRepository.save(message);

        chatRoomRepository.incrementUnreadForRoomMembersExcept(chatRoomId, senderId);
        rawWebSocketHandler.broadcastMessage(message);
        log.info("inbound bot post: bot={} room={} messageId={}", bot.getId(), chatRoomId, message.getId());
        return message;
    }
}
