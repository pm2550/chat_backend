package com.chatapp.service;

import com.chatapp.dto.ImageGenerationDto;
import com.chatapp.dto.PointsDto;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.websocket.RawWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageGenerationServiceTest {

    @Mock private MessageRepository messageRepository;
    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private UserRepository userRepository;
    @Mock private MessageService messageService;
    @Mock private PointsService pointsService;
    @Mock private ImageGenerationClient generationClient;
    @Mock private BotImageGenerationClient botImageGenerationClient;
    @Mock private FileStorageService fileStorageService;
    @Mock private RawWebSocketHandler rawWebSocketHandler;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private E2eeKeyService e2eeKeyService;

    private ImageGenerationService service;
    private Message persistedMessage;

    @BeforeEach
    void setUp() {
        Executor directExecutor = Runnable::run;
        service = new ImageGenerationService(
                messageRepository,
                chatRoomRepository,
                userRepository,
                messageService,
                pointsService,
                generationClient,
                botImageGenerationClient,
                fileStorageService,
                rawWebSocketHandler,
                transactionTemplate,
                directExecutor,
                e2eeKeyService);

        lenient().doAnswer(invocation -> {
            Consumer<?> callback = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Consumer<Object> typed = (Consumer<Object>) callback;
            typed.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        lenient().doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any());

        lenient().when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            if (message.getId() == null) {
                message.setId(77L);
                persistedMessage = message;
            }
            return message;
        });
        lenient().when(messageRepository.findWithSenderById(77L)).thenAnswer(invocation -> Optional.of(persistedMessage));
    }

    @Test
    void submitDebitsPointsAndCompletesGeneratedImageMessage() throws Exception {
        arrangeRoomAndUser();
        when(pointsService.debit(1L, "image_generation", "image_generation:77"))
                .thenReturn(new PointsDto.DebitResult(0, 10, 90, 123L));
        when(generationClient.submit("", "画一只蓝色机器人", 1, "1024*1024", "medium"))
                .thenReturn(new ImageGenerationClient.SubmitResult("/data2/hermes/data/cache/images/task-1.png"));
        when(generationClient.poll("", "/data2/hermes/data/cache/images/task-1.png"))
                .thenReturn(new ImageGenerationClient.PollResult(
                        ImageGenerationClient.PollResult.Status.SUCCEEDED,
                        "/data2/hermes/data/cache/images/task-1.png",
                        null));
        when(generationClient.download("/data2/hermes/data/cache/images/task-1.png"))
                .thenReturn(new byte[]{1, 2, 3});
        when(fileStorageService.uploadGeneratedImage(eq("image-generation-77.png"), eq("image/png"), any(byte[].class)))
                .thenReturn("/api/files/image-gen/generated.png");

        ImageGenerationDto.GenerateResponse response = service.submit(
                1L,
                new ImageGenerationDto.GenerateRequest(10L, "画一只蓝色机器人", 1, "1024*1024", false));

        assertThat(response.getMessageId()).isEqualTo(77L);
        assertThat(response.getPointsCharged()).isEqualTo(10);
        assertThat(persistedMessage.getImageGenStatus()).isEqualTo(Message.ImageGenerationStatus.DONE);
        assertThat(persistedMessage.getMessageStatus()).isEqualTo(Message.MessageStatus.SENT);
        assertThat(persistedMessage.getFileUrl()).isEqualTo("/api/files/image-gen/generated.png");
        // 排队时作为新消息推送但不发离线通知；进度只算更新；生成好了只通知一次。
        verify(rawWebSocketHandler).broadcastMessageWithoutOfflineNotification(persistedMessage);
        verify(rawWebSocketHandler, atLeastOnce()).broadcastMessageUpdated(persistedMessage);
        verify(rawWebSocketHandler, times(1)).notifyOfflineMembers(persistedMessage);
        verify(rawWebSocketHandler, never()).broadcastMessage(any());
    }

    @Test
    void generatedWebpIsStoredAsWebpNotPng() throws Exception {
        // PixAI 出的是 WebP：按内容认格式存，缩略图照常做（ffmpeg 解 WebP）。
        arrangeRoomAndUser();
        when(pointsService.debit(1L, "image_generation", "image_generation:77"))
                .thenReturn(new PointsDto.DebitResult(0, 10, 90, 123L));
        when(generationClient.submit("", "一只橘猫", 1, "1024*1024", "medium"))
                .thenReturn(new ImageGenerationClient.SubmitResult("task-webp"));
        when(generationClient.poll("", "task-webp"))
                .thenReturn(new ImageGenerationClient.PollResult(
                        ImageGenerationClient.PollResult.Status.SUCCEEDED, "https://cdn.example/x", null));
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
        when(generationClient.download("https://cdn.example/x")).thenReturn(webp);
        when(fileStorageService.uploadGeneratedImage(eq("image-generation-77.webp"), eq("image/webp"), any(byte[].class)))
                .thenReturn("/api/files/image-gen/generated.webp");

        service.submit(1L, new ImageGenerationDto.GenerateRequest(10L, "一只橘猫", 1, "1024*1024", true));

        assertThat(persistedMessage.getImageGenStatus()).isEqualTo(Message.ImageGenerationStatus.DONE);
        assertThat(persistedMessage.getFileUrl()).isEqualTo("/api/files/image-gen/generated.webp");
        assertThat(ImageGenerationService.sniffImageMimeType(new byte[]{(byte) 0xFF, (byte) 0xD8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}))
                .isEqualTo("image/jpeg");
    }

    @Test
    void submitRefundsPointsWhenProviderFails() {
        arrangeRoomAndUser();
        when(pointsService.debit(1L, "image_generation", "image_generation:77"))
                .thenReturn(new PointsDto.DebitResult(0, 10, 90, 123L));
        when(generationClient.submit("", "失败图", 1, "1024*1024", "medium"))
                .thenThrow(new IllegalStateException("quota exhausted"));

        service.submit(
                1L,
                new ImageGenerationDto.GenerateRequest(10L, "失败图", 1, "1024*1024", true));

        assertThat(persistedMessage.getImageGenStatus()).isEqualTo(Message.ImageGenerationStatus.FAILED);
        assertThat(persistedMessage.getMessageStatus()).isEqualTo(Message.MessageStatus.FAILED);
        verify(pointsService).refund(1L, "image_generation", "image_generation:77", "图片生成失败自动退还");
        verify(rawWebSocketHandler, never()).notifyOfflineMembers(any());
        verify(rawWebSocketHandler, never()).broadcastMessage(any());
    }

    @Test
    void submitAsBotChargesUserButMarksMessageAsBot() throws Exception {
        arrangeRoomAndUser();
        User botSender = new User();
        botSender.setId(9L);
        botSender.setUsername("owner");
        BotConfig bot = new BotConfig();
        bot.setId(12L);
        bot.setBotName("Draw Bot");

        when(pointsService.debit(1L, "image_generation", "image_generation:77"))
                .thenReturn(new PointsDto.DebitResult(0, 10, 90, 123L));
        when(generationClient.submit("", "画一座海边城市", 1, "1024*1024", "medium"))
                .thenReturn(new ImageGenerationClient.SubmitResult("/data2/hermes/data/cache/images/task-3.png"));
        when(generationClient.poll("", "/data2/hermes/data/cache/images/task-3.png"))
                .thenReturn(new ImageGenerationClient.PollResult(
                        ImageGenerationClient.PollResult.Status.SUCCEEDED,
                        "/data2/hermes/data/cache/images/task-3.png",
                        null));
        when(generationClient.download("/data2/hermes/data/cache/images/task-3.png"))
                .thenReturn(new byte[]{7, 8, 9});
        when(fileStorageService.uploadGeneratedImage(eq("image-generation-77.png"), eq("image/png"), any(byte[].class)))
                .thenReturn("/api/files/image-gen/bot-generated.png");

        ImageGenerationDto.GenerateResponse response = service.submitAsBot(
                1L,
                botSender,
                bot,
                "画图助手",
                new ImageGenerationDto.GenerateRequest(10L, "画一座海边城市", 1, "1024*1024", true));

        assertThat(response.getMessage().getBotConfigId()).isEqualTo(12L);
        assertThat(response.getMessage().getBotName()).isEqualTo("画图助手");
        assertThat(persistedMessage.getSender().getId()).isEqualTo(9L);
        assertThat(persistedMessage.getBotConfig().getId()).isEqualTo(12L);
        assertThat(persistedMessage.getBotDisplayName()).isEqualTo("画图助手");
        verify(pointsService).debit(1L, "image_generation", "image_generation:77");
        verify(messageService).validateCanSendMessage(1L, 10L);
    }

    @Test
    void submitAsBotUsesItsConfiguredImageProviderInsteadOfHermes() throws Exception {
        arrangeRoomAndUser();
        User botSender = new User();
        botSender.setId(9L);
        BotConfig bot = new BotConfig();
        bot.setId(12L);
        bot.setBotName("BYO Draw Bot");
        var providerConfig = new BotImageGenerationClient.ProviderConfig(
                BotConfig.ImageGenerationProvider.NOVELAI,
                "secret",
                "https://image.novelai.net/ai/generate-image",
                "nai-diffusion-3",
                null);
        when(botImageGenerationClient.resolve(bot)).thenReturn(providerConfig);
        when(botImageGenerationClient.generate(providerConfig, "draw a library", "1024*1024"))
                .thenReturn(new BotImageGenerationClient.GeneratedImage(new byte[]{5, 6, 7}, "image/png"));
        when(pointsService.debit(1L, "image_generation", "image_generation:77"))
                .thenReturn(new PointsDto.DebitResult(0, 10, 90, 123L));
        when(fileStorageService.uploadGeneratedImage(
                eq("image-generation-77.png"), eq("image/png"), any(byte[].class)))
                .thenReturn("/api/files/image-gen/byo.png");

        service.submitAsBot(
                1L,
                botSender,
                bot,
                "BYO Draw Bot",
                new ImageGenerationDto.GenerateRequest(
                        10L, "draw a library", 1, "1024*1024", true));

        assertThat(persistedMessage.getFileUrl()).isEqualTo("/api/files/image-gen/byo.png");
        verifyNoInteractions(generationClient);
        verify(botImageGenerationClient).generate(providerConfig, "draw a library", "1024*1024");
    }

    @Test
    void submitDefersProviderCallUntilOuterTransactionCommits() throws Exception {
        arrangeRoomAndUser();
        when(pointsService.debit(1L, "image_generation", "image_generation:77"))
                .thenReturn(new PointsDto.DebitResult(0, 10, 90, 123L));
        when(generationClient.submit("", "延迟提交", 1, "1024*1024", "medium"))
                .thenReturn(new ImageGenerationClient.SubmitResult("/data2/hermes/data/cache/images/task-2.png"));
        when(generationClient.poll("", "/data2/hermes/data/cache/images/task-2.png"))
                .thenReturn(new ImageGenerationClient.PollResult(
                        ImageGenerationClient.PollResult.Status.SUCCEEDED,
                        "/data2/hermes/data/cache/images/task-2.png",
                        null));
        when(generationClient.download("/data2/hermes/data/cache/images/task-2.png"))
                .thenReturn(new byte[]{4, 5, 6});
        when(fileStorageService.uploadGeneratedImage(eq("image-generation-77.png"), eq("image/png"), any(byte[].class)))
                .thenReturn("/api/files/image-gen/generated-2.png");

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.submit(
                    1L,
                    new ImageGenerationDto.GenerateRequest(10L, "延迟提交", 1, "1024*1024", true));

            assertThat(persistedMessage.getImageGenStatus()).isEqualTo(Message.ImageGenerationStatus.QUEUED);
            verify(generationClient, never()).submit("", "延迟提交", 1, "1024*1024", "medium");

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            assertThat(persistedMessage.getImageGenStatus()).isEqualTo(Message.ImageGenerationStatus.DONE);
            assertThat(persistedMessage.getFileUrl()).isEqualTo("/api/files/image-gen/generated-2.png");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void submitResurfacesHiddenRoomForRequesterAndOtherMembers() {
        arrangeRoomAndUser();
        when(pointsService.debit(1L, "image_generation", "image_generation:77"))
                .thenReturn(new PointsDto.DebitResult(0, 10, 90, 123L));
        when(generationClient.submit("", "隐藏会话里画图", 1, "1024*1024", "medium"))
                .thenThrow(new IllegalStateException("quota exhausted"));

        service.submit(
                1L,
                new ImageGenerationDto.GenerateRequest(10L, "隐藏会话里画图", 1, "1024*1024", true));

        verify(chatRoomRepository).clearHiddenForMember(10L, 1L);
        verify(chatRoomRepository).incrementUnreadForRoomMembersExcept(10L, 1L);
    }

    @Test
    void submitRefusesEncryptedPrivateChatBeforeCreatingMessageOrDebiting() {
        arrangeRoomAndUser();
        when(e2eeKeyService.isEncryptionActive(any(ChatRoom.class))).thenReturn(true);

        assertThatThrownBy(() -> service.submit(
                1L,
                new ImageGenerationDto.GenerateRequest(10L, "私聊里画图", 1, "1024*1024", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("端到端加密");

        verify(messageRepository, never()).save(any(Message.class));
        verify(pointsService, never()).debit(anyLong(), anyString(), anyString());
        verify(rawWebSocketHandler, never()).broadcastMessageWithoutOfflineNotification(any());
        verifyNoInteractions(generationClient);
    }

    @Test
    void drawingAlwaysUsesCreativeExpansionEvenWhenOldClientsAskForNone() {
        // 只有扩写：旧客户端的"快出图"（expand=false）也按创意扩写出图。
        arrangeRoomAndUser();
        when(pointsService.debit(1L, "image_generation", "image_generation:77"))
                .thenReturn(new PointsDto.DebitResult(0, 10, 90, 123L));
        when(generationClient.submit("", "快出图", 1, "1024*1024", "medium"))
                .thenThrow(new IllegalStateException("stop here"));

        service.submit(1L, new ImageGenerationDto.GenerateRequest(10L, "快出图", 1, "1024*1024", false));

        verify(generationClient).submit("", "快出图", 1, "1024*1024", "medium");
    }

    @Test
    void timedOutGenerationIsRecoveredOnceTheProviderFinishes() throws Exception {
        // PixAI 排队 8–10 分钟：我们先判了超时，之后服务商画完了，要把图补回原消息。
        Message failed = new Message();
        failed.setId(88L);
        failed.setMessageType(Message.MessageType.IMAGE_GENERATION);
        failed.setImageGenStatus(Message.ImageGenerationStatus.FAILED);
        failed.setMessageStatus(Message.MessageStatus.FAILED);
        failed.setImageGenPrompt("裸体美游");
        failed.setContent("裸体美游\n\n" + ImageGenerationService.TIMEOUT_REASON);
        failed.setImageGenProviderTaskId("task-late");
        Message otherFailure = new Message();
        otherFailure.setId(89L);
        otherFailure.setImageGenStatus(Message.ImageGenerationStatus.FAILED);
        otherFailure.setContent("猫\n\nPixAI 生成失败");
        otherFailure.setImageGenProviderTaskId("task-failed");
        when(messageRepository
                .findTop50ByMessageTypeAndImageGenStatusAndImageGenProviderTaskIdIsNotNullAndCreatedAtAfterOrderByIdAsc(
                        eq(Message.MessageType.IMAGE_GENERATION),
                        eq(Message.ImageGenerationStatus.FAILED),
                        any()))
                .thenReturn(List.of(failed, otherFailure));
        when(messageRepository.findWithSenderById(88L)).thenReturn(Optional.of(failed));
        when(generationClient.poll("", "task-late")).thenReturn(new ImageGenerationClient.PollResult(
                ImageGenerationClient.PollResult.Status.SUCCEEDED, "https://cdn.example/late.webp", null));
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
        when(generationClient.download("https://cdn.example/late.webp")).thenReturn(webp);
        when(fileStorageService.uploadGeneratedImage(eq("image-generation-88.webp"), eq("image/webp"), any(byte[].class)))
                .thenReturn("/api/files/image-gen/late.webp");

        service.recoverTimedOutGenerations();

        assertThat(failed.getImageGenStatus()).isEqualTo(Message.ImageGenerationStatus.DONE);
        assertThat(failed.getMessageStatus()).isEqualTo(Message.MessageStatus.SENT);
        assertThat(failed.getFileUrl()).isEqualTo("/api/files/image-gen/late.webp");
        assertThat(failed.getContent()).isEqualTo("裸体美游");
        verify(rawWebSocketHandler).broadcastMessageUpdated(failed);
        verify(rawWebSocketHandler).notifyOfflineMembers(failed);
        // 不是超时的失败（服务商明确说失败了）不回查；补发不重新扣积分。
        verify(generationClient, never()).poll("", "task-failed");
        verify(pointsService, never()).debit(anyLong(), anyString(), anyString());

        // 已经补回来了：下一轮不会再处理。
        service.recoverTimedOutGenerations();
        verify(generationClient, times(1)).poll("", "task-late");
    }

    @Test
    void stillQueuedTimedOutGenerationIsLeftForTheNextRound() {
        Message failed = new Message();
        failed.setId(88L);
        failed.setImageGenStatus(Message.ImageGenerationStatus.FAILED);
        failed.setContent("猫\n\n" + ImageGenerationService.TIMEOUT_REASON);
        failed.setImageGenProviderTaskId("task-queued");
        when(messageRepository
                .findTop50ByMessageTypeAndImageGenStatusAndImageGenProviderTaskIdIsNotNullAndCreatedAtAfterOrderByIdAsc(
                        any(), any(), any()))
                .thenReturn(List.of(failed));
        when(generationClient.poll("", "task-queued")).thenReturn(new ImageGenerationClient.PollResult(
                ImageGenerationClient.PollResult.Status.RUNNING, null, null));

        service.recoverTimedOutGenerations();

        assertThat(failed.getImageGenStatus()).isEqualTo(Message.ImageGenerationStatus.FAILED);
        verify(generationClient, never()).download(anyString());
    }

    private void arrangeRoomAndUser() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        ChatRoom room = new ChatRoom();
        room.setId(10L);
        room.setName("room");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(chatRoomRepository.findById(10L)).thenReturn(Optional.of(room));
    }
}
