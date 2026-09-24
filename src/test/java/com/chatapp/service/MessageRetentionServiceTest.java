package com.chatapp.service;

import com.chatapp.entity.Message;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.StickerPackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageRetentionServiceTest {
    @Mock private MessageRepository messageRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private StickerPackRepository stickerPackRepository;

    private MessageRetentionService service;

    @BeforeEach
    void setUp() {
        service = new MessageRetentionService(messageRepository, fileStorageService, stickerPackRepository);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "retentionDays", 30L);
        ReflectionTestUtils.setField(service, "batchSize", 10);
    }

    @Test
    void cleanupExpiredMessagesSoftDeletesRowsAndDeletesMessageScopedFiles() throws Exception {
        Message image = new Message();
        image.setId(1L);
        image.setContent("old image");
        image.setFileUrl("/api/files/chat/old.png");
        image.setThumbnailUrl("/api/files/chat/thumb.png");
        image.setImageGenUrl("/api/files/image-gen/generated.png");
        image.setFileName("old.png");
        image.setFileType("image/png");
        image.setFileSize(123L);
        image.setLinkPreviewJson("{}");
        image.setEncryptedContent(new byte[] {1, 2, 3});

        Message text = new Message();
        text.setId(2L);
        text.setContent("old text");

        when(messageRepository.findExpiredForRetention(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(image, text)))
                .thenReturn(new PageImpl<>(List.of()));
        when(fileStorageService.deleteFile("/api/files/chat/old.png")).thenReturn(true);
        when(fileStorageService.deleteFile("/api/files/chat/thumb.png")).thenReturn(true);
        when(fileStorageService.deleteFile("/api/files/image-gen/generated.png")).thenReturn(true);
        when(fileStorageService.listExpiredImageGenFileUrls(any(LocalDateTime.class), anyInt())).thenReturn(List.of());

        MessageRetentionService.CleanupResult result = service.cleanupExpiredMessages();

        assertThat(result.expiredMessages()).isEqualTo(2);
        assertThat(result.deletedFiles()).isEqualTo(3);
        assertThat(image.getIsDeleted()).isTrue();
        assertThat(image.getContent()).isEqualTo("[消息已过期]");
        assertThat(image.getFileUrl()).isNull();
        assertThat(image.getImageGenUrl()).isNull();
        assertThat(image.getThumbnailUrl()).isNull();
        assertThat(image.getEncryptedContent()).isNull();
        assertThat(text.getIsDeleted()).isTrue();
        verify(messageRepository).saveAll(List.of(image, text));
    }

    @Test
    void cleanupExpiredMessagesDeletesOnlyUnreferencedExpiredImageGenerationOrphans() throws Exception {
        when(messageRepository.findExpiredForRetention(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(fileStorageService.listExpiredImageGenFileUrls(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of("/api/files/image-gen/orphan.png", "/api/files/image-gen/live.png"));
        when(messageRepository.existsActiveMessageReferencingFileUrl("/api/files/image-gen/orphan.png"))
                .thenReturn(false);
        when(messageRepository.existsActiveMessageReferencingFileUrl("/api/files/image-gen/live.png"))
                .thenReturn(true);
        when(fileStorageService.deleteFile("/api/files/image-gen/orphan.png")).thenReturn(true);

        MessageRetentionService.CleanupResult result = service.cleanupExpiredMessages();

        assertThat(result.orphanImageFiles()).isEqualTo(1);
        verify(fileStorageService).deleteFile("/api/files/image-gen/orphan.png");
        verify(fileStorageService, never()).deleteFile("/api/files/image-gen/live.png");
    }

    @Test
    void cleanupExpiredMessagesKeepsFilesStillReferencedByForwardedCopiesOrStickerPacks() throws Exception {
        Message forwardedSource = new Message();
        forwardedSource.setId(3L);
        forwardedSource.setFileUrl("/api/files/chat/forwarded.png");
        Message legacySticker = new Message();
        legacySticker.setId(4L);
        legacySticker.setFileUrl("/api/files/chat/legacy-sticker.png");

        when(messageRepository.findExpiredForRetention(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(forwardedSource, legacySticker)))
                .thenReturn(new PageImpl<>(List.of()));
        when(messageRepository.existsActiveMessageReferencingFileUrl("/api/files/chat/forwarded.png"))
                .thenReturn(true);
        when(stickerPackRepository.existsReferencingUrl("/api/files/chat/legacy-sticker.png"))
                .thenReturn(true);
        when(fileStorageService.listExpiredImageGenFileUrls(any(LocalDateTime.class), anyInt())).thenReturn(List.of());

        MessageRetentionService.CleanupResult result = service.cleanupExpiredMessages();

        assertThat(result.expiredMessages()).isEqualTo(2);
        assertThat(result.deletedFiles()).isZero();
        assertThat(forwardedSource.getIsDeleted()).isTrue();
        verify(fileStorageService, never()).deleteFile(anyString());
    }

    @Test
    void cleanupExpiredMessagesChecksReferencesOnlyAfterTheWholeBatchIsExpired() throws Exception {
        Message first = new Message();
        first.setId(5L);
        first.setFileUrl("/api/files/chat/shared.png");
        Message second = new Message();
        second.setId(6L);
        second.setFileUrl("/api/files/chat/shared.png");

        when(messageRepository.findExpiredForRetention(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(first, second)))
                .thenReturn(new PageImpl<>(List.of()));
        when(messageRepository.existsActiveMessageReferencingFileUrl("/api/files/chat/shared.png"))
                .thenAnswer(invocation -> !Boolean.TRUE.equals(first.getIsDeleted())
                        || !Boolean.TRUE.equals(second.getIsDeleted()));
        when(fileStorageService.deleteFile("/api/files/chat/shared.png")).thenReturn(true);
        when(fileStorageService.listExpiredImageGenFileUrls(any(LocalDateTime.class), anyInt())).thenReturn(List.of());

        MessageRetentionService.CleanupResult result = service.cleanupExpiredMessages();

        assertThat(result.deletedFiles()).isEqualTo(1);
        verify(fileStorageService, times(1)).deleteFile("/api/files/chat/shared.png");
    }

    @Test
    void cleanupExpiredMessagesCanBeDisabled() throws IOException {
        ReflectionTestUtils.setField(service, "enabled", false);

        MessageRetentionService.CleanupResult result = service.cleanupExpiredMessages();

        assertThat(result.disabled()).isTrue();
        verifyNoInteractions(messageRepository, fileStorageService, stickerPackRepository);
    }

    @Test
    void cleanupExpiredMessagesUsesThirtyDayCutoff() throws IOException {
        when(messageRepository.findExpiredForRetention(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(fileStorageService.listExpiredImageGenFileUrls(any(LocalDateTime.class), anyInt())).thenReturn(List.of());
        LocalDateTime before = LocalDateTime.now().minusDays(30).minusSeconds(2);

        service.cleanupExpiredMessages();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(messageRepository).findExpiredForRetention(cutoff.capture(), any(Pageable.class));
        assertThat(cutoff.getValue()).isAfter(before);
        assertThat(cutoff.getValue()).isBefore(LocalDateTime.now().minusDays(30).plusSeconds(2));
    }
}
