package com.chatapp.service;

import com.chatapp.entity.ChatHistory;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatHistoryRepository;
import com.chatapp.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 聊天记录服务
 */
@Service
@Transactional
public class ChatHistoryService {

    @Autowired
    private ChatHistoryRepository chatHistoryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FileStorageService fileStorageService;

    /**
     * 发送私聊消息
     */
    public ChatHistory sendPrivateMessage(Long senderId, Long receiverId, String content) {
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new RuntimeException("发送者不存在"));

        User receiver = userRepository.findById(receiverId)
                .orElseThrow(() -> new RuntimeException("接收者不存在"));

        ChatHistory chatHistory = new ChatHistory();
        chatHistory.setSenderId(senderId);
        chatHistory.setReceiverId(receiverId);
        chatHistory.setContent(content);
        chatHistory.setMessageType(ChatHistory.MessageType.TEXT);
        chatHistory.setSenderName(sender.getDisplayName());
        chatHistory.setSenderAvatar(sender.getAvatarUrl());

        return chatHistoryRepository.save(chatHistory);
    }

    /**
     * 发送群聊消息
     */
    public ChatHistory sendGroupMessage(Long senderId, Long chatRoomId, String content) {
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new RuntimeException("发送者不存在"));

        ChatHistory chatHistory = new ChatHistory();
        chatHistory.setSenderId(senderId);
        chatHistory.setChatRoomId(chatRoomId);
        chatHistory.setContent(content);
        chatHistory.setMessageType(ChatHistory.MessageType.TEXT);
        chatHistory.setSenderName(sender.getDisplayName());
        chatHistory.setSenderAvatar(sender.getAvatarUrl());

        return chatHistoryRepository.save(chatHistory);
    }

    /**
     * 发送文件消息（私聊）
     */
    public ChatHistory sendPrivateFileMessage(Long senderId, Long receiverId, 
                                            MultipartFile file, ChatHistory.MessageType messageType) throws IOException {
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new RuntimeException("发送者不存在"));

        User receiver = userRepository.findById(receiverId)
                .orElseThrow(() -> new RuntimeException("接收者不存在"));

        // 上传文件
        String fileUrl;
        if (messageType == ChatHistory.MessageType.IMAGE) {
            fileUrl = fileStorageService.uploadAvatar(file); // 图片使用头像上传逻辑
        } else {
            fileUrl = fileStorageService.uploadChatFile(file);
        }

        ChatHistory chatHistory = new ChatHistory();
        chatHistory.setSenderId(senderId);
        chatHistory.setReceiverId(receiverId);
        chatHistory.setContent("[文件]" + file.getOriginalFilename());
        chatHistory.setMessageType(messageType);
        chatHistory.setFileUrl(fileUrl);
        chatHistory.setFileName(file.getOriginalFilename());
        chatHistory.setFileSize(file.getSize());
        chatHistory.setSenderName(sender.getDisplayName());
        chatHistory.setSenderAvatar(sender.getAvatarUrl());

        return chatHistoryRepository.save(chatHistory);
    }

    /**
     * 发送文件消息（群聊）
     */
    public ChatHistory sendGroupFileMessage(Long senderId, Long chatRoomId, 
                                          MultipartFile file, ChatHistory.MessageType messageType) throws IOException {
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new RuntimeException("发送者不存在"));

        // 上传文件
        String fileUrl;
        if (messageType == ChatHistory.MessageType.IMAGE) {
            fileUrl = fileStorageService.uploadAvatar(file); // 图片使用头像上传逻辑
        } else {
            fileUrl = fileStorageService.uploadChatFile(file);
        }

        ChatHistory chatHistory = new ChatHistory();
        chatHistory.setSenderId(senderId);
        chatHistory.setChatRoomId(chatRoomId);
        chatHistory.setContent("[文件]" + file.getOriginalFilename());
        chatHistory.setMessageType(messageType);
        chatHistory.setFileUrl(fileUrl);
        chatHistory.setFileName(file.getOriginalFilename());
        chatHistory.setFileSize(file.getSize());
        chatHistory.setSenderName(sender.getDisplayName());
        chatHistory.setSenderAvatar(sender.getAvatarUrl());

        return chatHistoryRepository.save(chatHistory);
    }

    /**
     * 回复消息（私聊）
     */
    public ChatHistory replyToPrivateMessage(Long senderId, Long receiverId, 
                                           String content, Long replyToId) {
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new RuntimeException("发送者不存在"));

        // 验证被回复的消息是否存在
        ChatHistory replyToMessage = chatHistoryRepository.findByIdAndIsDeletedFalse(replyToId)
                .orElseThrow(() -> new RuntimeException("被回复的消息不存在"));

        ChatHistory chatHistory = new ChatHistory();
        chatHistory.setSenderId(senderId);
        chatHistory.setReceiverId(receiverId);
        chatHistory.setContent(content);
        chatHistory.setMessageType(ChatHistory.MessageType.TEXT);
        chatHistory.setReplyToId(replyToId);
        chatHistory.setSenderName(sender.getDisplayName());
        chatHistory.setSenderAvatar(sender.getAvatarUrl());

        return chatHistoryRepository.save(chatHistory);
    }

    /**
     * 回复消息（群聊）
     */
    public ChatHistory replyToGroupMessage(Long senderId, Long chatRoomId, 
                                         String content, Long replyToId) {
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new RuntimeException("发送者不存在"));

        // 验证被回复的消息是否存在
        ChatHistory replyToMessage = chatHistoryRepository.findByIdAndIsDeletedFalse(replyToId)
                .orElseThrow(() -> new RuntimeException("被回复的消息不存在"));

        ChatHistory chatHistory = new ChatHistory();
        chatHistory.setSenderId(senderId);
        chatHistory.setChatRoomId(chatRoomId);
        chatHistory.setContent(content);
        chatHistory.setMessageType(ChatHistory.MessageType.TEXT);
        chatHistory.setReplyToId(replyToId);
        chatHistory.setSenderName(sender.getDisplayName());
        chatHistory.setSenderAvatar(sender.getAvatarUrl());

        return chatHistoryRepository.save(chatHistory);
    }

    /**
     * 获取私聊历史记录
     */
    public Page<ChatHistory> getPrivateChatHistory(Long userId1, Long userId2, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return chatHistoryRepository.findPrivateChatHistory(userId1, userId2, pageable);
    }

    /**
     * 获取群聊历史记录
     */
    public Page<ChatHistory> getGroupChatHistory(Long chatRoomId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return chatHistoryRepository.findGroupChatHistory(chatRoomId, pageable);
    }

    /**
     * 获取私聊最新消息
     */
    public List<ChatHistory> getLatestPrivateMessages(Long userId1, Long userId2, int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return chatHistoryRepository.findLatestPrivateMessages(userId1, userId2, pageable);
    }

    /**
     * 获取群聊最新消息
     */
    public List<ChatHistory> getLatestGroupMessages(Long chatRoomId, int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return chatHistoryRepository.findLatestGroupMessages(chatRoomId, pageable);
    }

    /**
     * 搜索私聊消息
     */
    public Page<ChatHistory> searchPrivateMessages(Long userId1, Long userId2, String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return chatHistoryRepository.searchPrivateChatHistory(userId1, userId2, keyword, pageable);
    }

    /**
     * 搜索群聊消息
     */
    public Page<ChatHistory> searchGroupMessages(Long chatRoomId, String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return chatHistoryRepository.searchGroupChatHistory(chatRoomId, keyword, pageable);
    }

    /**
     * 撤回消息
     */
    public ChatHistory recallMessage(Long messageId, Long userId) {
        ChatHistory message = chatHistoryRepository.findByIdAndIsDeletedFalse(messageId)
                .orElseThrow(() -> new RuntimeException("消息不存在"));

        // 验证是否是消息发送者
        if (!message.getSenderId().equals(userId)) {
            throw new RuntimeException("只能撤回自己发送的消息");
        }

        // 检查是否在撤回时间限制内（2分钟）
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime sentTime = message.getSentAt();
        if (sentTime.plusMinutes(2).isBefore(now)) {
            throw new RuntimeException("消息发送超过2分钟，无法撤回");
        }

        message.setIsRecalled(true);
        message.setRecalledAt(now);
        message.setContent("[消息已撤回]");

        return chatHistoryRepository.save(message);
    }

    /**
     * 删除消息
     */
    public void deleteMessage(Long messageId, Long userId) {
        ChatHistory message = chatHistoryRepository.findByIdAndIsDeletedFalse(messageId)
                .orElseThrow(() -> new RuntimeException("消息不存在"));

        // 验证是否是消息发送者
        if (!message.getSenderId().equals(userId)) {
            throw new RuntimeException("只能删除自己发送的消息");
        }

        message.setIsDeleted(true);
        message.setDeletedAt(LocalDateTime.now());

        // 如果是文件消息，删除文件
        if (message.getFileUrl() != null && !message.getFileUrl().isEmpty()) {
            fileStorageService.deleteFile(message.getFileUrl());
        }

        chatHistoryRepository.save(message);
    }

    /**
     * 获取用户的聊天记录
     */
    public Page<ChatHistory> getUserChatHistory(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return chatHistoryRepository.findUserChatHistory(userId, pageable);
    }

    /**
     * 统计私聊消息数量
     */
    public long countPrivateMessages(Long userId1, Long userId2) {
        return chatHistoryRepository.countPrivateMessages(userId1, userId2);
    }

    /**
     * 统计群聊消息数量
     */
    public long countGroupMessages(Long chatRoomId) {
        return chatHistoryRepository.countGroupMessages(chatRoomId);
    }

    /**
     * 获取指定时间范围内的私聊消息
     */
    public List<ChatHistory> getPrivateMessagesInTimeRange(Long userId1, Long userId2, 
                                                          LocalDateTime startTime, LocalDateTime endTime) {
        return chatHistoryRepository.findPrivateMessagesInTimeRange(userId1, userId2, startTime, endTime);
    }

    /**
     * 获取指定时间范围内的群聊消息
     */
    public List<ChatHistory> getGroupMessagesInTimeRange(Long chatRoomId, 
                                                        LocalDateTime startTime, LocalDateTime endTime) {
        return chatHistoryRepository.findGroupMessagesInTimeRange(chatRoomId, startTime, endTime);
    }

    /**
     * 清理旧消息
     */
    @Transactional
    public int cleanupOldMessages(int daysToKeep) {
        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(daysToKeep);
        return chatHistoryRepository.markMessagesAsDeletedBefore(cutoffTime);
    }

    /**
     * 根据ID获取消息
     */
    public Optional<ChatHistory> getMessageById(Long messageId) {
        return chatHistoryRepository.findByIdAndIsDeletedFalse(messageId);
    }
} 