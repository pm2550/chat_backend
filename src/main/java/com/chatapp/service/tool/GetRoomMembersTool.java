package com.chatapp.service.tool;

import com.chatapp.entity.ChatRoomMember;
import com.chatapp.repository.ChatRoomRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GetRoomMembersTool implements Tool {
    private final ChatRoomRepository chatRoomRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "get_room_members";
    }

    @Override
    public String description() {
        return "Return the full member list for the current room.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("roomId").put("type", "integer").put("description", "Ignored; server forces current room.");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params, ToolContext context) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("roomId", context.roomId());
        ArrayNode members = root.putArray("members");
        for (ChatRoomMember member : chatRoomRepository.findMembersByRoomId(context.roomId())) {
            ObjectNode node = members.addObject();
            node.put("userId", member.getUser().getId());
            node.put("displayName", displayName(member));
            node.put("username", member.getUser().getUsername());
            node.put("role", member.getMemberRole() != null ? member.getMemberRole().name() : "MEMBER");
            node.put("roleLabel", member.getMemberRole() != null ? member.getMemberRole().getDescription() : "普通成员");
            node.put("nickname", member.getNickname() != null ? member.getNickname() : "");
            node.put("title", member.getMemberTitle() != null ? member.getMemberTitle() : "");
        }
        return root;
    }

    private String displayName(ChatRoomMember member) {
        if (member.getNickname() != null && !member.getNickname().isBlank()) {
            return member.getNickname();
        }
        if (member.getUser().getDisplayName() != null && !member.getUser().getDisplayName().isBlank()) {
            return member.getUser().getDisplayName();
        }
        return member.getUser().getUsername();
    }
}
