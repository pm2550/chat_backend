package com.chatapp.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MessageStarredStateIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("Star/unstar responses and message lists expose starredByMe per viewer")
    void starredByMeReflectsCurrentUsersStars() throws Exception {
        TestUser alice = createUserAndLogin("staralice");
        TestUser bob = createUserAndLogin("starbob");
        Long roomId = createGroupChat(alice, "Star Room " + uniqueSuffix, List.of(bob.id()));
        Long starred = sendMessage(bob, roomId, "worth keeping");
        Long plain = sendMessage(bob, roomId, "just chatter");

        mockMvc.perform(post("/api/v1/messages/" + starred + "/star")
                .header("Authorization", alice.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(starred.intValue()))
                .andExpect(jsonPath("$.data.starredByMe").value(true));

        Map<Long, Object> aliceView = starredByMeById(alice, roomId);
        assertThat(aliceView).containsEntry(starred, true).containsEntry(plain, false);
        // 收藏是个人状态，另一个成员看到的仍是未收藏
        Map<Long, Object> bobView = starredByMeById(bob, roomId);
        assertThat(bobView).containsEntry(starred, false).containsEntry(plain, false);

        mockMvc.perform(get("/api/v1/users/me/starred")
                .header("Authorization", alice.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].id").value(starred.intValue()))
                .andExpect(jsonPath("$.messages[0].starredByMe").value(true));
        mockMvc.perform(get("/api/v1/messages/starred")
                .header("Authorization", alice.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].id").value(starred.intValue()))
                .andExpect(jsonPath("$.messages[0].starredByMe").value(true));

        mockMvc.perform(delete("/api/v1/messages/" + starred + "/star")
                .header("Authorization", alice.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(starred.intValue()))
                .andExpect(jsonPath("$.data.starredByMe").value(false));

        assertThat(starredByMeById(alice, roomId)).containsEntry(starred, false);
    }

    @Test
    @DisplayName("Starred list loads replies and mentions without lazy-loading errors")
    void starredListLoadsReplyAndMentions() throws Exception {
        TestUser alice = createUserAndLogin("replyalice");
        TestUser bob = createUserAndLogin("replybob");
        Long roomId = createGroupChat(alice, "Reply Star " + uniqueSuffix, List.of(bob.id()));
        Long original = sendMessage(alice, roomId, "original question");
        Map<String, Object> reply = data(mockMvc.perform(post("/api/v1/messages/reply")
                .header("Authorization", bob.bearer())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "chatRoomId", roomId,
                        "replyToMessageId", original,
                        "content", "@" + alice.username() + " here is the answer"))))
                .andExpect(status().isOk())
                .andReturn());
        Long replyId = ((Number) reply.get("id")).longValue();

        mockMvc.perform(post("/api/v1/messages/" + replyId + "/star")
                .header("Authorization", alice.bearer()))
                .andExpect(status().isOk());

        for (String path : List.of("/api/v1/users/me/starred", "/api/v1/messages/starred")) {
            mockMvc.perform(get(path)
                    .header("Authorization", alice.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messages[0].id").value(replyId.intValue()))
                    .andExpect(jsonPath("$.messages[0].starredByMe").value(true))
                    .andExpect(jsonPath("$.messages[0].replyToMessage.id").value(original.intValue()))
                    .andExpect(jsonPath("$.messages[0].replyToMessage.content").value("original question"))
                    .andExpect(jsonPath("$.messages[0].mentionedUserIds[0]").value(alice.id().intValue()));
        }
    }

    private Map<Long, Object> starredByMeById(TestUser viewer, Long roomId) throws Exception {
        Map<String, Object> response = body(mockMvc.perform(get("/api/v1/messages/chat-room/" + roomId)
                .header("Authorization", viewer.bearer()))
                .andExpect(status().isOk())
                .andReturn());
        Map<Long, Object> result = new java.util.HashMap<>();
        for (Map<String, Object> message : (List<Map<String, Object>>) response.get("messages")) {
            result.put(((Number) message.get("id")).longValue(), message.get("starredByMe"));
        }
        return result;
    }
}
