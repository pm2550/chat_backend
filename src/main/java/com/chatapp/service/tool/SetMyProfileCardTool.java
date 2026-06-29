package com.chatapp.service.tool;

import com.chatapp.dto.UserDto;
import com.chatapp.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SetMyProfileCardTool implements Tool {
    private final UserService userService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "set_my_profile_card";
    }

    @Override
    public String description() {
        return "Set the initiating user's visible PM chat profile card/title badge. "
                + "Use this when the user asks you to generate, install, equip, or apply a personal name card, "
                + "title card, badge, or nickname title such as 高大师 or 陆大师. "
                + "This writes the title to the user's profile so it actually appears in PM chat.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("title")
                .put("type", "string")
                .put("maxLength", 50)
                .put("description", "The visible title/name-card text to equip, for example 高大师 or 陆大师.");
        properties.putObject("titleColor")
                .put("type", "string")
                .put("description", "Optional #RRGGBB title color. Defaults to PM anonymous purple if omitted.");
        properties.putObject("titleEffect")
                .put("type", "string")
                .put("description", "Optional effect: none, gradient, glow, rainbow, or animated_pulse. Defaults to glow.");
        schema.putArray("required").add("title");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params, ToolContext context) {
        if (context == null || context.userId() == null) {
            throw new ToolExecutionException("missing_caller_user", "Profile card updates require an initiating user.");
        }
        String title = params.path("title").asText("").trim();
        if (title.isBlank()) {
            throw new ToolExecutionException("invalid_params", "title is required");
        }
        if (title.length() > 50) {
            throw new ToolExecutionException("invalid_params", "title must be 50 characters or fewer");
        }

        UserDto.TitleRequest request = new UserDto.TitleRequest();
        request.setTitle(title);
        request.setTitleColor(optionalText(params, "titleColor", "#7C3AED"));
        request.setTitleEffect(optionalText(params, "titleEffect", "glow"));
        UserDto user = userService.updateTitle(context.userId(), request);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("updated", true);
        root.put("userId", context.userId());
        root.put("title", user.getTitle() != null ? user.getTitle() : "");
        root.put("titleColor", user.getTitleColor() != null ? user.getTitleColor() : "");
        root.put("titleEffect", user.getTitleEffect() != null ? user.getTitleEffect() : "none");
        root.put("note", "The title card has been saved to the initiating user's PM chat profile.");
        return root;
    }

    private String optionalText(JsonNode params, String field, String fallback) {
        String value = params.path(field).asText("").trim();
        return value.isBlank() ? fallback : value;
    }
}
