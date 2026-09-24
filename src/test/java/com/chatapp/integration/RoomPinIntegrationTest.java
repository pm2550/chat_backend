package com.chatapp.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RoomPinIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("Admins pin and unpin, every member can read the pin list")
    void pinListRoundTrip() throws Exception {
        TestUser owner = createUserAndLogin("pinowner");
        TestUser member = createUserAndLogin("pinmember");
        Long roomId = createGroupChat(owner, "Pin Room " + uniqueSuffix, List.of(member.id()));
        Long first = sendMessage(member, roomId, "first pin");
        Long second = sendMessage(owner, roomId, "@" + member.username() + " second pin");

        mockMvc.perform(post("/api/v1/rooms/" + roomId + "/pin/" + first)
                .header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(first.intValue()));
        mockMvc.perform(post("/api/v1/rooms/" + roomId + "/pin/" + second)
                .header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));

        // Newest pin first; readable by a plain member.
        mockMvc.perform(get("/api/v1/rooms/" + roomId + "/pins")
                .header("Authorization", member.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].id").value(second.intValue()))
                .andExpect(jsonPath("$.data[0].mentionedUserIds[0]").value(member.id().intValue()))
                .andExpect(jsonPath("$.data[1].id").value(first.intValue()));

        mockMvc.perform(delete("/api/v1/rooms/" + roomId + "/pin/" + first)
                .header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(second.intValue()));
    }

    @Test
    @DisplayName("Plain group members cannot pin or unpin")
    void membersCannotManagePins() throws Exception {
        TestUser owner = createUserAndLogin("pinowner2");
        TestUser member = createUserAndLogin("pinmember2");
        Long roomId = createGroupChat(owner, "Pin Room 2 " + uniqueSuffix, List.of(member.id()));
        Long message = sendMessage(member, roomId, "not yours to pin");

        mockMvc.perform(post("/api/v1/rooms/" + roomId + "/pin/" + message)
                .header("Authorization", member.bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("需要群管理员权限"));

        mockMvc.perform(post("/api/v1/rooms/" + roomId + "/pin/" + message)
                .header("Authorization", owner.bearer()))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/rooms/" + roomId + "/pin/" + message)
                .header("Authorization", member.bearer()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/rooms/" + roomId + "/pins")
                .header("Authorization", member.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));
    }
}
