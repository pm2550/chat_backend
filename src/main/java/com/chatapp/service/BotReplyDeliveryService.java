package com.chatapp.service;

import com.chatapp.entity.Message;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Delivers chunked bot replies as a short, human-paced sequence. */
@Service
public class BotReplyDeliveryService {

    private static final long MIN_GAP_MS = 420;
    private static final long MAX_GAP_MS = 1200;
    private static final long MS_PER_CHARACTER = 28;

    private final ScheduledExecutorService scheduler;

    public BotReplyDeliveryService() {
        this(Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bot-reply-delivery");
            thread.setDaemon(true);
            return thread;
        }));
    }

    BotReplyDeliveryService(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    public void deliver(List<Message> messages, Consumer<Message> broadcaster) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        broadcaster.accept(messages.get(0));
        scheduleNext(messages, 1, broadcaster);
    }

    private void scheduleNext(
            List<Message> messages,
            int index,
            Consumer<Message> broadcaster) {
        if (index >= messages.size()) {
            return;
        }
        long delayMs = gapAfter(messages.get(index - 1));
        scheduler.schedule(() -> {
            broadcaster.accept(messages.get(index));
            scheduleNext(messages, index + 1, broadcaster);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    static long gapAfter(Message message) {
        String content = message != null ? message.getContent() : null;
        int characters = content == null ? 0 : content.codePointCount(0, content.length());
        return Math.max(MIN_GAP_MS, Math.min(MAX_GAP_MS, characters * MS_PER_CHARACTER));
    }
}
