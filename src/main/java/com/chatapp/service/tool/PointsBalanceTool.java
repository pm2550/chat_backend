package com.chatapp.service.tool;

import com.chatapp.dto.PointsDto;
import com.chatapp.service.PointsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PointsBalanceTool implements Tool {
    private final PointsService pointsService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "lookup_my_points_balance";
    }

    @Override
    public String description() {
        return "Look up the initiating PM chat user's current paid points and free daily quota remaining by feature.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params, ToolContext context) {
        Long userId = requireCallerUserId(context);
        PointsDto.BalanceResponse balance = pointsService.getBalance(userId);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("user_id", userId);
        root.put("paid_points", balance.getPaidPoints());
        root.set("free_remaining_per_feature",
                objectMapper.valueToTree(balance.getFreeRemainingPerFeature()));
        return root;
    }

    private Long requireCallerUserId(ToolContext context) {
        if (context == null || context.userId() == null) {
            throw new ToolExecutionException("missing_caller_user", "Points lookup requires an initiating user.");
        }
        return context.userId();
    }
}
