package com.chatapp.websocket;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory call membership registry for small P2P mesh calls.
 *
 * This is intentionally non-persistent: if the backend restarts, callers must
 * rejoin and renegotiate. Batch 5 explicitly excludes call session storage.
 */
@Component
public class CallRoomRegistry {

    public static final int MAX_PARTICIPANTS = 6;

    private final Map<String, LinkedHashSet<Long>> participantsByCall = new ConcurrentHashMap<>();

    public synchronized JoinResult addParticipant(String callId, Long userId) {
        LinkedHashSet<Long> current = participantsByCall.computeIfAbsent(callId, ignored -> new LinkedHashSet<>());
        List<Long> before = List.copyOf(current);
        if (!current.contains(userId) && current.size() >= MAX_PARTICIPANTS) {
            return new JoinResult(false, before, before);
        }
        current.add(userId);
        return new JoinResult(true, before, List.copyOf(current));
    }

    public synchronized List<Long> removeParticipant(String callId, Long userId) {
        LinkedHashSet<Long> current = participantsByCall.get(callId);
        if (current == null) {
            return List.of();
        }
        current.remove(userId);
        if (current.isEmpty()) {
            participantsByCall.remove(callId);
            return List.of();
        }
        return List.copyOf(current);
    }

    public List<Long> getParticipants(String callId) {
        Set<Long> current = participantsByCall.get(callId);
        if (current == null) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(current));
    }

    public boolean isFull(String callId) {
        return getParticipants(callId).size() >= MAX_PARTICIPANTS;
    }

    public void clear() {
        participantsByCall.clear();
    }

    public record JoinResult(
            boolean accepted,
            List<Long> existingParticipants,
            List<Long> participants
    ) {
    }
}
