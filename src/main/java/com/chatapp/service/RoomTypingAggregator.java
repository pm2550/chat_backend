package com.chatapp.service;

import lombok.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RoomTypingAggregator {
    private static final Duration ALIVE_WINDOW = Duration.ofSeconds(3);

    private final Map<Long, Map<Long, TypingUser>> activeByRoom = new ConcurrentHashMap<>();
    private final Map<Long, List<Long>> lastPublishedUserIds = new ConcurrentHashMap<>();

    public void update(Long roomId, Long userId, String userName, boolean isTyping) {
        if (roomId == null || userId == null) {
            return;
        }
        Map<Long, TypingUser> room = activeByRoom.computeIfAbsent(
                roomId,
                ignored -> new ConcurrentHashMap<>());
        if (!isTyping) {
            room.remove(userId);
            return;
        }
        room.put(userId, new TypingUser(userId, userName, Instant.now()));
    }

    public List<TypingSnapshot> drainChangedSnapshots() {
        Instant now = Instant.now();
        List<TypingSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<Long, Map<Long, TypingUser>> roomEntry : activeByRoom.entrySet()) {
            Long roomId = roomEntry.getKey();
            Map<Long, TypingUser> users = roomEntry.getValue();
            users.entrySet().removeIf(entry ->
                    Duration.between(entry.getValue().getLastTypingAt(), now).compareTo(ALIVE_WINDOW) > 0);

            List<TypingUser> aliveUsers = users.values().stream()
                    .sorted(java.util.Comparator.comparing(TypingUser::getUserId))
                    .toList();
            List<Long> aliveIds = aliveUsers.stream().map(TypingUser::getUserId).toList();
            List<Long> lastIds = lastPublishedUserIds.getOrDefault(roomId, List.of());
            if (Objects.equals(aliveIds, lastIds)) {
                continue;
            }
            lastPublishedUserIds.put(roomId, aliveIds);
            snapshots.add(new TypingSnapshot(
                    roomId,
                    aliveIds,
                    aliveUsers.stream().map(TypingUser::getUserName).toList()));
        }
        return snapshots;
    }

    @Value
    public static class TypingSnapshot {
        Long roomId;
        List<Long> userIds;
        List<String> userNames;
    }

    @Value
    private static class TypingUser {
        Long userId;
        String userName;
        Instant lastTypingAt;
    }
}
