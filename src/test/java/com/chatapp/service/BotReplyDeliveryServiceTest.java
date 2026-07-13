package com.chatapp.service;

import com.chatapp.entity.Message;
import com.chatapp.entity.BotConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BotReplyDeliveryServiceTest {

    @Test
    void deliversFirstReplyImmediatelyAndSchedulesFollowingRepliesInOrder() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        BotReplyDeliveryService service = new BotReplyDeliveryService(scheduler);
        List<Message> delivered = new ArrayList<>();
        Message first = message("第一句。");
        Message second = message("第二句稍微长一点。");
        Message third = message("第三句。");

        service.deliver(List.of(first, second, third), delivered::add);

        assertEquals(List.of(first), delivered);
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Long> delays = ArgumentCaptor.forClass(Long.class);
        verify(scheduler).schedule(
                tasks.capture(), delays.capture(), org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS));
        assertEquals(BotReplyDeliveryService.gapAfter(first), delays.getValue());

        tasks.getValue().run();
        assertEquals(List.of(first, second), delivered);

        tasks = ArgumentCaptor.forClass(Runnable.class);
        delays = ArgumentCaptor.forClass(Long.class);
        verify(scheduler, org.mockito.Mockito.times(2)).schedule(
                tasks.capture(), delays.capture(), org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS));
        assertEquals(BotReplyDeliveryService.gapAfter(second), delays.getAllValues().get(1));

        tasks.getAllValues().get(1).run();
        assertEquals(List.of(first, second, third), delivered);
    }

    @Test
    void clampsHumanizedGapForShortAndLongMessages() {
        assertEquals(420, BotReplyDeliveryService.gapAfter(message("好")));
        assertEquals(1200, BotReplyDeliveryService.gapAfter(message("很长".repeat(100))));
    }

    @Test
    void usesConfiguredBotReplyInterval() {
        Message message = message("短句");
        BotConfig bot = new BotConfig();
        bot.setReplyIntervalSeconds(3.5);
        message.setBotConfig(bot);

        assertEquals(3500, BotReplyDeliveryService.gapAfter(message));

        bot.setReplyIntervalSeconds(99.0);
        assertEquals(10000, BotReplyDeliveryService.gapAfter(message));
    }

    private static Message message(String content) {
        Message message = new Message();
        message.setContent(content);
        return message;
    }
}
