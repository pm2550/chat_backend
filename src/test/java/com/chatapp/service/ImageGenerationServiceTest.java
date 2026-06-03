package com.chatapp.service;

import com.chatapp.dto.ImageGenerationDto;
import com.chatapp.dto.PointsDto;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.Message;
import com.chatapp.entity.ProviderCredential;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageGenerationServiceTest {

    @Mock private MessageRepository messageRepository;
    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private UserRepository userRepository;
    @Mock private MessageService messageService;
    @Mock private PointsService pointsService;
    @Mock private ProviderCredentialService credentialService;
    @Mock private ImageGenerationClient generationClient;
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
                credentialService,
                generationClient,
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
        ProviderCredential credential = new ProviderCredential();
        credential.setId(9L);
        when(pointsService.debit(1L, "image_generation", "image_generation:77"))
                .thenReturn(new PointsDto.DebitResult(0, 10, 90, 123L));
        when(credentialService.getLatestActiveCredential(eq(1L), any()))
                .thenReturn(credential);
        when(credentialService.decrypt(credential)).thenReturn("dashscope-key");
        when(generationClient.submit("dashscope-key", "画一只蓝色机器人", 1, "1024*1024"))
                .thenReturn(new ImageGenerationClient.SubmitResult("task-1"));
        when(generationClient.poll("dashscope-key", "task-1"))
                .thenReturn(new ImageGenerationClient.PollResult(
                        ImageGenerationClient.PollResult.Status.SUCCEEDED,
                        "https://example.test/out.png",
                        null));
        when(generationClient.download("https://example.test/out.png"))
                .thenReturn(new byte[]{1, 2, 3});
        when(fileStorageService.uploadGeneratedImage(eq("image-generation-77.png"), eq("image/png"), any(byte[].class)))
                .thenReturn("/api/files/image-gen/generated.png");

        ImageGenerationDto.GenerateResponse response = service.submit(
                1L,
                new ImageGenerationDto.GenerateRequest(10L, "画一只蓝色机器人", 1, "1024*1024"));

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
        ProviderCredential credential = new ProviderCredential();
        when(pointsService.debit(1L, "image_generation", "image_generation:77"))
                .thenReturn(new PointsDto.DebitResult(0, 10, 90, 123L));
        when(credentialService.getLatestActiveCredential(eq(1L), any()))
                .thenReturn(credential);
        when(credentialService.decrypt(credential)).thenReturn("dashscope-key");
        when(generationClient.submit("dashscope-key", "失败图", 1, "1024*1024"))
                .thenThrow(new IllegalStateException("quota exhausted"));

        service.submit(
                1L,
                new ImageGenerationDto.GenerateRequest(10L, "失败图", 1, "1024*1024"));

        assertThat(persistedMessage.getImageGenStatus()).isEqualTo(Message.ImageGenerationStatus.FAILED);
        assertThat(persistedMessage.getMessageStatus()).isEqualTo(Message.MessageStatus.FAILED);
        verify(pointsService).refund(1L, "image_generation", "image_generation:77", "图片生成失败自动退还");
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
