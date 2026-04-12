package com.chatapp.service;

import com.chatapp.repository.MessageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SelfDestructService")
class SelfDestructServiceTest {

    @Mock private MessageRepository messageRepository;
    @InjectMocks private SelfDestructService service;

    @Test
    @DisplayName("destroyExpiredMessages calls repository with current time")
    void calls_repo() {
        when(messageRepository.deleteExpiredSelfDestructMessages(any(LocalDateTime.class))).thenReturn(3);
        LocalDateTime before = LocalDateTime.now();
        service.destroyExpiredMessages();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(messageRepository).deleteExpiredSelfDestructMessages(captor.capture());
        LocalDateTime captured = captor.getValue();
        assert !captured.isBefore(before);
    }

    @Test
    @DisplayName("destroyExpiredMessages is safe when zero expired rows")
    void zero_deletes_safe() {
        when(messageRepository.deleteExpiredSelfDestructMessages(any(LocalDateTime.class))).thenReturn(0);
        service.destroyExpiredMessages(); // no exception
        verify(messageRepository).deleteExpiredSelfDestructMessages(any(LocalDateTime.class));
    }
}
