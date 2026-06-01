package com.chatapp.service;

import com.chatapp.dto.MessageDto;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomClearState;
import com.chatapp.entity.ChatRoomMember;
import com.chatapp.entity.ChatRoomPinnedMessage;
import com.chatapp.entity.Message;
import com.chatapp.entity.MessageStar;
import com.chatapp.entity.User;
import com.chatapp.entity.AnonymousIdentity;
import com.chatapp.repository.ChatRoomClearStateRepository;
import com.chatapp.repository.ChatRoomPinnedMessageRepository;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.MessageReadReceiptRepository;
import com.chatapp.repository.MessageStarRepository;
import com.chatapp.repository.StickerRepository;
import com.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Base64;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 消息服务类
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class MessageService {
    private static final Pattern MENTION_PATTERN = Pattern.compile("(?<!\\\\)@([\\p{L}\\p{N}_\\-.]+)");

    private final MessageRepository messageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final AnonymousService anonymousService;
    private final ChatRoomClearStateRepository clearStateRepository;
    private final ChatRoomPinnedMessageRepository pinnedMessageRepository;
    private final MessageStarRepository messageStarRepository;

    @Autowired(required = false)
    private MessageLinkPreviewService linkPreviewService;

    @Autowired(required = false)
    private StickerRepository stickerRepository;

    @Autowired(required = false)
    private MessageReadReceiptRepository readReceiptRepository;

    /**
     * 发送消息
     */
    public Message sendMessage(Long senderId, Long chatRoomId, String content, Message.MessageType messageType) {
        return sendEncryptedMessage(senderId, chatRoomId, content, null, null, messageType);
    }

    public Message sendStickerMessage(Long senderId, Long chatRoomId, Long stickerId, boolean anonymous) {
        if (stickerRepository == null) {
            throw new IllegalStateException("贴纸服务未启用");
        }
        var sticker = stickerRepository.findById(stickerId)
                .orElseThrow(() -> new IllegalArgumentException("贴纸不存在"));
        Message message = anonymous
                ? sendAnonymousEncryptedMessage(senderId, chatRoomId, "[贴纸]", null, null, Message.MessageType.STICKER)
                : sendEncryptedMessage(senderId, chatRoomId, "[贴纸]", null, null, Message.MessageType.STICKER);
        message.setStickerId(sticker.getId());
        message.setFileUrl(sticker.getUrl());
        message.setFileName(sticker.getKeyword());
        message.setFileType("image/sticker");
        return messageRepository.save(message);
    }

    /**
     * 发送可选端到端加密消息。后端只负责保存密文信封，不解密用户内容。
     */
    public Message sendEncryptedMessage(Long senderId,
                                        Long chatRoomId,
                                        String content,
                                        String encryptedContentBase64,
                                        Integer encryptionVersion,
                                        Message.MessageType messageType) {
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new RuntimeException("发送者不存在"));
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));
        validateCanSendMessage(senderId, chatRoomId);

        // 创建消息
        Message message = new Message();
        boolean encrypted = encryptedContentBase64 != null && !encryptedContentBase64.isBlank();
        message.setContent(encrypted && (content == null || content.isBlank()) ? "[加密消息]" : content);
        message.setMessageType(messageType);
        message.setSender(sender);
        message.setChatRoom(chatRoom);
        message.setCreatedAt(LocalDateTime.now());
        message.setMessageStatus(Message.MessageStatus.SENT);
        if (!encrypted && messageType == Message.MessageType.TEXT) {
            message.setMentionedUserIds(resolveMentionedUserIds(content, chatRoom));
        }
        if (encrypted) {
            message.setEncryptedContent(decodeEncryptedContent(encryptedContentBase64));
            message.setEncryptionVersion(encryptionVersion != null ? encryptionVersion : 1);
        }

        message = messageRepository.save(message);
        if (!encrypted && message.getMessageType() == Message.MessageType.TEXT) {
            enqueueLinkPreview(message);
        }
        chatRoomRepository.incrementUnreadForRoomMembersExcept(chatRoomId, senderId);

        log.info("用户 {} 在聊天室 {} 发送消息: {}", senderId, chatRoomId, message.getId());
        return message;
    }

    /**
     * 发送匿名文本消息。真实发送者仍用于权限和撤回校验，展示层使用匿名身份。
     */
    public Message sendAnonymousEncryptedMessage(Long senderId,
                                                 Long chatRoomId,
                                                 String content,
                                                 String encryptedContentBase64,
                                                 Integer encryptionVersion,
                                                 Message.MessageType messageType) {
        AnonymousIdentity identity = anonymousService.getOrCreateIdentityEntity(senderId, chatRoomId);
        Message message = sendEncryptedMessage(
                senderId,
                chatRoomId,
                content,
                encryptedContentBase64,
                encryptionVersion,
                messageType);
        message.setIsAnonymous(true);
        message.setAnonymousIdentity(identity);
        return messageRepository.save(message);
    }

    /**
     * 发送文件消息
     */
    public Message sendFileMessage(Long senderId, Long chatRoomId, String fileName, String fileUrl, 
                                 String fileType, Long fileSize, Message.MessageType messageType) {
        return sendFileMessage(
                senderId,
                chatRoomId,
                fileName,
                fileUrl,
                fileType,
                fileSize,
                messageType,
                null,
                null);
    }

    /**
     * 发送可选加密信封的文件消息。密文字段可用于保存文件密钥信封。
     */
    public Message sendFileMessage(Long senderId, Long chatRoomId, String fileName, String fileUrl,
                                 String fileType, Long fileSize, Message.MessageType messageType,
                                 String encryptedContentBase64, Integer encryptionVersion) {
        // 验证发送者和聊天室
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new RuntimeException("发送者不存在"));
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));

        validateCanSendMessage(senderId, chatRoomId);

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
        if (encryptedContentBase64 != null && !encryptedContentBase64.isBlank()) {
            message.setEncryptedContent(decodeEncryptedContent(encryptedContentBase64));
            message.setEncryptionVersion(encryptionVersion != null ? encryptionVersion : 1);
        }

        message = messageRepository.save(message);
        chatRoomRepository.incrementUnreadForRoomMembersExcept(chatRoomId, senderId);

        log.info("用户 {} 在聊天室 {} 发送文件: {} (类型: {})", 
                senderId, chatRoomId, fileName, messageType);
        return message;
    }

    private byte[] decodeEncryptedContent(String encryptedContentBase64) {
        try {
            return Base64.getDecoder().decode(encryptedContentBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("加密内容必须是有效 Base64");
        }
    }

    Set<Long> resolveMentionedUserIds(String content, ChatRoom chatRoom) {
        Set<Long> mentionedIds = new LinkedHashSet<>();
        if (content == null || content.isBlank()
                || chatRoom == null || chatRoom.getMembers() == null) {
            return mentionedIds;
        }

        Matcher matcher = MENTION_PATTERN.matcher(content);
        while (matcher.find()) {
            String token = normalizeMentionLabel(matcher.group(1));
            if (token.isEmpty()) {
                continue;
            }
            chatRoom.getMembers().stream()
                    .filter(member -> member.getUser() != null && member.getUser().getId() != null)
                    .filter(member -> mentionLabelMatches(member, token))
                    .map(member -> member.getUser().getId())
                    .findFirst()
                    .ifPresent(mentionedIds::add);
        }
        return mentionedIds;
    }

    private boolean mentionLabelMatches(ChatRoomMember member, String normalizedToken) {
        User user = member.getUser();
        return normalizedToken.equals(normalizeMentionLabel(user.getDisplayName()))
                || normalizedToken.equals(normalizeMentionLabel(user.getUsername()))
                || normalizedToken.equals(normalizeMentionLabel(member.getNickname()));
    }

    private String normalizeMentionLabel(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 验证用户是否可以在聊天室发送消息。
     */
    public void validateCanSendMessage(Long senderId, Long chatRoomId) {
        if (!chatRoomRepository.isMember(chatRoomId, senderId)) {
            throw new IllegalArgumentException("您不是该聊天室的成员");
        }
        if (chatRoomRepository.isMuted(chatRoomId, senderId)) {
            throw new IllegalArgumentException("您在该聊天室中被禁言");
        }
    }

    /**
     * 回复消息
     */
    public Message replyToMessage(Long senderId, Long chatRoomId, Long replyToMessageId, 
                                String content, Message.MessageType messageType) {
        // 验证原消息
        Message replyToMessage = messageRepository.findWithSenderById(replyToMessageId)
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

    private void enqueueLinkPreview(Message message) {
        if (linkPreviewService == null
                || message == null
                || message.getId() == null
                || message.getContent() == null
                || message.getContent().isBlank()) {
            return;
        }
        Long messageId = message.getId();
        String content = message.getContent();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    linkPreviewService.enrichMessage(messageId, content);
                }
            });
            return;
        }
        linkPreviewService.enrichMessage(messageId, content);
    }

    /**
     * 获取聊天室消息（分页）
     */
    public Page<Message> getChatRoomMessages(Long chatRoomId, Long userId, Pageable pageable) {
        // 验证用户权限
        if (!chatRoomRepository.isMember(chatRoomId, userId)) {
            throw new IllegalArgumentException("您不是该聊天室的成员");
        }

        return clearedAt(chatRoomId, userId)
                .map(clearedAt -> messageRepository.findByChatRoomIdAfterClear(chatRoomId, clearedAt, pageable))
                .orElseGet(() -> messageRepository.findByChatRoomIdOrderByCreatedAtDesc(chatRoomId, pageable));
    }

    /**
     * 获取当前用户在聊天室内被 @ 的消息。
     */
    public Page<Message> getMentionedMessages(Long chatRoomId, Long userId, Pageable pageable) {
        if (!chatRoomRepository.isMember(chatRoomId, userId)) {
            throw new IllegalArgumentException("您不是该聊天室的成员");
        }

        return clearedAt(chatRoomId, userId)
                .map(clearedAt -> messageRepository.findMentionedMessagesForUserAfterClear(
                        chatRoomId,
                        userId,
                        clearedAt,
                        pageable))
                .orElseGet(() -> messageRepository.findMentionedMessagesForUser(chatRoomId, userId, pageable));
    }

    /**
     * 获取最新消息
     */
    public List<Message> getRecentMessages(Long chatRoomId, Long userId, int limit) {
        // 验证用户权限
        if (!chatRoomRepository.isMember(chatRoomId, userId)) {
            throw new IllegalArgumentException("您不是该聊天室的成员");
        }

        return clearedAt(chatRoomId, userId)
                .map(clearedAt -> messageRepository.findRecentMessagesListAfterClear(
                        chatRoomId,
                        clearedAt,
                        org.springframework.data.domain.PageRequest.of(0, limit)))
                .orElseGet(() -> messageRepository.findRecentMessages(chatRoomId, limit));
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

        if (readReceiptRepository != null &&
                readReceiptRepository.findByMessageIdAndUserId(messageId, userId).isEmpty()) {
            var receipt = new com.chatapp.entity.MessageReadReceipt();
            receipt.setMessage(message);
            receipt.setUser(userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("用户不存在")));
            readReceiptRepository.save(receipt);
            messageRepository.markAsRead(messageId, userId);
        } else if (readReceiptRepository == null) {
            messageRepository.markAsRead(messageId, userId);
        }
        chatRoomRepository.markMessageReadForMember(message.getChatRoom().getId(), userId, messageId);
        
        log.debug("用户 {} 标记消息 {} 为已读", userId, messageId);
    }

    /**
     * 撤回消息
     */
    public Message recallMessage(Long messageId, Long userId) {
        Message message = messageRepository.findWithSenderById(messageId)
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
        message = messageRepository.save(message);

        log.info("用户 {} 撤回了消息 {}", userId, messageId);
        return message;
    }

    public Message editMessage(Long messageId, Long userId, String content) {
        Message message = messageRepository.findWithSenderById(messageId)
                .orElseThrow(() -> new RuntimeException("消息不存在"));
        if (!chatRoomRepository.isMember(message.getChatRoom().getId(), userId)) {
            throw new IllegalArgumentException("您无权限编辑此消息");
        }
        if (!message.getSender().getId().equals(userId)) {
            throw new IllegalArgumentException("只能编辑自己的消息");
        }
        if (Boolean.TRUE.equals(message.getIsDeleted())) {
            throw new IllegalArgumentException("已删除消息不能编辑");
        }
        if (message.getMessageType() != Message.MessageType.TEXT) {
            throw new IllegalArgumentException("仅支持编辑文本消息");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        message.setContent(content.trim());
        message.setIsEdited(true);
        message.setMentionedUserIds(resolveMentionedUserIds(content, message.getChatRoom()));
        return messageRepository.save(message);
    }

    public Message forwardMessage(Long messageId, Long userId, Long targetRoomId) {
        Message source = messageRepository.findWithSenderById(messageId)
                .orElseThrow(() -> new RuntimeException("消息不存在"));
        if (!chatRoomRepository.isMember(source.getChatRoom().getId(), userId)) {
            throw new IllegalArgumentException("您无权限查看原消息");
        }
        validateCanSendMessage(userId, targetRoomId);
        User sender = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("发送者不存在"));
        ChatRoom targetRoom = chatRoomRepository.findById(targetRoomId)
                .orElseThrow(() -> new RuntimeException("目标聊天室不存在"));

        Message forwarded = new Message();
        forwarded.setSender(sender);
        forwarded.setChatRoom(targetRoom);
        forwarded.setForwardedFromMessage(source);
        forwarded.setContent(source.getContent());
        forwarded.setMessageType(source.getMessageType());
        forwarded.setFileUrl(source.getFileUrl());
        forwarded.setFileName(source.getFileName());
        forwarded.setFileSize(source.getFileSize());
        forwarded.setFileType(source.getFileType());
        forwarded.setThumbnailUrl(source.getThumbnailUrl());
        forwarded.setStickerId(source.getStickerId());
        forwarded.setPollId(source.getPollId());
        forwarded.setImageGenPrompt(source.getImageGenPrompt());
        forwarded.setImageGenStatus(source.getImageGenStatus());
        forwarded.setImageGenUrl(source.getImageGenUrl());
        forwarded.setDuration(source.getDuration());
        forwarded.setWidth(source.getWidth());
        forwarded.setHeight(source.getHeight());
        forwarded.setMessageStatus(Message.MessageStatus.SENT);
        if (forwarded.getMessageType() == Message.MessageType.TEXT) {
            forwarded.setMentionedUserIds(resolveMentionedUserIds(forwarded.getContent(), targetRoom));
        }
        Message saved = messageRepository.save(forwarded);
        chatRoomRepository.incrementUnreadForRoomMembersExcept(targetRoomId, userId);
        return saved;
    }

    public ChatRoomPinnedMessage pinMessage(Long roomId, Long messageId, Long userId) {
        Message message = messageRepository.findWithSenderById(messageId)
                .orElseThrow(() -> new RuntimeException("消息不存在"));
        if (!message.getChatRoom().getId().equals(roomId)) {
            throw new IllegalArgumentException("消息不属于这个聊天室");
        }
        requireRoomAdminOrPrivateMember(roomId, userId);
        return pinnedMessageRepository.findByChatRoomIdAndMessageId(roomId, messageId)
                .orElseGet(() -> {
                    ChatRoomPinnedMessage pin = new ChatRoomPinnedMessage();
                    pin.setChatRoom(message.getChatRoom());
                    pin.setMessage(message);
                    pin.setPinnedBy(userRepository.findById(userId)
                            .orElseThrow(() -> new RuntimeException("用户不存在")));
                    return pinnedMessageRepository.save(pin);
                });
    }

    public void unpinMessage(Long roomId, Long messageId, Long userId) {
        requireRoomAdminOrPrivateMember(roomId, userId);
        pinnedMessageRepository.deleteByChatRoomIdAndMessageId(roomId, messageId);
    }

    @Transactional(readOnly = true)
    public List<Message> getPinnedMessages(Long roomId, Long userId) {
        if (!chatRoomRepository.isMember(roomId, userId)) {
            throw new IllegalArgumentException("您不是该聊天室的成员");
        }
        return pinnedMessageRepository.findByChatRoomIdOrderByCreatedAtDesc(roomId).stream()
                .map(ChatRoomPinnedMessage::getMessage)
                .toList();
    }

    public Message starMessage(Long messageId, Long userId) {
        Message message = messageRepository.findWithSenderById(messageId)
                .orElseThrow(() -> new RuntimeException("消息不存在"));
        if (!chatRoomRepository.isMember(message.getChatRoom().getId(), userId)) {
            throw new IllegalArgumentException("您无权限收藏此消息");
        }
        messageStarRepository.findByMessageIdAndUserId(messageId, userId)
                .orElseGet(() -> {
                    MessageStar star = new MessageStar();
                    star.setMessage(message);
                    star.setUser(userRepository.findById(userId)
                            .orElseThrow(() -> new RuntimeException("用户不存在")));
                    return messageStarRepository.save(star);
                });
        return message;
    }

    public Message unstarMessage(Long messageId, Long userId) {
        Message message = messageRepository.findWithSenderById(messageId)
                .orElseThrow(() -> new RuntimeException("消息不存在"));
        if (!chatRoomRepository.isMember(message.getChatRoom().getId(), userId)) {
            throw new IllegalArgumentException("您无权限取消收藏此消息");
        }
        messageStarRepository.deleteByMessageIdAndUserId(messageId, userId);
        return message;
    }

    @Transactional(readOnly = true)
    public Page<Message> getStarredMessages(Long userId, Pageable pageable) {
        return messageStarRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(MessageStar::getMessage);
    }

    private void requireRoomAdminOrPrivateMember(Long roomId, Long userId) {
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));
        if (!chatRoomRepository.isMember(roomId, userId)) {
            throw new IllegalArgumentException("您不是该聊天室的成员");
        }
        if (room.getRoomType() != ChatRoom.RoomType.PRIVATE && !chatRoomRepository.isAdmin(roomId, userId)) {
            throw new IllegalArgumentException("需要群管理员权限");
        }
    }

    /**
     * 删除消息（管理员）
     */
    public Message deleteMessage(Long messageId, Long operatorId) {
        Message message = messageRepository.findWithSenderById(messageId)
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
        message = messageRepository.save(message);

        log.info("用户 {} 删除了消息 {} (聊天室: {})", operatorId, messageId, chatRoomId);
        return message;
    }

    /**
     * 搜索消息
     */
    public Page<Message> searchMessages(Long chatRoomId, Long userId, String keyword, Pageable pageable) {
        // 验证用户权限
        if (!chatRoomRepository.isMember(chatRoomId, userId)) {
            throw new IllegalArgumentException("您不是该聊天室的成员");
        }

        return clearedAt(chatRoomId, userId)
                .map(clearedAt -> messageRepository.searchInChatRoomAfterClear(
                        chatRoomId,
                        keyword,
                        clearedAt,
                        pageable))
                .orElseGet(() -> messageRepository.searchInChatRoom(chatRoomId, keyword, pageable));
    }

    public List<MessageDto> searchContext(Long chatRoomId, Message message) {
        List<Message> before = messageRepository.findContextBefore(
                chatRoomId,
                message.getCreatedAt(),
                PageRequest.of(0, 2));
        List<Message> after = messageRepository.findContextAfter(
                chatRoomId,
                message.getCreatedAt(),
                PageRequest.of(0, 2));
        List<Message> context = new java.util.ArrayList<>();
        for (int i = before.size() - 1; i >= 0; i--) {
            context.add(before.get(i));
        }
        context.add(message);
        context.addAll(after);
        return context.stream().map(MessageDto::fromEntity).toList();
    }

    /**
     * 获取聊天室内的文件/图片消息。
     */
    public Page<Message> getChatRoomFileMessages(Long chatRoomId, Long userId,
                                                 Message.MessageType messageType,
                                                 Pageable pageable) {
        if (!chatRoomRepository.isMember(chatRoomId, userId)) {
            throw new IllegalArgumentException("您不是该聊天室的成员");
        }
        if (messageType != null
                && messageType != Message.MessageType.IMAGE
                && messageType != Message.MessageType.FILE
                && messageType != Message.MessageType.VOICE
                && messageType != Message.MessageType.AUDIO
                && messageType != Message.MessageType.VIDEO) {
            throw new IllegalArgumentException("仅支持筛选附件消息");
        }
        return clearedAt(chatRoomId, userId)
                .map(clearedAt -> messageRepository.findFileMessagesInChatRoomAfterClear(
                        chatRoomId,
                        messageType,
                        clearedAt,
                        pageable))
                .orElseGet(() -> messageRepository.findFileMessagesInChatRoom(chatRoomId, messageType, pageable));
    }

    /**
     * 获取未读消息数量
     */
    public Long getUnreadMessageCount(Long chatRoomId, Long userId) {
        // 验证用户权限
        if (!chatRoomRepository.isMember(chatRoomId, userId)) {
            return 0L;
        }

        return chatRoomRepository.findUnreadCount(chatRoomId, userId)
                .map(Integer::longValue)
                .orElse(0L);
    }

    /**
     * 获取用户在所有聊天室的未读消息总数
     */
    public Long getTotalUnreadCount(Long userId) {
        return messageRepository.countTotalUnreadMessages(userId);
    }

    @Transactional(readOnly = true)
    public List<com.chatapp.dto.ReadReceiptDto> getReadReceipts(Long messageId, Long requesterId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("消息不存在"));
        if (!chatRoomRepository.isMember(message.getChatRoom().getId(), requesterId)) {
            throw new IllegalArgumentException("您无权限查看此消息");
        }
        if (readReceiptRepository == null) {
            return List.of();
        }
        return readReceiptRepository.findByMessageIdOrderByReadAtAsc(messageId).stream()
                .map(com.chatapp.dto.ReadReceiptDto::fromEntity)
                .toList();
    }

    /**
     * 标记聊天室所有消息为已读
     */
    public Message markAllMessagesAsRead(Long chatRoomId, Long userId) {
        // 验证用户权限
        if (!chatRoomRepository.isMember(chatRoomId, userId)) {
            throw new IllegalArgumentException("您不是该聊天室的成员");
        }

        messageRepository.markAllAsReadInChatRoom(chatRoomId, userId);
        Message lastMessage = findVisibleLastMessage(chatRoomId, userId);
        chatRoomRepository.markRoomReadForMember(
                chatRoomId,
                userId,
                lastMessage != null ? lastMessage.getId() : null);
        
        log.info("用户 {} 标记聊天室 {} 所有消息为已读", userId, chatRoomId);
        return lastMessage;
    }

    /**
     * 获取消息统计信息
     */
    public MessageStats getMessageStats(Long chatRoomId, Long userId) {
        // 验证用户权限
        if (!chatRoomRepository.isMember(chatRoomId, userId)) {
            throw new IllegalArgumentException("您不是该聊天室的成员");
        }

        Long totalCount = clearedAt(chatRoomId, userId)
                .map(clearedAt -> messageRepository.countByChatRoomIdAfterClear(chatRoomId, clearedAt))
                .orElseGet(() -> messageRepository.countByChatRoomId(chatRoomId));
        Long unreadCount = getUnreadMessageCount(chatRoomId, userId);
        Message lastMessage = findVisibleLastMessage(chatRoomId, userId);

        return new MessageStats(totalCount, unreadCount, lastMessage);
    }

    /**
     * Clears the visible history for one user without deleting messages for
     * other members. New messages sent after this timestamp remain visible.
     */
    public void clearChatHistoryForUser(Long chatRoomId, Long userId) {
        if (!chatRoomRepository.isMember(chatRoomId, userId)) {
            throw new IllegalArgumentException("您不是该聊天室的成员");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));

        ChatRoomClearState clearState = clearStateRepository
                .findByUserIdAndChatRoomId(userId, chatRoomId)
                .orElseGet(ChatRoomClearState::new);
        clearState.setUser(user);
        clearState.setChatRoom(chatRoom);
        clearState.setClearedAt(LocalDateTime.now());
        clearStateRepository.save(clearState);
        chatRoomRepository.markRoomReadForMember(chatRoomId, userId, null);

        log.info("用户 {} 清空了聊天室 {} 的本地可见历史", userId, chatRoomId);
    }

    private Message findVisibleLastMessage(Long chatRoomId, Long userId) {
        return clearedAt(chatRoomId, userId)
                .map(clearedAt -> {
                    List<Message> messages = messageRepository.findLastMessagesAfterClear(
                            chatRoomId,
                            clearedAt,
                            org.springframework.data.domain.PageRequest.of(0, 1));
                    return messages.isEmpty() ? null : messages.get(0);
                })
                .orElseGet(() -> messageRepository.findLastMessage(chatRoomId));
    }

    private java.util.Optional<LocalDateTime> clearedAt(Long chatRoomId, Long userId) {
        return clearStateRepository.findByUserIdAndChatRoomId(userId, chatRoomId)
                .map(ChatRoomClearState::getClearedAt);
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
