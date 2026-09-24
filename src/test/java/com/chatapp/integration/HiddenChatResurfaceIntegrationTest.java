package com.chatapp.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 被“移出消息列表”的会话在有新消息时应重新出现；被屏蔽的会话不应被新消息带回来。
 */
class HiddenChatResurfaceIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("A hidden room comes back for the member when another member sends a message")
    void hiddenRoomReturnsWhenOtherMemberSends() throws Exception {
        TestUser alice = createUserAndLogin("hidealice");
        TestUser bob = createUserAndLogin("hidebob");
        Long roomId = createGroupChat(alice, "Hide Room " + uniqueSuffix, List.of(bob.id()));
        sendMessage(alice, roomId, "first");

        updateDisplayState(bob, roomId, "REMOVE_FROM_LIST");

        assertThat(summaryFor(bob, roomId, false)).isNull();
        Map<String, Object> hidden = summaryFor(bob, roomId, true);
        assertThat(hidden).isNotNull();
        assertThat(hidden.get("hiddenAt")).isNotNull();

        sendMessage(alice, roomId, "are you there?");

        Map<String, Object> resurfaced = summaryFor(bob, roomId, false);
        assertThat(resurfaced).isNotNull();
        assertThat(resurfaced.get("hiddenAt")).isNull();
        assertThat(((Number) resurfaced.get("unreadCount")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("A hidden room comes back for the member when they send in it themselves")
    void hiddenRoomReturnsWhenMemberSendsThemselves() throws Exception {
        TestUser alice = createUserAndLogin("selfalice");
        TestUser bob = createUserAndLogin("selfbob");
        Long roomId = createGroupChat(alice, "Self Room " + uniqueSuffix, List.of(bob.id()));

        updateDisplayState(bob, roomId, "REMOVE_FROM_LIST");
        assertThat(summaryFor(bob, roomId, false)).isNull();

        sendMessage(bob, roomId, "back again");

        Map<String, Object> resurfaced = summaryFor(bob, roomId, false);
        assertThat(resurfaced).isNotNull();
        assertThat(resurfaced.get("hiddenAt")).isNull();
    }

    @Test
    @DisplayName("A blocked room stays out of the list when another member sends a message")
    void blockedRoomDoesNotReturn() throws Exception {
        TestUser alice = createUserAndLogin("blockalice");
        TestUser bob = createUserAndLogin("blockbob");
        Long roomId = createGroupChat(alice, "Block Room " + uniqueSuffix, List.of(bob.id()));

        updateDisplayState(bob, roomId, "BLOCK");
        sendMessage(alice, roomId, "knock knock");

        assertThat(summaryFor(bob, roomId, false)).isNull();
        assertThat(summaryFor(bob, roomId, true)).isNull();
        Map<String, Object> blocked = summaryForWithBlocked(bob, roomId);
        assertThat(blocked).isNotNull();
        assertThat(blocked.get("hiddenAt")).isNotNull();
        assertThat(((Number) blocked.get("unreadCount")).intValue()).isZero();
    }

    @Test
    @DisplayName("Replies, forwards and polls also bring a hidden room back")
    void replyForwardAndPollResurfaceHiddenRoom() throws Exception {
        TestUser alice = createUserAndLogin("pathalice");
        TestUser bob = createUserAndLogin("pathbob");
        Long roomId = createGroupChat(alice, "Paths Room " + uniqueSuffix, List.of(bob.id()));
        Long otherRoom = createGroupChat(alice, "Source Room " + uniqueSuffix, List.of(bob.id()));
        Long original = sendMessage(alice, roomId, "original");
        Long toForward = sendMessage(alice, otherRoom, "forward me");

        updateDisplayState(bob, roomId, "REMOVE_FROM_LIST");
        mockMvc.perform(post("/api/v1/messages/reply")
                .header("Authorization", alice.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "chatRoomId", roomId,
                        "replyToMessageId", original,
                        "content", "a reply"))))
                .andExpect(status().isOk());
        assertThat(summaryFor(bob, roomId, false)).as("reply").isNotNull();

        updateDisplayState(bob, roomId, "REMOVE_FROM_LIST");
        mockMvc.perform(post("/api/v1/messages/" + toForward + "/forward")
                .header("Authorization", alice.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("targetChatRoomId", roomId))))
                .andExpect(status().isOk());
        assertThat(summaryFor(bob, roomId, false)).as("forward").isNotNull();

        updateDisplayState(bob, roomId, "REMOVE_FROM_LIST");
        mockMvc.perform(post("/api/v1/polls")
                .header("Authorization", alice.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "chatRoomId", roomId,
                        "question", "午饭吃什么",
                        "options", List.of("面", "饭")))))
                .andExpect(status().isOk());
        assertThat(summaryFor(bob, roomId, false)).as("poll").isNotNull();
    }

    private Map<String, Object> summaryFor(TestUser user, Long roomId, boolean includeHidden) throws Exception {
        return findRoom(body(mockMvc.perform(get("/api/v1/chat-rooms/summaries")
                .header("Authorization", user.bearer())
                .param("size", "100")
                .param("includeHidden", String.valueOf(includeHidden)))
                .andExpect(status().isOk())
                .andReturn()), roomId);
    }

    private Map<String, Object> summaryForWithBlocked(TestUser user, Long roomId) throws Exception {
        return findRoom(body(mockMvc.perform(get("/api/v1/chat-rooms/summaries")
                .header("Authorization", user.bearer())
                .param("size", "100")
                .param("includeHidden", "true")
                .param("includeBlocked", "true"))
                .andExpect(status().isOk())
                .andReturn()), roomId);
    }

    private Map<String, Object> findRoom(Map<String, Object> response, Long roomId) {
        for (Map<String, Object> room : (List<Map<String, Object>>) response.get("chatRooms")) {
            if (((Number) room.get("id")).longValue() == roomId) {
                return room;
            }
        }
        return null;
    }
}
