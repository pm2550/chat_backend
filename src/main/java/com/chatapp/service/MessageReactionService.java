package com.chatapp.service;

import com.chatapp.dto.MessageDto;
import com.chatapp.entity.Message;
import com.chatapp.entity.MessageReaction;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageReactionRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MessageReactionService {

    private final MessageReactionRepository reactionRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ChatRoomRepository chatRoomRepository;

    @Transactional
    public List<MessageDto.ReactionInfo> addReaction(Long messageId, Long userId, String emoji) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("消息不存在"));
        validateMember(message, userId);
        String normalized = normalizeEmoji(emoji);
        if (reactionRepository.findByMessageIdAndUserIdAndEmoji(messageId, userId, normalized).isEmpty()) {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
            MessageReaction reaction = new MessageReaction();
            reaction.setMessage(message);
            reaction.setUser(user);
            reaction.setEmoji(normalized);
            reactionRepository.save(reaction);
        }
        return aggregate(messageId);
    }

    @Transactional
    public List<MessageDto.ReactionInfo> removeReaction(Long messageId, Long userId, String emoji) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("消息不存在"));
        validateMember(message, userId);
        reactionRepository.deleteByMessageIdAndUserIdAndEmoji(messageId, userId, normalizeEmoji(emoji));
        return aggregate(messageId);
    }

    @Transactional(readOnly = true)
    public List<MessageDto.ReactionInfo> aggregate(Long messageId) {
        return aggregate(messageId, null);
    }

    @Transactional(readOnly = true)
    public List<MessageDto.ReactionInfo> aggregate(Long messageId, Long currentUserId) {
        Map<String, List<Long>> grouped = new LinkedHashMap<>();
        for (MessageReaction reaction : reactionRepository.findByMessageId(messageId)) {
            grouped.computeIfAbsent(reaction.getEmoji(), ignored -> new ArrayList<>())
                    .add(reaction.getUser().getId());
        }
        return grouped.entrySet().stream()
                .map(entry -> new MessageDto.ReactionInfo(
                        entry.getKey(),
                        entry.getValue().size(),
                        entry.getValue(),
                        currentUserId != null && entry.getValue().contains(currentUserId)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MessageDto> attachAggregates(List<MessageDto> messages, Long currentUserId) {
        List<Long> messageIds = messages.stream()
                .map(MessageDto::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (messageIds.isEmpty()) {
            return messages;
        }

        Map<Long, Map<String, List<Long>>> groupedByMessage = new LinkedHashMap<>();
        for (MessageReaction reaction : reactionRepository.findByMessageIdIn(messageIds)) {
            if (reaction.getMessage() == null || reaction.getMessage().getId() == null
                    || reaction.getUser() == null || reaction.getUser().getId() == null) {
                continue;
            }
            Long messageId = reaction.getMessage().getId();
            groupedByMessage
                    .computeIfAbsent(messageId, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(reaction.getEmoji(), ignored -> new ArrayList<>())
                    .add(reaction.getUser().getId());
        }

        for (MessageDto message : messages) {
            Map<String, List<Long>> grouped = groupedByMessage.get(message.getId());
            if (grouped == null || grouped.isEmpty()) {
                message.setReactions(List.of());
                continue;
            }
            message.setReactions(grouped.entrySet().stream()
                    .map(entry -> new MessageDto.ReactionInfo(
                            entry.getKey(),
                            entry.getValue().size(),
                            entry.getValue(),
                            currentUserId != null && entry.getValue().contains(currentUserId)))
                    .toList());
        }
        return messages;
    }

    private void validateMember(Message message, Long userId) {
        Long roomId = message.getChatRoom().getId();
        if (!chatRoomRepository.isMember(roomId, userId)) {
            throw new IllegalArgumentException("您不是该聊天室成员");
        }
    }

    private String normalizeEmoji(String emoji) {
        String normalized = emoji == null ? "" : emoji.trim();
        if (normalized.isEmpty() || normalized.length() > 16) {
            throw new IllegalArgumentException("无效的反应表情");
        }
        return normalized;
    }
}
