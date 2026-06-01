package com.chatapp.service;

import com.chatapp.dto.UrlPreviewDto;
import com.chatapp.entity.Message;
import com.chatapp.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageLinkPreviewServiceTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private UrlPreviewService urlPreviewService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void enrichMessageStoresFetchedPreviewJson() throws Exception {
        Message message = new Message();
        message.setId(42L);
        when(messageRepository.findById(42L)).thenReturn(Optional.of(message));
        when(urlPreviewService.fetch("https://example.com/post")).thenReturn(new UrlPreviewDto(
                "https://example.com/post",
                "Example",
                "Description",
                "https://example.com/cover.png",
                "example.com",
                "https://example.com/favicon.ico"));

        MessageLinkPreviewService service = new MessageLinkPreviewService(
                messageRepository,
                urlPreviewService,
                objectMapper);

        service.enrichMessage(42L, "看这个 https://example.com/post。");

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        assertTrue(captor.getValue().getLinkPreviewJson().contains("\"title\":\"Example\""));
    }

    @Test
    void ignoresMessagesWithoutHttpsUrl() {
        MessageLinkPreviewService service = new MessageLinkPreviewService(
                messageRepository,
                urlPreviewService,
                objectMapper);

        service.enrichMessage(42L, "http://example.com is not previewed");

        verify(urlPreviewService, never()).fetch(org.mockito.ArgumentMatchers.anyString());
        verify(messageRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void firstHttpsUrlTrimsTrailingPunctuation() {
        MessageLinkPreviewService service = new MessageLinkPreviewService(
                messageRepository,
                urlPreviewService,
                objectMapper);

        assertEquals("https://example.com/a", service.firstHttpsUrl("打开 https://example.com/a，"));
    }
}
