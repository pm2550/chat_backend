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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
                directExecutor);

        doAnswer(invocation -> {
            Consumer<?> callback = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Consumer<Object> typed = (Consumer<Object>) callback;
            typed.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            if (message.getId() == null) {
                message.setId(77L);
                persistedMessage = message;
            }
            return message;
        });
        when(messageRepository.findWithSenderById(77L)).thenAnswer(invocation -> Optional.of(persistedMessage));
    }

    @Test
    void submitDebitsPointsAndCompletesGeneratedImageMessage() throws Exception {
        arrangeRoomAndUser();
        when(pointsService.debit(1L, "image_generation", "image_generation:77"))
                .thenReturn(new PointsDto.DebitResult(0, 10, 90, 123L));
        when(generationClient.submit("", "画一只蓝色机器人", 1, "1024*1024", false))
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
        verify(rawWebSocketHandler, atLeastOnce()).broadcastMessage(persistedMessage);
    }

    @Test
    void submitRefundsPointsWhenProviderFails() {
        arrangeRoomAndUser();
        when(pointsService.debit(1L, "image_generation", "image_generation:77"))
                .thenReturn(new PointsDto.DebitResult(0, 10, 90, 123L));
        when(generationClient.submit("", "失败图", 1, "1024*1024", true))
                .thenThrow(new IllegalStateException("quota exhausted"));

        service.submit(
                1L,
                new ImageGenerationDto.GenerateRequest(10L, "失败图", 1, "1024*1024", true));

        assertThat(persistedMessage.getImageGenStatus()).isEqualTo(Message.ImageGenerationStatus.FAILED);
        assertThat(persistedMessage.getMessageStatus()).isEqualTo(Message.MessageStatus.FAILED);
        verify(pointsService).refund(1L, "image_generation", "image_generation:77", "图片生成失败自动退还");
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
        when(generationClient.submit("", "画一座海边城市", 1, "1024*1024", true))
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
                "https://api.novelai.net/ai/generate-image",
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
        verify(generationClient, never()).submit(anyString(), anyString(), anyInt(), anyString(), anyBoolean());
        verify(botImageGenerationClient).generate(providerConfig, "draw a library", "1024*1024");
    }

    @Test
    void submitDefersProviderCallUntilOuterTransactionCommits() throws Exception {
        arrangeRoomAndUser();
        when(pointsService.debit(1L, "image_generation", "image_generation:77"))
                .thenReturn(new PointsDto.DebitResult(0, 10, 90, 123L));
        when(generationClient.submit("", "延迟提交", 1, "1024*1024", true))
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
            verify(generationClient, never()).submit("", "延迟提交", 1, "1024*1024", true);

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            assertThat(persistedMessage.getImageGenStatus()).isEqualTo(Message.ImageGenerationStatus.DONE);
            assertThat(persistedMessage.getFileUrl()).isEqualTo("/api/files/image-gen/generated-2.png");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
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
