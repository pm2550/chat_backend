package com.chatapp.service;

import com.chatapp.dto.ImageGenerationDto;
import com.chatapp.dto.MessageDto;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.websocket.RawWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;

@Service
@Slf4j
public class ImageGenerationService {
    private static final String FEATURE_KEY = "image_generation";
    private static final int MAX_POLLS = 40;
    private static final long POLL_DELAY_MS = 3_000L;

    private final MessageRepository messageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final MessageService messageService;
    private final PointsService pointsService;
    private final ImageGenerationClient generationClient;
    private final FileStorageService fileStorageService;
    private final RawWebSocketHandler rawWebSocketHandler;
    private final TransactionTemplate transactionTemplate;
    private final Executor taskExecutor;

    public ImageGenerationService(
            MessageRepository messageRepository,
            ChatRoomRepository chatRoomRepository,
            UserRepository userRepository,
            MessageService messageService,
            PointsService pointsService,
            ImageGenerationClient generationClient,
            FileStorageService fileStorageService,
            RawWebSocketHandler rawWebSocketHandler,
            TransactionTemplate transactionTemplate,
            @Qualifier("taskExecutor") Executor taskExecutor) {
        this.messageRepository = messageRepository;
        this.chatRoomRepository = chatRoomRepository;
        this.userRepository = userRepository;
        this.messageService = messageService;
        this.pointsService = pointsService;
        this.generationClient = generationClient;
        this.fileStorageService = fileStorageService;
        this.rawWebSocketHandler = rawWebSocketHandler;
        this.transactionTemplate = transactionTemplate;
        this.taskExecutor = taskExecutor;
    }

    @Transactional
    public ImageGenerationDto.GenerateResponse submit(Long userId, ImageGenerationDto.GenerateRequest request) {
        String prompt = normalizePrompt(request.getPrompt());
        int count = request.getN() == null ? 1 : request.getN();
        if (count != 1) {
            throw new IllegalArgumentException("当前仅支持一次生成一张图片");
        }

        User sender = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        ChatRoom chatRoom = chatRoomRepository.findById(request.getRoomId())
                .orElseThrow(() -> new IllegalArgumentException("聊天室不存在"));
        messageService.validateCanSendMessage(userId, chatRoom.getId());

        Message message = new Message();
        message.setContent(prompt);
        message.setMessageType(Message.MessageType.IMAGE_GENERATION);
        message.setMessageStatus(Message.MessageStatus.SENDING);
        message.setSender(sender);
        message.setChatRoom(chatRoom);
        message.setImageGenPrompt(prompt);
        message.setImageGenStatus(Message.ImageGenerationStatus.QUEUED);
        message.setCreatedAt(LocalDateTime.now());
        message = messageRepository.save(message);

        String refId = refId(message.getId());
        var debit = pointsService.debit(userId, FEATURE_KEY, refId);
        chatRoomRepository.incrementUnreadForRoomMembersExcept(chatRoom.getId(), userId);
        rawWebSocketHandler.broadcastMessage(message);

        Long messageId = message.getId();
        String size = request.getSize();
        boolean expand = request.getExpand() == null || request.getExpand();
        taskExecutor.execute(() -> process(messageId, userId, prompt, size, expand, refId));

        return new ImageGenerationDto.GenerateResponse(
                messageId,
                debit.getUsedPaid(),
                Message.ImageGenerationStatus.QUEUED,
                MessageDto.fromEntity(message)
        );
    }

    private void process(Long messageId, Long userId, String prompt, String size, boolean expand, String refId) {
        try {
            updateStatus(messageId, Message.ImageGenerationStatus.PROCESSING, Message.MessageStatus.SENDING, null, null);
            ImageGenerationClient.SubmitResult submit = generationClient.submit("", prompt, 1, size, expand);
            updateProviderTask(messageId, submit.taskId());

            ImageGenerationClient.PollResult result = waitForResult("", submit.taskId());
            if (result.status() != ImageGenerationClient.PollResult.Status.SUCCEEDED) {
                throw new IllegalStateException(result.errorMessage() == null
                        ? "图片生成失败"
                        : result.errorMessage());
            }

            byte[] bytes = generationClient.download(result.imageUrl());
            String fileUrl = fileStorageService.uploadGeneratedImage(
                    "image-generation-" + messageId + ".png",
                    "image/png",
                    bytes);
            complete(messageId, fileUrl, bytes.length);
        } catch (Exception e) {
            log.warn("Image generation failed for message {}: {}", messageId, e.getMessage());
            try {
                pointsService.refund(userId, FEATURE_KEY, refId, "图片生成失败自动退还");
            } catch (Exception refundError) {
                log.warn("Image generation refund failed for message {}: {}", messageId, refundError.getMessage());
            }
            fail(messageId, e.getMessage());
        }
    }

    private ImageGenerationClient.PollResult waitForResult(String apiKey, String taskId) throws InterruptedException {
        for (int i = 0; i < MAX_POLLS; i++) {
            ImageGenerationClient.PollResult result = generationClient.poll(apiKey, taskId);
            if (result.status() == ImageGenerationClient.PollResult.Status.SUCCEEDED
                    || result.status() == ImageGenerationClient.PollResult.Status.FAILED) {
                return result;
            }
            Thread.sleep(POLL_DELAY_MS);
        }
        return new ImageGenerationClient.PollResult(
                ImageGenerationClient.PollResult.Status.FAILED,
                null,
                "图片生成超时");
    }

    private void updateStatus(Long messageId,
                              Message.ImageGenerationStatus status,
                              Message.MessageStatus messageStatus,
                              String fileUrl,
                              Long fileSize) {
        transactionTemplate.executeWithoutResult(statusTx -> {
            Message message = messageRepository.findWithSenderById(messageId)
                    .orElseThrow(() -> new IllegalArgumentException("消息不存在"));
            message.setImageGenStatus(status);
            message.setMessageStatus(messageStatus);
            if (fileUrl != null) {
                message.setImageGenUrl(fileUrl);
                message.setFileUrl(fileUrl);
                message.setFileName("AI image " + messageId + ".png");
                message.setFileType("image/png");
                message.setFileSize(fileSize);
            }
            message = messageRepository.save(message);
            rawWebSocketHandler.broadcastMessage(message);
        });
    }

    private void updateProviderTask(Long messageId, String taskId) {
        transactionTemplate.executeWithoutResult(statusTx -> {
            Message message = messageRepository.findWithSenderById(messageId)
                    .orElseThrow(() -> new IllegalArgumentException("消息不存在"));
            message.setImageGenProviderTaskId(taskId);
            message = messageRepository.save(message);
            rawWebSocketHandler.broadcastMessage(message);
        });
    }

    private void complete(Long messageId, String fileUrl, long fileSize) {
        updateStatus(messageId, Message.ImageGenerationStatus.DONE, Message.MessageStatus.SENT, fileUrl, fileSize);
    }

    private void fail(Long messageId, String reason) {
        transactionTemplate.executeWithoutResult(statusTx -> {
            Message message = messageRepository.findWithSenderById(messageId)
                    .orElseThrow(() -> new IllegalArgumentException("消息不存在"));
            message.setImageGenStatus(Message.ImageGenerationStatus.FAILED);
            message.setMessageStatus(Message.MessageStatus.FAILED);
            if (reason != null && !reason.isBlank()) {
                message.setContent(message.getImageGenPrompt() + "\n\n" + reason);
            }
            message = messageRepository.save(message);
            rawWebSocketHandler.broadcastMessage(message);
        });
    }

    private String normalizePrompt(String prompt) {
        String normalized = prompt == null ? "" : prompt.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("提示词不能为空");
        }
        if (normalized.length() > 1000) {
            throw new IllegalArgumentException("提示词最多 1000 字符");
        }
        return normalized;
    }

    private String refId(Long messageId) {
        return "image_generation:" + messageId;
    }
}
