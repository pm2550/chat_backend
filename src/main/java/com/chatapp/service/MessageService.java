package com.chatapp.service;

import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息服务类
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class MessageService {

    private final MessageRepository messageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;

    /**
     * 发送消息
     */
    public Message sendMessage(Long senderId, Long chatRoomId, String content, Message.MessageType messageType) {
        // 验证发送者
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new RuntimeException("发送者不存在"));

        // 验证聊天室
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));

        // 检查用户是否是聊天室成员
        if (!chatRoomRepository.isMember(chatRoomId, senderId)) {
            throw new IllegalArgumentException("您不是该聊天室的成员");
        }

        // 检查用户是否被禁言
        if (chatRoomRepository.isMuted(chatRoomId, senderId)) {
            throw new IllegalArgumentException("您在该聊天室中被禁言");
        }

        // 创建消息
        Message message = new Message();
        message.setContent(content);
        message.setMessageType(messageType);
        message.setSender(sender);
        message.setChatRoom(chatRoom);
        message.setCreatedAt(LocalDateTime.now());
        message.setMessageStatus(Message.MessageStatus.SENT);

        message = messageRepository.save(message);

        log.info("用户 {} 在聊天室 {} 发送消息: {}", senderId, chatRoomId, message.getId());
        return message;
    }

    /**
     * 发送文件消息
     */
    public Message sendFileMessage(Long senderId, Long chatRoomId, String fileName, String fileUrl, 
                                 String fileType, Long fileSize, Message.MessageType messageType) {
        // 验证发送者和聊天室
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new RuntimeException("发送者不存在"));
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));

        // 权限检查
        if (!chatRoomRepository.isMember(chatRoomId, senderId)) {
            throw new IllegalArgumentException("您不是该聊天室的成员");
        }
        if (chatRoomRepository.isMuted(chatRoomId, senderId)) {
            throw new IllegalArgumentException("您在该聊天室中被禁言");
        }

        // 创建文件消息
        Message message = new Message();
        message.setContent(fileName); // 文件名作为内容
        message.setMessageType(messageType);
        message.setSender(sender);
        message.setChatRoom(chatRoom);
        message.setFileUrl(fileUrl);
        message.setFileName(fileName);
        message.setFileType(fileType);
        message.setFileSize(fileSize);
        message.setCreatedAt(LocalDateTime.now());
        message.setMessageStatus(Message.MessageStatus.SENT);

        message = messageRepository.save(message);

        log.info("用户 {} 在聊天室 {} 发送文件: {} (类型: {})", 
                senderId, chatRoomId, fileName, messageType);
        return message;
    }

    /**
     * 回复消息
     */
    public Message replyToMessage(Long senderId, Long chatRoomId, Long replyToMessageId, 
                                String content, Message.MessageType messageType) {
        // 验证原消息
        Message replyToMessage = messageRepository.findById(replyToMessageId)
                .orElseThrow(() -> new RuntimeException("回复的消息不存在"));

        // 确保回复的消息在同一个聊天室
        if (!replyToMessage.getChatRoom().getId().equals(chatRoomId)) {
            throw new IllegalArgumentException("只能回复同一聊天室的消息");
        }

        // 发送回复消息
        Message message = sendMessage(senderId, chatRoomId, content, messageType);
        message.setReplyToMessage(replyToMessage);
        
        return messageRepository.save(message);
    }

    /**
     * 获取聊天室消息（分页）
     */
    public Page<Message> getChatRoomMessages(Long chatRoomId, Long userId, Pageable pageable) {
        // 验证用户权限
        if (!chatRoomRepository.isMember(chatRoomId, userId)) {
            throw new IllegalArgumentException("您不是该聊天室的成员");
        }

        return messageRepository.findByChatRoomIdOrderByCreatedAtDesc(chatRoomId, pageable);
    }

    /**
     * 获取最新消息
     */
    public List<Message> getRecentMessages(Long chatRoomId, Long userId, int limit) {
        // 验证用户权限
        if (!chatRoomRepository.isMember(chatRoomId, userId)) {
            throw new IllegalArgumentException("您不是该聊天室的成员");
        }

        return messageRepository.findRecentMessages(chatRoomId, limit);
    }

    /**
     * 标记消息为已读
     */
    public void markMessageAsRead(Long messageId, Long userId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("消息不存在"));

        // 检查用户是否有权限查看此消息
        if (!chatRoomRepository.isMember(message.getChatRoom().getId(), userId)) {
            throw new IllegalArgumentException("您无权限查看此消息");
        }

        // 不能标记自己的消息为已读
        if (message.getSender().getId().equals(userId)) {
            return;
        }

        // 标记为已读
        messageRepository.markAsRead(messageId, userId);
        
        log.debug("用户 {} 标记消息 {} 为已读", userId, messageId);
    }

    /**
     * 撤回消息
     */
    public void recallMessage(Long messageId, Long userId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("消息不存在"));

        // 只能撤回自己的消息
        if (!message.getSender().getId().equals(userId)) {
            throw new IllegalArgumentException("只能撤回自己的消息");
        }

        // 检查撤回时间限制（2分钟内）
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime messageTime = message.getCreatedAt();
        if (now.minusMinutes(2).isAfter(messageTime)) {
            throw new IllegalArgumentException("消息发送超过2分钟，无法撤回");
        }

        // 标记为已删除
        message.setIsDeleted(true);
        message.setContent("[消息已撤回]");
        messageRepository.save(message);

        log.info("用户 {} 撤回了消息 {}", userId, messageId);
    }

    /**
     * 删除消息（管理员）
     */
    public void deleteMessage(Long messageId, Long operatorId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("消息不存在"));

        Long chatRoomId = message.getChatRoom().getId();

        // 检查操作者权限
        if (!message.getSender().getId().equals(operatorId) && 
            !chatRoomRepository.isAdmin(chatRoomId, operatorId)) {
            throw new IllegalArgumentException("无权限删除此消息");
        }

        // 标记为已删除
        message.setIsDeleted(true);
        message.setContent("[消息已删除]");
        messageRepository.save(message);

        log.info("用户 {} 删除了消息 {} (聊天室: {})", operatorId, messageId, chatRoomId);
    }

    /**
     * 搜索消息
     */
    public Page<Message> searchMessages(Long chatRoomId, Long userId, String keyword, Pageable pageable) {
        // 验证用户权限
        if (!chatRoomRepository.isMember(chatRoomId, userId)) {
            throw new IllegalArgumentException("您不是该聊天室的成员");
        }

        return messageRepository.searchInChatRoom(chatRoomId, keyword, pageable);
    }

    /**
     * 获取未读消息数量
     */
    public Long getUnreadMessageCount(Long chatRoomId, Long userId) {
        // 验证用户权限
        if (!chatRoomRepository.isMember(chatRoomId, userId)) {
            return 0L;
        }

        return messageRepository.countUnreadMessages(chatRoomId, userId);
    }

    /**
     * 获取用户在所有聊天室的未读消息总数
     */
    public Long getTotalUnreadCount(Long userId) {
        return messageRepository.countTotalUnreadMessages(userId);
    }

    /**
     * 标记聊天室所有消息为已读
     */
    public void markAllMessagesAsRead(Long chatRoomId, Long userId) {
        // 验证用户权限
        if (!chatRoomRepository.isMember(chatRoomId, userId)) {
            throw new IllegalArgumentException("您不是该聊天室的成员");
        }

        messageRepository.markAllAsReadInChatRoom(chatRoomId, userId);
        
        log.info("用户 {} 标记聊天室 {} 所有消息为已读", userId, chatRoomId);
    }

    /**
     * 获取消息统计信息
     */
    public MessageStats getMessageStats(Long chatRoomId, Long userId) {
        // 验证用户权限
        if (!chatRoomRepository.isMember(chatRoomId, userId)) {
            throw new IllegalArgumentException("您不是该聊天室的成员");
        }

        Long totalCount = messageRepository.countByChatRoomId(chatRoomId);
        Long unreadCount = messageRepository.countUnreadMessages(chatRoomId, userId);
        Message lastMessage = messageRepository.findLastMessage(chatRoomId);

        return new MessageStats(totalCount, unreadCount, lastMessage);
    }

    /**
     * 消息统计信息类
     */
    public static class MessageStats {
        private final Long totalCount;
        private final Long unreadCount;
        private final Message lastMessage;

        public MessageStats(Long totalCount, Long unreadCount, Message lastMessage) {
            this.totalCount = totalCount;
            this.unreadCount = unreadCount;
            this.lastMessage = lastMessage;
        }

        public Long getTotalCount() { return totalCount; }
        public Long getUnreadCount() { return unreadCount; }
        public Message getLastMessage() { return lastMessage; }
    }
} 