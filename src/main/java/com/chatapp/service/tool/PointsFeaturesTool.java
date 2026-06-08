package com.chatapp.service.tool;

import com.chatapp.dto.PointsDto;
import com.chatapp.entity.FeatureCost;
import com.chatapp.repository.FeatureCostRepository;
import com.chatapp.service.PointsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class PointsFeaturesTool implements Tool {
    private final PointsService pointsService;
    private final FeatureCostRepository featureCostRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "lookup_points_features";
    }

    @Override
    public String description() {
        return "List enabled PM chat point-consuming features, their point cost, and the initiating user's remaining free quota.";
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
        Map<String, Integer> freeRemaining = balance.getFreeRemainingPerFeature() != null
                ? balance.getFreeRemainingPerFeature()
                : Map.of();

        ObjectNode root = objectMapper.createObjectNode();
        root.put("user_id", userId);
        root.put("paid_points", balance.getPaidPoints());
        ArrayNode features = root.putArray("features");
        featureCostRepository.findAll().stream()
                .filter(feature -> Boolean.TRUE.equals(feature.getEnabled()))
                .sorted(Comparator.comparing(FeatureCost::getFeatureKey))
                .forEach(feature -> {
                    ObjectNode node = features.addObject();
                    node.put("feature_key", feature.getFeatureKey());
                    node.put("cost_points", nonNegative(feature.getCostPoints()));
                    node.put("free_daily_quota", nonNegative(feature.getFreeDailyQuota()));
                    node.put("free_remaining",
                            freeRemaining.getOrDefault(feature.getFeatureKey(), 0));
                    node.put("description", feature.getDescription() != null ? feature.getDescription() : "");
                });
        return root;
    }

    private Long requireCallerUserId(ToolContext context) {
        if (context == null || context.userId() == null) {
            throw new ToolExecutionException("missing_caller_user", "Points lookup requires an initiating user.");
        }
        return context.userId();
    }

    private int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
