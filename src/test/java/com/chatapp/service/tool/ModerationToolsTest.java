package com.chatapp.service.tool;

import com.chatapp.service.ModerationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ModerationToolsTest {

    private final ObjectMapper om = new ObjectMapper();
    @Mock private ModerationService moderationService;

    private ToolContext ctx(Long roomId, Long botId) {
        return new ToolContext(roomId, 1L, null, botId);
    }

    @Test
    void muteMemberDelegatesToService() {
        MuteMemberTool tool = new MuteMemberTool(om, moderationService);
        ObjectNode p = om.createObjectNode();
        p.put("user_id", 9);
        p.put("mute", true);

        JsonNode out = tool.execute(p, ctx(100L, 5L));

        assertTrue(out.path("muted").asBoolean());
        assertEquals(9L, out.path("user_id").asLong());
        verify(moderationService).muteByBot(5L, 100L, 9L, true);
    }

    @Test
    void muteMemberWithoutBotContextErrors() {
        MuteMemberTool tool = new MuteMemberTool(om, moderationService);
        ObjectNode p = om.createObjectNode();
        p.put("user_id", 9);
        JsonNode out = tool.execute(p, ctx(100L, null));
        assertEquals("no_bot", out.path("error").path("code").asText());
        verifyNoInteractions(moderationService);
    }

    @Test
    void muteMemberMapsAccessDeniedToForbidden() {
        MuteMemberTool tool = new MuteMemberTool(om, moderationService);
        doThrow(new AccessDeniedException("机器人没有该管理权限"))
                .when(moderationService).muteByBot(anyLong(), anyLong(), anyLong(), anyBoolean());
        ObjectNode p = om.createObjectNode();
        p.put("user_id", 9);
        JsonNode out = tool.execute(p, ctx(100L, 5L));
        assertEquals("forbidden", out.path("error").path("code").asText());
    }

    @Test
    void muteMemberMissingUserIdThrowsInvalidParams() {
        MuteMemberTool tool = new MuteMemberTool(om, moderationService);
        ObjectNode p = om.createObjectNode(); // no user_id
        assertThrows(ToolExecutionException.class, () -> tool.execute(p, ctx(100L, 5L)));
    }

    @Test
    void kickMemberDelegatesToService() {
        KickMemberTool tool = new KickMemberTool(om, moderationService);
        ObjectNode p = om.createObjectNode();
        p.put("user_id", 9);

        JsonNode out = tool.execute(p, ctx(100L, 5L));

        assertTrue(out.path("kicked").asBoolean());
        assertEquals(9L, out.path("user_id").asLong());
        verify(moderationService).kickByBot(5L, 100L, 9L);
    }

    @Test
    void kickMemberWithoutRoomErrors() {
        KickMemberTool tool = new KickMemberTool(om, moderationService);
        ObjectNode p = om.createObjectNode();
        p.put("user_id", 9);
        JsonNode out = tool.execute(p, ctx(null, 5L));
        assertEquals("no_room", out.path("error").path("code").asText());
        verifyNoInteractions(moderationService);
    }
}
