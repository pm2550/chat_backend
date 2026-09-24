package com.chatapp.integration;

import com.chatapp.entity.User;
import com.chatapp.repository.UserRepository;
import com.chatapp.websocket.RawWebSocketHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 别人看到的在线状态只取决于有没有前台实时连接：登录不算在线，最后一个连接断开就离线。
 */
@DisplayName("Presence follows foreground connections")
class PresenceIntegrationTest extends IntegrationTestSupport {

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
    @DisplayName("Logging in without opening a socket leaves the user offline to others")
    void loginAloneIsNotOnline() throws Exception {
        TestUser viewer = createUserAndLogin("pviewer");
        TestUser target = createUserAndLogin("ptarget");

        assertEquals("OFFLINE", statusSeenBy(viewer, target));
        assertEquals(User.OnlineStatus.OFFLINE, reload(target).getOnlineStatus());
    }

    @Test
    @DisplayName("Connecting shows the chosen status; the last disconnect goes offline and stamps last_seen")
    void connectAndDisconnect() throws Exception {
        TestUser viewer = createUserAndLogin("pviewer");
        TestUser target = createUserAndLogin("ptarget");

        var phone = connect(target, false);
        assertEquals("ONLINE", statusSeenBy(viewer, target));

        close(phone);
        assertEquals("OFFLINE", statusSeenBy(viewer, target));
        LocalDateTime lastSeen = reload(target).getLastSeen();
        assertNotNull(lastSeen);
        assertTrue(lastSeen.isAfter(LocalDateTime.now().minusMinutes(1)), "last_seen should be the disconnect time");
    }

    @Test
    @DisplayName("Picking 忙碌 while offline stays offline until a socket connects, then shows 忙碌")
    void chosenStatusAppliesOnConnect() throws Exception {
        TestUser viewer = createUserAndLogin("pviewer");
        TestUser target = createUserAndLogin("ptarget");

        mockMvc.perform(put("/api/profile/status")
                        .header("Authorization", target.bearer())
                        .param("status", "BUSY"))
                .andExpect(status().isOk());
        assertEquals("OFFLINE", statusSeenBy(viewer, target));

        connect(target, false);
        assertEquals("BUSY", statusSeenBy(viewer, target));

        mockMvc.perform(put("/api/profile/status")
                        .header("Authorization", target.bearer())
                        .param("status", "AWAY"))
                .andExpect(status().isOk());
        assertEquals("AWAY", statusSeenBy(viewer, target));
    }

    @Test
    @DisplayName("A second device keeps the user online when the first one closes")
    void secondDeviceKeepsOnline() throws Exception {
        TestUser viewer = createUserAndLogin("pviewer");
        TestUser target = createUserAndLogin("ptarget");

        var phone = connect(target, false);
        var desktop = connect(target, false);
        close(phone);
        assertEquals("ONLINE", statusSeenBy(viewer, target));

        close(desktop);
        assertEquals("OFFLINE", statusSeenBy(viewer, target));
    }

    @Test
    @DisplayName("An Android background connection does not count as online")
    void backgroundConnectionIsNotOnline() throws Exception {
        TestUser viewer = createUserAndLogin("pviewer");
        TestUser target = createUserAndLogin("ptarget");

        connect(target, true);

        assertEquals("OFFLINE", statusSeenBy(viewer, target));
    }

    @Test
    @DisplayName("A connection dropped by the heartbeat sweeper takes the user offline")
    void staleSweepGoesOffline() throws Exception {
        TestUser viewer = createUserAndLogin("pviewer");
        TestUser target = createUserAndLogin("ptarget");
        var phone = connect(target, false);
        assertEquals("ONLINE", statusSeenBy(viewer, target));

        phone.getAttributes().put("pmchat.lastInboundAt", System.currentTimeMillis() - 10 * 60_000L);
        rawWebSocketHandler.closeStaleSessions();

        assertEquals("OFFLINE", statusSeenBy(viewer, target));
    }

    @Test
    @DisplayName("Startup clears stale ONLINE rows but keeps users that are really connected")
    void startupResetsStaleOnline() throws Exception {
        TestUser viewer = createUserAndLogin("pviewer");
        TestUser stale = createUserAndLogin("pstale");
        TestUser live = createUserAndLogin("plive");
        User staleUser = reload(stale);
        staleUser.setOnlineStatus(User.OnlineStatus.ONLINE);
        userRepository.save(staleUser);
        connect(live, false);

        rawWebSocketHandler.resetPresenceOnStartup();

        assertEquals("OFFLINE", statusSeenBy(viewer, stale));
        assertEquals("ONLINE", statusSeenBy(viewer, live));
    }

    @Test
    @DisplayName("The user's own profile shows the status they picked even before the socket connects")
    void ownProfileShowsChosenStatus() throws Exception {
        TestUser target = createUserAndLogin("ptarget");
        mockMvc.perform(put("/api/profile/status")
                        .header("Authorization", target.bearer())
                        .param("status", "AWAY"))
                .andExpect(status().isOk());

        Map<String, Object> own = data(mockMvc.perform(get("/api/profile")
                        .header("Authorization", target.bearer()))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals("AWAY", own.get("onlineStatus"));
        assertEquals(User.OnlineStatus.OFFLINE, reload(target).getOnlineStatus());
    }

    private String statusSeenBy(TestUser viewer, TestUser target) throws Exception {
        Map<String, Object> body = body(mockMvc.perform(get("/api/v1/users/lookup")
                        .header("Authorization", viewer.bearer())
                        .param("username", target.username()))
                .andExpect(status().isOk())
                .andReturn());
        return String.valueOf(((Map<String, Object>) body.get("user")).get("onlineStatus"));
    }

    private User reload(TestUser user) {
        return userRepository.findById(user.id()).orElseThrow();
    }

    private RoomRealtimeSyncIntegrationTest.RecordingSession connect(TestUser user, boolean background) {
        var session = new RoomRealtimeSyncIntegrationTest.RecordingSession();
        session.getAttributes().put(RawWebSocketHandler.ATTR_USER, reload(user));
        if (background) {
            session.getAttributes().put(RawWebSocketHandler.ATTR_BACKGROUND, Boolean.TRUE);
        }
        rawWebSocketHandler.afterConnectionEstablished(session);
        openSessions.add(session);
        return session;
    }

    private void close(RoomRealtimeSyncIntegrationTest.RecordingSession session) {
        rawWebSocketHandler.afterConnectionClosed(session, CloseStatus.NORMAL);
        openSessions.remove(session);
    }
}
