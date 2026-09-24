package com.chatapp.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 隐私回归网：把一批代表性接口的 JSON 原文拿来扫，
 * 别人的邮箱/手机号、匿名消息背后的真实身份一旦出现在"非本人"的回包里就失败。
 * 以后新增接口时把它加进下面的扫描清单即可。
 */
class PrivacyExposureIntegrationTest extends IntegrationTestSupport {

    private record Person(TestUser user, String email, String phone) {
    }

    @Test
    @DisplayName("A stranger who sends a friend request never sees the target's email or phone")
    void strangerFriendRequestDoesNotRevealContactInfo() throws Exception {
        Person target = person("target");
        Person stranger = person("stranger");

        mockMvc.perform(post("/api/v1/friends/request/" + target.user().id())
                        .header("Authorization", stranger.user().bearer()))
                .andExpect(status().isOk());

        assertNoContactInfo(target, stranger, List.of(
                "/api/v1/friends/requests/sent",
                "/api/v1/friends/stats",
                "/api/profile/search?keyword=" + target.user().username(),
                "/api/v1/users/lookup?username=" + target.user().username()));
        // 反过来：收到请求的人也看不到陌生人的联系方式。
        assertNoContactInfo(stranger, target, List.of("/api/v1/friends/requests/received"));
    }

    @Test
    @DisplayName("Friends and room members never see each other's email or phone; each user still sees their own")
    void membersAndFriendsDoNotSeeEachOthersContactInfo() throws Exception {
        Person alice = person("alice");
        Person bob = person("bob");
        mockMvc.perform(post("/api/v1/friends/request/" + alice.user().id())
                        .header("Authorization", bob.user().bearer()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/friends/accept/" + bob.user().id())
                        .header("Authorization", alice.user().bearer()))
                .andExpect(status().isOk());

        Long roomId = createGroupChat(alice.user(), "privacy-room-" + uniqueSuffix, List.of(bob.user().id()));
        Long messageId = sendMessage(alice.user(), roomId, "plain hello from alice");
        mockMvc.perform(post("/api/v1/rooms/" + roomId + "/pin/" + messageId)
                        .header("Authorization", alice.user().bearer()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/messages/" + messageId + "/star")
                        .header("Authorization", bob.user().bearer()))
                .andExpect(status().isOk());
        Long privateRoomId = createPrivateChat(bob.user(), alice.user().id());

        assertNoContactInfo(alice, bob, List.of(
                "/api/v1/chat-rooms",
                "/api/v1/chat-rooms/summaries",
                "/api/v1/chat-rooms/" + roomId,
                "/api/v1/chat-rooms/" + roomId + "/members",
                "/api/v1/chat-rooms/" + privateRoomId + "/members",
                "/api/v1/messages/chat-room/" + roomId,
                "/api/v1/messages/chat-room/" + roomId + "/recent",
                "/api/v1/messages/search?chatRoomId=" + roomId + "&keyword=hello",
                "/api/v1/chat-rooms/" + roomId + "/messages/search?q=hello",
                "/api/v1/chat-rooms/messages/search?q=hello",
                "/api/v1/messages/stats/" + roomId,
                "/api/v1/messages/" + messageId + "/read-by",
                "/api/v1/rooms/" + roomId + "/pins",
                "/api/v1/users/me/starred",
                "/api/v1/messages/starred",
                "/api/v1/friends",
                "/api/v1/friends/search?keyword=" + alice.user().username(),
                "/api/profile/search?keyword=" + alice.user().username(),
                "/api/v1/users/lookup?id=" + alice.user().id()));

        // 本人自己的资料仍然带邮箱和手机号。
        String validate = getAs(bob.user(), "/api/auth/validate");
        assertTrue(validate.contains(bob.email()), "self validate must keep own email");
        String profile = getAs(bob.user(), "/api/profile");
        assertTrue(profile.contains(bob.email()) && profile.contains(bob.phone()),
                "own profile must keep own email and phone");
    }

    @Test
    @DisplayName("User search matches an email only in full, so emails cannot be probed piece by piece")
    void userSearchDoesNotMatchPartialEmail() throws Exception {
        Person alice = person("probe");
        Person viewer = person("viewer");
        String localPart = alice.email().substring(0, alice.email().indexOf('@'));
        String domainPart = alice.email().substring(alice.email().indexOf('@'));

        assertFalse(getAs(viewer.user(), "/api/profile/search?keyword=" + domainPart)
                .contains(alice.user().username()), "partial email (domain) must not match");
        assertTrue(getAs(viewer.user(), "/api/profile/search?keyword=" + alice.email())
                .contains(alice.user().username()), "a full email still finds the user");
        assertTrue(getAs(viewer.user(), "/api/profile/search?keyword=" + localPart)
                .contains(alice.user().username()), "username match is unaffected");
    }

    @Test
    @DisplayName("Anonymous messages carry no real identity to anyone but the sender, even the room owner")
    void anonymousMessagesHideTheRealSender() throws Exception {
        Person owner = person("owner");
        Person alice = person("anonsender");
        Person bob = person("reader");
        Long roomId = createGroupChat(owner.user(), "anon-room-" + uniqueSuffix,
                List.of(alice.user().id(), bob.user().id()));
        mockMvc.perform(put("/api/v1/chat-rooms/" + roomId + "/anonymous/toggle")
                        .param("enable", "true")
                        .header("Authorization", owner.user().bearer()))
                .andExpect(status().isOk());

        // 发送者自己的回包：认得出是自己发的。
        Map<String, Object> sent = sendAnonymous(alice.user(), roomId, "secret take", null);
        Long anonymousId = ((Number) sent.get("id")).longValue();
        assertEquals(Boolean.TRUE, sent.get("sentByMe"));
        String anonymousName = (String) sent.get("anonymousName");
        assertFalse(anonymousName == null || anonymousName.isBlank());

        // 别人引用这条匿名消息。
        Map<String, Object> reply = sendReply(bob.user(), roomId, "who said that?", anonymousId);
        Map<String, Object> quoted = (Map<String, Object>) reply.get("replyToMessage");
        assertNull(quoted.get("senderId"));
        assertNull(quoted.get("sender"));
        assertEquals(anonymousName, quoted.get("senderName"));

        mockMvc.perform(post("/api/v1/rooms/" + roomId + "/pin/" + anonymousId)
                        .header("Authorization", owner.user().bearer()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/messages/" + anonymousId + "/star")
                        .header("Authorization", bob.user().bearer()))
                .andExpect(status().isOk());

        List<String> roomViews = List.of(
                "/api/v1/chat-rooms/summaries",
                "/api/v1/messages/chat-room/" + roomId,
                "/api/v1/messages/chat-room/" + roomId + "/recent",
                "/api/v1/messages/search?chatRoomId=" + roomId + "&keyword=secret",
                "/api/v1/chat-rooms/" + roomId + "/messages/search?q=secret",
                "/api/v1/chat-rooms/messages/search?q=secret",
                "/api/v1/messages/stats/" + roomId,
                "/api/v1/messages/" + anonymousId + "/read-by",
                "/api/v1/rooms/" + roomId + "/pins");
        for (TestUser viewer : List.of(bob.user(), owner.user())) {
            for (String url : roomViews) {
                assertNoIdentityOf(alice, getAs(viewer, url), viewer.username() + " " + url);
            }
        }
        assertNoIdentityOf(alice, getAs(bob.user(), "/api/v1/users/me/starred"), "starred");
        assertNoIdentityOf(alice, getAs(bob.user(), "/api/v1/messages/starred"), "starred (legacy)");

        Map<String, Object> bobsCopy = findMessage(bob.user(), roomId, anonymousId);
        assertNull(bobsCopy.get("senderId"));
        assertNull(bobsCopy.get("sender"));
        assertEquals(Boolean.FALSE, bobsCopy.get("sentByMe"));
        assertEquals(anonymousName, bobsCopy.get("anonymousName"));
        assertEquals(anonymousName, bobsCopy.get("senderName"));

        // 发送者本人看历史：sentByMe=true，引用里也认得出自己的那条。
        Map<String, Object> alicesCopy = findMessage(alice.user(), roomId, anonymousId);
        assertEquals(Boolean.TRUE, alicesCopy.get("sentByMe"));
        assertEquals(alice.user().id().intValue(), ((Number) alicesCopy.get("senderId")).intValue());
        Map<String, Object> alicesViewOfReply = findMessage(alice.user(), roomId, ((Number) reply.get("id")).longValue());
        assertEquals(Boolean.FALSE, alicesViewOfReply.get("sentByMe"));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) alicesViewOfReply.get("replyToMessage")).get("sentByMe"));

        // 只有发送者能改/撤回自己的匿名消息（服务器仍按真实发送者鉴权）。
        mockMvc.perform(post("/api/v1/messages/" + anonymousId + "/recall")
                        .header("Authorization", bob.user().bearer()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/messages/" + anonymousId + "/recall")
                        .header("Authorization", alice.user().bearer()))
                .andExpect(status().isOk());
    }

    private Person person(String prefix) throws Exception {
        TestUser user = createUserAndLogin(prefix);
        String phone = "139" + String.format("%08d", Math.abs((prefix + uniqueSuffix).hashCode()) % 100_000_000);
        mockMvc.perform(put("/api/profile")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", phone))))
                .andExpect(status().isOk());
        return new Person(user, user.username() + "@test.com", phone);
    }

    private void assertNoContactInfo(Person subject, Person viewer, List<String> urls) throws Exception {
        List<String> leaks = new ArrayList<>();
        for (String url : urls) {
            String json = getAs(viewer.user(), url);
            if (json.contains(subject.email()) || json.contains(subject.phone())) {
                leaks.add(url + " -> " + json);
            }
        }
        assertTrue(leaks.isEmpty(), "email/phone leaked to a non-self viewer: " + leaks);
    }

    private void assertNoIdentityOf(Person sender, String json, String where) {
        TestUser user = sender.user();
        // createUserAndLogin 的显示名就是用户名，所以扫用户名同时覆盖了显示名。
        assertFalse(json.contains(user.username()), where + " leaks username/displayName: " + json);
        assertFalse(json.contains(sender.email()), where + " leaks email: " + json);
        assertFalse(json.contains("\"senderId\":" + user.id() + ","), where + " leaks senderId: " + json);
        assertFalse(json.contains("\"senderId\":" + user.id() + "}"), where + " leaks senderId: " + json);
    }

    private String getAs(TestUser viewer, String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url).header("Authorization", viewer.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private Long createPrivateChat(TestUser from, Long friendId) throws Exception {
        Map<String, Object> body = body(mockMvc.perform(post("/api/v1/chat-rooms/private/" + friendId)
                        .header("Authorization", from.bearer()))
                .andExpect(status().isOk())
                .andReturn());
        return ((Number) ((Map<String, Object>) body.get("chatRoom")).get("id")).longValue();
    }

    private Map<String, Object> sendAnonymous(TestUser sender, Long roomId, String content, Long replyTo)
            throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("chatRoomId", roomId);
        request.put("content", content);
        request.put("isAnonymous", true);
        if (replyTo != null) {
            request.put("replyToMessageId", replyTo);
        }
        return postMessage(sender, request);
    }

    private Map<String, Object> sendReply(TestUser sender, Long roomId, String content, Long replyTo)
            throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("chatRoomId", roomId);
        request.put("content", content);
        request.put("replyToMessageId", replyTo);
        return postMessage(sender, request);
    }

    private Map<String, Object> postMessage(TestUser sender, Map<String, Object> request) throws Exception {
        MockHttpServletRequestBuilder builder = post("/api/v1/messages")
                .header("Authorization", sender.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request));
        return data(mockMvc.perform(builder).andExpect(status().isOk()).andReturn());
    }

    private Map<String, Object> findMessage(TestUser viewer, Long roomId, Long messageId) throws Exception {
        Map<String, Object> body = objectMapper.readValue(
                getAs(viewer, "/api/v1/messages/chat-room/" + roomId), Map.class);
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        return messages.stream()
                .filter(message -> ((Number) message.get("id")).longValue() == messageId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("message " + messageId + " not in history"));
    }
}
