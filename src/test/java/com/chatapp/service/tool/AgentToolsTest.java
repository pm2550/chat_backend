package com.chatapp.service.tool;

import com.chatapp.entity.ChatRoomMember;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolsTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readRecentMessagesReturnsChronologicalMessagesAndForcesCurrentRoom() {
        MessageRepository repository = mock(MessageRepository.class);
        when(repository.findRecentMessages(eq(10L), eq(2))).thenReturn(List.of(
                message(2L, "Bob", "newer"),
                message(1L, "Alice", "older")));
        ReadRecentMessagesTool tool = new ReadRecentMessagesTool(repository, objectMapper);

        JsonNode result = tool.execute(objectMapper.createObjectNode().put("roomId", 999).put("n", 2),
                new ToolContext(10L, 1L, 77L));

        assertEquals(10L, result.path("roomId").asLong());
        assertEquals("older", result.path("messages").path(0).path("content").asText());
        assertEquals("newer", result.path("messages").path(1).path("content").asText());
    }

    @Test
    void searchMessagesReturnsMatchesFromCurrentRoomOnly() {
        MessageRepository repository = mock(MessageRepository.class);
        when(repository.searchInChatRoom(eq(10L), eq("image"), eq(PageRequest.of(0, 3))))
                .thenReturn(new PageImpl<>(List.of(message(3L, "Alice", "image generation note"))));
        SearchMessagesTool tool = new SearchMessagesTool(repository, objectMapper);

        JsonNode result = tool.execute(objectMapper.createObjectNode()
                        .put("roomId", 999)
                        .put("keyword", "image")
                        .put("maxResults", 3),
                new ToolContext(10L, 1L, 77L));

        assertEquals(10L, result.path("roomId").asLong());
        assertEquals("image", result.path("keyword").asText());
        assertEquals("image generation note", result.path("matches").path(0).path("content").asText());
    }

    @Test
    void getRoomMembersReturnsFullMemberList() {
        ChatRoomRepository repository = mock(ChatRoomRepository.class);
        when(repository.findMembersByRoomId(10L)).thenReturn(List.of(
                member(user(1L, "alice", "Alice"), ChatRoomMember.MemberRole.OWNER),
                member(user(2L, "bob", "Bob"), ChatRoomMember.MemberRole.MEMBER)));
        GetRoomMembersTool tool = new GetRoomMembersTool(repository, objectMapper);

        JsonNode result = tool.execute(objectMapper.createObjectNode().put("roomId", 999),
                new ToolContext(10L, 1L, 77L));

        assertEquals(2, result.path("members").size());
        assertEquals("Alice", result.path("members").path(0).path("displayName").asText());
        assertEquals("OWNER", result.path("members").path(0).path("role").asText());
    }

    @Test
    void webSearchParsesSearxngResults() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            byte[] body = "{\"results\":[{\"title\":\"A\",\"url\":\"https://a.test\",\"content\":\"Snippet A\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            WebSearchTool tool = new WebSearchTool(objectMapper, "http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode result = tool.execute(objectMapper.createObjectNode().put("query", "pm chat").put("max_results", 1),
                    new ToolContext(10L, 1L, 77L));

            assertEquals("pm chat", result.path("query").asText());
            assertEquals("A", result.path("results").path(0).path("title").asText());
            assertEquals("https://a.test", result.path("results").path(0).path("url").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void webSearchReturnsErrorNodeOnUnavailableService() {
        WebSearchTool tool = new WebSearchTool(objectMapper, "http://127.0.0.1:1");

        JsonNode result = tool.execute(objectMapper.createObjectNode().put("query", "pm chat"),
                new ToolContext(10L, 1L, 77L));

        assertTrue(result.has("error"));
    }

    private Message message(Long id, String displayName, String content) {
        Message message = new Message();
        message.setId(id);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());
        message.setSender(user(id, displayName.toLowerCase(), displayName));
        return message;
    }

    private ChatRoomMember member(User user, ChatRoomMember.MemberRole role) {
        ChatRoomMember member = new ChatRoomMember();
        member.setUser(user);
        member.setMemberRole(role);
        return member;
    }

    private User user(Long id, String username, String displayName) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setDisplayName(displayName);
        return user;
    }
}
