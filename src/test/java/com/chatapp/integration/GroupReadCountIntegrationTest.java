package com.chatapp.integration;

import com.chatapp.entity.User;
import com.chatapp.repository.UserRepository;
import com.chatapp.websocket.RawWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.socket.CloseStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 群里的已读数 = 读到这条的其他成员人数：整房间已读和逐条已读算同一个人一次，
 * 关了已读回执的人不计数也不进名单，已读名单和已读数一致。
 */
@DisplayName("Group read counts")
class GroupReadCountIntegrationTest extends IntegrationTestSupport {

    @Autowired private RawWebSocketHandler rawWebSocketHandler;
    @Autowired private UserRepository userRepository;

    private final List<RoomRealtimeSyncIntegrationTest.RecordingSession> openSessions = new ArrayList<>();

    @AfterEach
    void closeSessions() {
        for (var session : openSessions) {
            rawWebSocketHandler.afterConnectionClosed(session, CloseStatus.NORMAL);
        }
        openSessions.clear();
    }

    @Test
    @DisplayName("Opening a room (read-all + per-message reads) counts each reader once, for every reader")
    void eachReaderCountsOnce() throws Exception {
        TestUser alice = createUserAndLogin("rc_alice");
        TestUser bob = createUserAndLogin("rc_bob");
        TestUser carol = createUserAndLogin("rc_carol");
        TestUser dave = createUserAndLogin("rc_dave");
        Long room = createGroupChat(alice, "read-count", List.of(bob.id(), carol.id(), dave.id()));
        Long first = sendMessage(alice, room, "one");
        Long second = sendMessage(alice, room, "two");

        // 客户端打开会话时两条都会调：整房间已读 + 可见消息逐条已读。
        openRoom(bob, room, first, second);
        assertEquals(1, readCount(alice, room, first), "bob was counted twice");
        assertEquals(1, readCount(alice, room, second));

        // 第二个读者走同样的路径也要被计上（以前整房间已读在群里最多加到 1）。
        openRoom(carol, room, first, second);
        assertEquals(2, readCount(alice, room, first));
        assertEquals(2, readCount(alice, room, second));
        assertEquals("READ", messageField(alice, room, second, "messageStatus"));

        // 关了已读回执的人读了也不计数。
        mockMvc.perform(put("/api/profile/settings")
                        .header("Authorization", dave.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("readReceiptsEnabled", false))))
                .andExpect(status().isOk());
        openRoom(dave, room, first, second);
        assertEquals(2, readCount(alice, room, second));

        // 已读名单与已读数是同一批人。
        List<Map<String, Object>> readers = readBy(alice, second);
        assertEquals(2, readers.size());
        assertEquals(List.of(bob.id(), carol.id()),
                readers.stream().map(r -> ((Number) r.get("userId")).longValue()).sorted().toList());

        // 之后关掉回执的读者，已读数和名单一起把他去掉。
        mockMvc.perform(put("/api/profile/settings")
                        .header("Authorization", carol.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("readReceiptsEnabled", false))))
                .andExpect(status().isOk());
        assertEquals(1, readCount(alice, room, second));
        assertEquals(1, readBy(alice, second).size());
    }

    @Test
    @DisplayName("A per-message read without read-all counts the reader for that message and earlier ones")
    void perMessageReadAdvancesTheReader() throws Exception {
        TestUser alice = createUserAndLogin("rc_alice");
        TestUser bob = createUserAndLogin("rc_bob");
        Long room = createGroupChat(alice, "read-count", List.of(bob.id()));
        Long first = sendMessage(alice, room, "one");
        Long second = sendMessage(alice, room, "two");
        Long third = sendMessage(alice, room, "three");

        markRead(bob, second);

        assertEquals(1, readCount(alice, room, first));
        assertEquals(1, readCount(alice, room, second));
        assertEquals(0, readCount(alice, room, third));
        assertEquals(1, readBy(alice, first).size());
        assertEquals(0, readBy(alice, third).size());
    }

    @Test
    @DisplayName("Read receipts carry the advanced range once; re-reading inside it is not re-broadcast")
    void receiptsCarryTheAdvancedRange() throws Exception {
        TestUser alice = createUserAndLogin("rc_alice");
        TestUser bob = createUserAndLogin("rc_bob");
        Long room = createGroupChat(alice, "read-count", List.of(bob.id()));
        Long first = sendMessage(alice, room, "one");
        Long second = sendMessage(alice, room, "two");
        var aliceSession = connect(alice);
        var bobDesktop = connect(bob);
        aliceSession.messages.clear();
        bobDesktop.messages.clear();

        mockMvc.perform(post("/api/v1/messages/chat-room/" + room + "/read-all")
                        .header("Authorization", bob.bearer()))
                .andExpect(status().isOk());

        JsonNode receipt = await(aliceSession, "read_receipt", 3000);
        assertNotNull(receipt);
        assertTrue(receipt.has("previousLastReadMessageId"));
        assertTrue(receipt.get("previousLastReadMessageId").isNull());
        assertEquals(second.longValue(), receipt.path("lastReadMessageId").asLong());
        assertNotNull(await(bobDesktop, "read_receipt", 3000), "bob's other device syncs its unread count");

        // 已读位置没推进：对别人来说什么都没变，不再推送（以前这里又推一次，客户端再加一）。
        markRead(bob, first);
        assertNull(await(aliceSession, "read_receipt", 300));
        assertEquals(1, readCount(alice, room, first));

        Long third = sendMessage(alice, room, "three");
        aliceSession.messages.clear();
        markRead(bob, third);
        JsonNode next = await(aliceSession, "read_receipt", 3000);
        assertNotNull(next);
        assertEquals(second.longValue(), next.path("previousLastReadMessageId").asLong());
        assertEquals(third.longValue(), next.path("lastReadMessageId").asLong());
    }

    private void openRoom(TestUser reader, Long room, Long... visibleMessageIds) throws Exception {
        mockMvc.perform(post("/api/v1/messages/chat-room/" + room + "/read-all")
                        .header("Authorization", reader.bearer()))
                .andExpect(status().isOk());
        for (Long id : visibleMessageIds) {
            markRead(reader, id);
        }
    }

    private void markRead(TestUser reader, Long messageId) throws Exception {
        mockMvc.perform(post("/api/v1/messages/" + messageId + "/read")
                        .header("Authorization", reader.bearer()))
                .andExpect(status().isOk());
    }

    private int readCount(TestUser viewer, Long room, Long messageId) throws Exception {
        return ((Number) messageField(viewer, room, messageId, "readCount")).intValue();
    }

    private Object messageField(TestUser viewer, Long room, Long messageId, String field) throws Exception {
        Map<String, Object> page = body(mockMvc.perform(get("/api/v1/messages/chat-room/" + room)
                        .header("Authorization", viewer.bearer()))
                .andExpect(status().isOk())
                .andReturn());
        List<Map<String, Object>> messages = (List<Map<String, Object>>) page.get("messages");
        return messages.stream()
                .filter(m -> ((Number) m.get("id")).longValue() == messageId)
                .findFirst()
                .orElseThrow()
                .get(field);
    }

    private List<Map<String, Object>> readBy(TestUser viewer, Long messageId) throws Exception {
        return (List<Map<String, Object>>) body(mockMvc.perform(get("/api/v1/messages/" + messageId + "/read-by")
                        .header("Authorization", viewer.bearer()))
                .andExpect(status().isOk())
                .andReturn()).get("data");
    }

    private RoomRealtimeSyncIntegrationTest.RecordingSession connect(TestUser user) {
        User entity = userRepository.findById(user.id()).orElseThrow();
        var session = new RoomRealtimeSyncIntegrationTest.RecordingSession();
        session.getAttributes().put(RawWebSocketHandler.ATTR_USER, entity);
        rawWebSocketHandler.afterConnectionEstablished(session);
        openSessions.add(session);
        return session;
    }

    private JsonNode await(RoomRealtimeSyncIntegrationTest.RecordingSession session, String type, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String raw = session.messages.poll(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (raw == null) {
                return null;
            }
            JsonNode node = objectMapper.readTree(raw);
            if (type.equals(node.path("type").asText())) {
                return node;
            }
        }
        return null;
    }
}
