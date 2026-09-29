package com.chatapp.service;

import com.chatapp.dto.ImageGenerationDto;
import com.chatapp.dto.MessageDto;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.websocket.RawWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;

@Service
@Slf4j
public class ImageGenerationService {
    private static final String FEATURE_KEY = "image_generation";
    /** 最多等 15 分钟：PixAI 队列拥堵时 API 任务会排 8–10 分钟（实测），2 分钟就判失败会白扣服务商额度。 */
    private static final int MAX_POLLS = 300;
    private static final long POLL_DELAY_MS = 3_000L;
    static final String TIMEOUT_REASON = "图片生成超时";
    /** 超时失败后多久内还去服务商那边找回结果（PixAI 的临时图片链接约一天后失效）。 */
    private static final long LATE_RESULT_LOOKBACK_HOURS = 24;
    static final String E2EE_ROOM_REJECTION = "端到端加密的私聊里不能用 AI 画图：描述和图片会以明文存在服务器上";

    private final MessageRepository messageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final MessageService messageService;
    private final PointsService pointsService;
    private final ImageGenerationClient generationClient;
    private final BotImageGenerationClient botImageGenerationClient;
    private final FileStorageService fileStorageService;
    private final RawWebSocketHandler rawWebSocketHandler;
    private final TransactionTemplate transactionTemplate;
    private final Executor taskExecutor;
    private final E2eeKeyService e2eeKeyService;
    /** 可选：AI 画的图通常 1–3 MB，气泡先显示缩略图。没注入（单测手工构造）时不生成。 */
    private ImageThumbnailService imageThumbnailService;

    public ImageGenerationService(
            MessageRepository messageRepository,
            ChatRoomRepository chatRoomRepository,
            UserRepository userRepository,
            MessageService messageService,
            PointsService pointsService,
            ImageGenerationClient generationClient,
            BotImageGenerationClient botImageGenerationClient,
            FileStorageService fileStorageService,
            RawWebSocketHandler rawWebSocketHandler,
            TransactionTemplate transactionTemplate,
            @Qualifier("imageGenerationExecutor") Executor taskExecutor,
            E2eeKeyService e2eeKeyService) {
        this.messageRepository = messageRepository;
        this.chatRoomRepository = chatRoomRepository;
        this.userRepository = userRepository;
        this.messageService = messageService;
        this.pointsService = pointsService;
        this.generationClient = generationClient;
        this.botImageGenerationClient = botImageGenerationClient;
        this.fileStorageService = fileStorageService;
        this.rawWebSocketHandler = rawWebSocketHandler;
        this.transactionTemplate = transactionTemplate;
        this.taskExecutor = taskExecutor;
        this.e2eeKeyService = e2eeKeyService;
    }

    @Autowired(required = false)
    void setImageThumbnailService(ImageThumbnailService imageThumbnailService) {
        this.imageThumbnailService = imageThumbnailService;
    }

    private ImageThumbnailService.MessageRenditions createThumbnail(byte[] bytes) {
        return imageThumbnailService == null
                ? null
                : ImageThumbnailService.MessageRenditions.serverGenerated(imageThumbnailService.createAndStore(bytes));
    }

    @Transactional
    public ImageGenerationDto.GenerateResponse submit(Long userId, ImageGenerationDto.GenerateRequest request) {
        User sender = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        return submitInternal(userId, sender, null, null, request);
    }

    @Transactional
    public ImageGenerationDto.GenerateResponse submitAsBot(Long chargedUserId,
                                                           User botSender,
                                                           BotConfig botConfig,
                                                           String botDisplayName,
                                                           ImageGenerationDto.GenerateRequest request) {
        if (botSender == null) {
            throw new IllegalArgumentException("机器人发送者不存在");
        }
        if (botConfig == null) {
            throw new IllegalArgumentException("机器人不存在");
        }
        return submitInternal(chargedUserId, botSender, botConfig, botDisplayName, request);
    }

    private ImageGenerationDto.GenerateResponse submitInternal(Long chargedUserId,
                                                               User sender,
                                                               BotConfig botConfig,
                                                               String botDisplayName,
                                                               ImageGenerationDto.GenerateRequest request) {
        String prompt = normalizePrompt(request.getPrompt());
        // 画图一律自动扩写（创意扩写）；请求里的 expand 只为兼容旧客户端，不再生效。
        String promptHelperLevel = ImageGenerationClient.PROMPT_HELPER_MEDIUM;
        int count = request.getN() == null ? 1 : request.getN();
        if (count != 1) {
            throw new IllegalArgumentException("当前仅支持一次生成一张图片");
        }

        userRepository.findById(chargedUserId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        ChatRoom chatRoom = chatRoomRepository.findById(request.getRoomId())
                .orElseThrow(() -> new IllegalArgumentException("聊天室不存在"));
        messageService.validateCanSendMessage(chargedUserId, chatRoom.getId());
        // 端到端加密的私聊里服务器只有密文；用户直接画图时描述和生成的图却都是明文存在服务器上，
        // 会悄悄破坏加密。在建消息、扣积分之前就拒绝。（机器人画图由明文消息触发，不走这里。）
        if (botConfig == null && e2eeKeyService.isEncryptionActive(chatRoom)) {
            throw new IllegalArgumentException(E2EE_ROOM_REJECTION);
        }

        Message message = new Message();
        message.setContent(prompt);
        message.setMessageType(Message.MessageType.IMAGE_GENERATION);
        message.setMessageStatus(Message.MessageStatus.SENDING);
        message.setSender(sender);
        message.setChatRoom(chatRoom);
        if (botConfig != null) {
            message.setBotConfig(botConfig);
            message.setBotDisplayName(botDisplayName != null && !botDisplayName.isBlank()
                    ? botDisplayName
                    : botConfig.getBotName());
        }
        message.setImageGenPrompt(prompt);
        message.setImageGenStatus(Message.ImageGenerationStatus.QUEUED);
        message.setCreatedAt(LocalDateTime.now());
        message = messageRepository.save(message);

        String refId = refId(message.getId());
        var debit = pointsService.debit(chargedUserId, FEATURE_KEY, refId);
        // 和普通发消息一致：发起人自己隐藏过的会话也要回到消息列表
        chatRoomRepository.clearHiddenForMember(chatRoom.getId(), chargedUserId);
        chatRoomRepository.incrementUnreadForRoomMembersExcept(chatRoom.getId(), chargedUserId);
        // 离线通知等图片真正生成好再发一次（见 complete），排队/处理中的进度只算更新。
        rawWebSocketHandler.broadcastMessageWithoutOfflineNotification(message);

        Long messageId = message.getId();
        String size = request.getSize();
        BotImageGenerationClient.ProviderConfig providerConfig =
                botImageGenerationClient.resolve(botConfig);
        runAfterCommit(() -> process(
                messageId,
                chargedUserId,
                prompt,
                size,
                promptHelperLevel,
                refId,
                providerConfig));

        return new ImageGenerationDto.GenerateResponse(
                messageId,
                debit.getUsedPaid(),
                Message.ImageGenerationStatus.QUEUED,
                MessageDto.fromEntity(message)
        );
    }

    private void process(Long messageId,
                         Long userId,
                         String prompt,
                         String size,
                         String promptHelperLevel,
                         String refId,
                         BotImageGenerationClient.ProviderConfig providerConfig) {
        try {
            updateStatus(messageId, Message.ImageGenerationStatus.PROCESSING, Message.MessageStatus.SENDING,
                    null, null, null);
            byte[] bytes;
            String mimeType;
            if (providerConfig == null
                    || providerConfig.provider() == BotConfig.ImageGenerationProvider.HERMES) {
                ImageGenerationClient.SubmitResult submit = generationClient.submit("", prompt, 1, size, promptHelperLevel);
                updateProviderTask(messageId, submit.taskId());
                ImageGenerationClient.PollResult result = waitForResult("", submit.taskId());
                if (result.status() != ImageGenerationClient.PollResult.Status.SUCCEEDED) {
                    throw new IllegalStateException(result.errorMessage() == null
                            ? "图片生成失败"
                            : result.errorMessage());
                }
                bytes = generationClient.download(result.imageUrl());
                // Hermes 给 PNG，PixAI 给 WebP：按内容认格式，别一律当 PNG 存。
                mimeType = sniffImageMimeType(bytes);
            } else {
                BotImageGenerationClient.GeneratedImage generated =
                        botImageGenerationClient.generate(providerConfig, prompt, size);
                bytes = generated.bytes();
                mimeType = generated.mimeType();
            }
            String fileUrl = fileStorageService.uploadGeneratedImage(
                    "image-generation-" + messageId + "." + extensionFor(mimeType),
                    mimeType,
                    bytes);
            complete(messageId, fileUrl, bytes.length, createThumbnail(bytes));
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

    static String sniffImageMimeType(byte[] bytes) {
        if (bytes != null && bytes.length >= 12) {
            if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
                return "image/png";
            }
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
                return "image/jpeg";
            }
            if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
                return "image/webp";
            }
            if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
                return "image/gif";
            }
        }
        return "image/png";
    }

    private static String extensionFor(String mimeType) {
        if (mimeType == null) {
            return "png";
        }
        return switch (mimeType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "png";
        };
    }

    private void runAfterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            taskExecutor.execute(task);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                taskExecutor.execute(task);
            }
        });
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
                TIMEOUT_REASON);
    }

    /**
     * 超时判失败的画图，服务商那边往往过几分钟还是画完了（也照样扣了服务商额度）：
     * 定时回查，画好了就把图补回原消息。积分当时已经退了，补发不再扣。
     */
    @Scheduled(initialDelay = 60_000L, fixedDelay = 300_000L)
    public void recoverTimedOutGenerations() {
        List<Message> candidates = messageRepository
                .findTop50ByMessageTypeAndImageGenStatusAndImageGenProviderTaskIdIsNotNullAndCreatedAtAfterOrderByIdAsc(
                        Message.MessageType.IMAGE_GENERATION,
                        Message.ImageGenerationStatus.FAILED,
                        LocalDateTime.now().minusHours(LATE_RESULT_LOOKBACK_HOURS));
        for (Message message : candidates) {
            String content = message.getContent();
            if (content == null || !content.endsWith(TIMEOUT_REASON)) {
                continue;
            }
            try {
                recoverTimedOutGeneration(message);
            } catch (Exception e) {
                log.warn("Late image recovery failed for message {}: {}", message.getId(), e.getMessage());
            }
        }
    }

    private void recoverTimedOutGeneration(Message message) throws Exception {
        ImageGenerationClient.PollResult result = generationClient.poll("", message.getImageGenProviderTaskId());
        if (result.status() != ImageGenerationClient.PollResult.Status.SUCCEEDED || result.imageUrl() == null) {
            return;
        }
        byte[] bytes = generationClient.download(result.imageUrl());
        String mimeType = sniffImageMimeType(bytes);
        String fileUrl = fileStorageService.uploadGeneratedImage(
                "image-generation-" + message.getId() + "." + extensionFor(mimeType), mimeType, bytes);
        ImageThumbnailService.MessageRenditions thumbnail = createThumbnail(bytes);
        Message done = transactionTemplate.execute(tx -> {
            Message current = messageRepository.findWithSenderById(message.getId()).orElse(null);
            if (current == null || current.getImageGenStatus() != Message.ImageGenerationStatus.FAILED) {
                return null;
            }
            current.setImageGenStatus(Message.ImageGenerationStatus.DONE);
            current.setMessageStatus(Message.MessageStatus.SENT);
            current.setContent(current.getImageGenPrompt());
            current.setImageGenUrl(fileUrl);
            current.setFileUrl(fileUrl);
            current.setFileName("AI image " + current.getId() + "." + extensionFor(mimeType));
            current.setFileType(mimeType);
            current.setFileSize((long) bytes.length);
            if (thumbnail != null) {
                thumbnail.applyTo(current);
            }
            current = messageRepository.save(current);
            rawWebSocketHandler.broadcastMessageUpdated(current);
            return current;
        });
        if (done != null) {
            log.info("Recovered timed-out image generation for message {}", done.getId());
            rawWebSocketHandler.notifyOfflineMembers(done);
        }
    }

    private Message updateStatus(Long messageId,
                                 Message.ImageGenerationStatus status,
                                 Message.MessageStatus messageStatus,
                                 String fileUrl,
                                 Long fileSize,
                                 ImageThumbnailService.MessageRenditions thumbnail) {
        return transactionTemplate.execute(statusTx -> {
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
                if (thumbnail != null) {
                    thumbnail.applyTo(message);
                }
            }
            message = messageRepository.save(message);
            rawWebSocketHandler.broadcastMessageUpdated(message);
            return message;
        });
    }

    private void updateProviderTask(Long messageId, String taskId) {
        transactionTemplate.executeWithoutResult(statusTx -> {
            Message message = messageRepository.findWithSenderById(messageId)
                    .orElseThrow(() -> new IllegalArgumentException("消息不存在"));
            message.setImageGenProviderTaskId(taskId);
            message = messageRepository.save(message);
            rawWebSocketHandler.broadcastMessageUpdated(message);
        });
    }

    private void complete(Long messageId, String fileUrl, long fileSize,
                          ImageThumbnailService.MessageRenditions thumbnail) {
        Message done = updateStatus(
                messageId, Message.ImageGenerationStatus.DONE, Message.MessageStatus.SENT,
                fileUrl, fileSize, thumbnail);
        if (done != null) {
            rawWebSocketHandler.notifyOfflineMembers(done);
        }
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
            rawWebSocketHandler.broadcastMessageUpdated(message);
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
