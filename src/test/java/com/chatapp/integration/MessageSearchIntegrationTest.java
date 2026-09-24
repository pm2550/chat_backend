package com.chatapp.integration;

import com.chatapp.entity.Message;
import com.chatapp.repository.MessageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MessageSearchIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private MessageRepository messageRepository;

    @Test
    @DisplayName("Per-room search reports currentPage, totalPages and hasPrevious from offset")
    void roomSearchReportsPagingMetadata() throws Exception {
        TestUser owner = createUserAndLogin("pagedsearch");
        Long roomId = createGroupChat(owner, "Paged Search " + uniqueSuffix, List.of());
        for (int i = 0; i < 5; i++) {
            sendMessage(owner, roomId, "needle-" + uniqueSuffix + " #" + i);
        }
        sendMessage(owner, roomId, "unrelated chatter");

        mockMvc.perform(get("/api/v1/chat-rooms/" + roomId + "/messages/search")
                .header("Authorization", owner.bearer())
                .param("q", "needle-" + uniqueSuffix)
                .param("limit", "2")
                .param("offset", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(2)))
                .andExpect(jsonPath("$.results", hasSize(2)))
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.offset").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.currentPage").value(1))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.hasPrevious").value(true));

        mockMvc.perform(get("/api/v1/chat-rooms/" + roomId + "/messages/search")
                .header("Authorization", owner.bearer())
                .param("q", "needle-" + uniqueSuffix)
                .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPage").value(0))
                .andExpect(jsonPath("$.hasPrevious").value(false));
    }

    @Test
    @DisplayName("Global search covers only visible messages in the caller's rooms")
    void globalSearchCoversOnlyVisibleMessagesInCallersRooms() throws Exception {
        TestUser alice = createUserAndLogin("gsalice");
        TestUser bob = createUserAndLogin("gsbob");
        TestUser carol = createUserAndLogin("gscarol");
        String keyword = "Kiwi" + uniqueSuffix;

        Long roomOne = createGroupChat(alice, "Search One " + uniqueSuffix, List.of(bob.id()));
        Long roomTwo = createGroupChat(bob, "Search Two " + uniqueSuffix, List.of(alice.id()));
        Long foreignRoom = createGroupChat(carol, "Foreign " + uniqueSuffix, List.of(bob.id()));
        Long clearedRoom = createGroupChat(bob, "Cleared " + uniqueSuffix, List.of(alice.id()));
        Long blockedRoom = createGroupChat(bob, "Blocked " + uniqueSuffix, List.of(alice.id()));

        Long beforeClear = sendMessage(bob, clearedRoom, "old " + keyword + " before clear");
        updateDisplayState(alice, clearedRoom, "CLEAR");
        Long afterClear = sendMessage(bob, clearedRoom, "new " + keyword + " after clear");

        Long inRoomOne = sendMessage(bob, roomOne, "hello " + keyword.toLowerCase() + " one");
        Long inRoomTwo = sendMessage(alice, roomTwo, "HELLO " + keyword.toUpperCase() + " two");
        sendMessage(alice, roomTwo, "nothing to see");
        Long foreign = sendMessage(carol, foreignRoom, "secret " + keyword);
        Long blocked = sendMessage(bob, blockedRoom, "blocked " + keyword);
        updateDisplayState(alice, blockedRoom, "BLOCK");

        Long deleted = sendMessage(bob, roomOne, "deleted " + keyword);
        Message deletedMessage = messageRepository.findById(deleted).orElseThrow();
        deletedMessage.setIsDeleted(true);
        messageRepository.save(deletedMessage);

        Map<String, Object> response = body(mockMvc.perform(get("/api/v1/chat-rooms/messages/search")
                .header("Authorization", alice.bearer())
                .param("q", "  " + keyword + " "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyword").value(keyword))
                .andExpect(jsonPath("$.currentPage").value(0))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.hasPrevious").value(false))
                .andReturn());

        List<Map<String, Object>> messages = (List<Map<String, Object>>) response.get("messages");
        List<Long> ids = messages.stream().map(m -> ((Number) m.get("id")).longValue()).toList();
        // 按时间倒序，且不包含：非成员房间、清空点之前、屏蔽房间、已删除消息
        assertThat(ids).containsExactly(inRoomTwo, inRoomOne, afterClear);
        assertThat(ids).doesNotContain(beforeClear, foreign, blocked, deleted);
        Map<Long, Long> roomByMessage = new java.util.HashMap<>();
        messages.forEach(m -> roomByMessage.put(
                ((Number) m.get("id")).longValue(),
                ((Number) m.get("chatRoomId")).longValue()));
        assertThat(roomByMessage).containsEntry(inRoomOne, roomOne)
                .containsEntry(inRoomTwo, roomTwo)
                .containsEntry(afterClear, clearedRoom);

        // bob 屏蔽/清空都没做过，能看到自己所在房间的全部未删除命中（包括 carol 的房间）
        mockMvc.perform(get("/api/v1/chat-rooms/messages/search")
                .header("Authorization", bob.bearer())
                .param("q", keyword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(6));
    }

    @Test
    @DisplayName("Global search pages results and clamps the page size")
    void globalSearchPagesResults() throws Exception {
        TestUser owner = createUserAndLogin("gspager");
        Long roomId = createGroupChat(owner, "Global Pager " + uniqueSuffix, List.of());
        String keyword = "pager" + uniqueSuffix;
        for (int i = 0; i < 3; i++) {
            sendMessage(owner, roomId, keyword + " " + i);
        }

        mockMvc.perform(get("/api/v1/chat-rooms/messages/search")
                .header("Authorization", owner.bearer())
                .param("q", keyword)
                .param("page", "1")
                .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(1)))
                .andExpect(jsonPath("$.messages[0].chatRoomId").value(roomId.intValue()))
                .andExpect(jsonPath("$.currentPage").value(1))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.hasPrevious").value(true));

        mockMvc.perform(get("/api/v1/chat-rooms/messages/search")
                .header("Authorization", owner.bearer())
                .param("q", keyword)
                .param("size", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(1)))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    @DisplayName("Global search rejects a blank keyword and requires authentication")
    void globalSearchRejectsBlankKeyword() throws Exception {
        TestUser owner = createUserAndLogin("gsblank");

        mockMvc.perform(get("/api/v1/chat-rooms/messages/search")
                .header("Authorization", owner.bearer())
                .param("q", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("关键词不能为空"));
        mockMvc.perform(get("/api/v1/chat-rooms/messages/search")
                .header("Authorization", owner.bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("关键词不能为空"));
        mockMvc.perform(get("/api/v1/chat-rooms/messages/search")
                .param("q", "anything"))
                .andExpect(status().isUnauthorized());
    }
}
