package com.chatapp.service;

import com.chatapp.entity.Message;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class BotReplyDeliveryServiceTest {

    @Test
    void deliversFirstReplyImmediatelyAndSchedulesFollowingRepliesInOrder() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        BotReplyDeliveryService service = new BotReplyDeliveryService(scheduler);
        List<Message> delivered = new ArrayList<>();
        Message first = message("第一句。");
        Message second = message("第二句稍微长一点。");
        Message third = message("第三句。");

        service.deliver(List.of(first, second, third), delivered::add);

        assertEquals(List.of(first), delivered);
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> instants = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler, times(2)).schedule(tasks.capture(), instants.capture());
        assertTrue(instants.getAllValues().get(1).isAfter(instants.getAllValues().get(0)));

        tasks.getAllValues().forEach(Runnable::run);
        assertEquals(List.of(first, second, third), delivered);
    }

    @Test
    void clampsHumanizedGapForShortAndLongMessages() {
        assertEquals(420, BotReplyDeliveryService.gapAfter(message("好")));
        assertEquals(1200, BotReplyDeliveryService.gapAfter(message("很长".repeat(100))));
    }

    private static Message message(String content) {
        Message message = new Message();
        message.setContent(content);
        return message;
    }
}
