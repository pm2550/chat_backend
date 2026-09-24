package com.chatapp.integration;

import com.chatapp.entity.User;
import com.chatapp.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserLookupIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("Exact username lookup returns the public profile without private fields")
    void exactUsernameLookupReturnsPublicProfile() throws Exception {
        TestUser viewer = createUserAndLogin("lookupviewer");
        TestUser target = createUserAndLogin("alice123");

        mockMvc.perform(put("/api/v1/users/me/title")
                .header("Authorization", target.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "title", "群主",
                        "titleColor", "#FF8800",
                        "titleEffect", "glow"))))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/profile/settings")
                .header("Authorization", target.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("avatarFramePreset", "golden_ring"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/lookup")
                .header("Authorization", viewer.bearer())
                .param("username", "  " + target.username() + " "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(target.id().intValue()))
                .andExpect(jsonPath("$.user.username").value(target.username()))
                .andExpect(jsonPath("$.user.displayName").value(target.username()))
                .andExpect(jsonPath("$.user.title").value("群主"))
                .andExpect(jsonPath("$.user.titleColor").value("#FF8800"))
                .andExpect(jsonPath("$.user.titleEffect").value("glow"))
                .andExpect(jsonPath("$.user.avatarFramePreset").value("golden_ring"))
                .andExpect(jsonPath("$.user.onlineStatus").exists())
                .andExpect(jsonPath("$.user.email").doesNotExist())
                .andExpect(jsonPath("$.user.phone").doesNotExist())
                .andExpect(jsonPath("$.user.password").doesNotExist());
    }

    @Test
    @DisplayName("Partial username never matches")
    void partialUsernameIsNotFound() throws Exception {
        TestUser viewer = createUserAndLogin("partialviewer");
        TestUser target = createUserAndLogin("alice123");

        mockMvc.perform(get("/api/v1/users/lookup")
                .header("Authorization", viewer.bearer())
                .param("username", target.username().substring(0, 3)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("用户不存在"));
    }

    @Test
    @DisplayName("Lookup by id works and defaults the avatar frame to none")
    void lookupById() throws Exception {
        TestUser viewer = createUserAndLogin("idviewer");
        TestUser target = createUserAndLogin("idtarget");

        mockMvc.perform(get("/api/v1/users/lookup")
                .header("Authorization", viewer.bearer())
                .param("id", target.id().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(target.id().intValue()))
                .andExpect(jsonPath("$.user.username").value(target.username()))
                .andExpect(jsonPath("$.user.avatarFramePreset").value("none"))
                .andExpect(jsonPath("$.user.email").doesNotExist());

        mockMvc.perform(get("/api/v1/users/lookup")
                .header("Authorization", viewer.bearer())
                .param("id", "987654321"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("用户不存在"));
    }

    @Test
    @DisplayName("Inactive users are reported as not found")
    void inactiveUserIsNotFound() throws Exception {
        TestUser viewer = createUserAndLogin("inactiveviewer");
        TestUser target = createUserAndLogin("inactivetarget");
        User entity = userRepository.findById(target.id()).orElseThrow();
        entity.setIsActive(false);
        userRepository.save(entity);

        mockMvc.perform(get("/api/v1/users/lookup")
                .header("Authorization", viewer.bearer())
                .param("username", target.username()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/users/lookup")
                .header("Authorization", viewer.bearer())
                .param("id", target.id().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Missing, blank or malformed parameters are rejected with 400")
    void missingParametersAreBadRequest() throws Exception {
        TestUser viewer = createUserAndLogin("badparamviewer");

        mockMvc.perform(get("/api/v1/users/lookup")
                .header("Authorization", viewer.bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());
        mockMvc.perform(get("/api/v1/users/lookup")
                .header("Authorization", viewer.bearer())
                .param("username", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());
        mockMvc.perform(get("/api/v1/users/lookup")
                .header("Authorization", viewer.bearer())
                .param("id", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());
    }

    @Test
    @DisplayName("Lookup requires authentication")
    void lookupRequiresAuthentication() throws Exception {
        TestUser target = createUserAndLogin("anontarget");

        mockMvc.perform(get("/api/v1/users/lookup")
                .param("username", target.username()))
                .andExpect(status().isUnauthorized());
    }
}
