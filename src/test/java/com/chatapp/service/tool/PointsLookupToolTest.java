package com.chatapp.service.tool;

import com.chatapp.dto.PointsDto;
import com.chatapp.entity.FeatureCost;
import com.chatapp.repository.FeatureCostRepository;
import com.chatapp.service.PointsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PointsLookupToolTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private PointsService pointsService;
    private FeatureCostRepository featureCostRepository;

    @BeforeEach
    void setUp() {
        pointsService = mock(PointsService.class);
        featureCostRepository = mock(FeatureCostRepository.class);
    }

    @Test
    void balanceToolUsesInitiatingUserIdNotBotConfigId() {
        Map<String, Integer> free = new LinkedHashMap<>();
        free.put("ai_image_gen", 3);
        when(pointsService.getBalance(42L))
                .thenReturn(new PointsDto.BalanceResponse(free, 120));
        PointsBalanceTool tool = new PointsBalanceTool(pointsService, objectMapper);

        JsonNode result = tool.execute(
                objectMapper.createObjectNode(),
                new ToolContext(10L, 42L, 77L, 99L));

        assertEquals(42L, result.path("user_id").asLong());
        assertEquals(120, result.path("paid_points").asInt());
        assertEquals(3, result.path("free_remaining_per_feature").path("ai_image_gen").asInt());
        verify(pointsService).getBalance(42L);
        verify(pointsService, never()).getBalance(99L);
    }

    @Test
    void balanceToolRejectsMissingCallerUserId() {
        PointsBalanceTool tool = new PointsBalanceTool(pointsService, objectMapper);

        assertThrows(ToolExecutionException.class,
                () -> tool.execute(objectMapper.createObjectNode(), new ToolContext(10L, null, 77L, 99L)));
    }

    @Test
    void featuresToolListsEnabledFeaturesWithCallerFreeQuota() {
        Map<String, Integer> free = new LinkedHashMap<>();
        free.put("ai_image_gen", 2);
        free.put("bot_invoke", 50);
        when(pointsService.getBalance(42L))
                .thenReturn(new PointsDto.BalanceResponse(free, 75));
        when(featureCostRepository.findAll()).thenReturn(List.of(
                new FeatureCost("bot_invoke", 1, 50, true, "Bot invoke", null),
                new FeatureCost("disabled_feature", 9, 0, false, "Disabled", null),
                new FeatureCost("ai_image_gen", 10, 3, true, "AI image generation", null)
        ));
        PointsFeaturesTool tool = new PointsFeaturesTool(pointsService, featureCostRepository, objectMapper);

        JsonNode result = tool.execute(
                objectMapper.createObjectNode(),
                new ToolContext(10L, 42L, 77L, 99L));

        assertEquals(42L, result.path("user_id").asLong());
        assertEquals(75, result.path("paid_points").asInt());
        JsonNode features = result.path("features");
        assertEquals(2, features.size());
        assertEquals("ai_image_gen", features.get(0).path("feature_key").asText());
        assertEquals(10, features.get(0).path("cost_points").asInt());
        assertEquals(2, features.get(0).path("free_remaining").asInt());
        assertEquals("bot_invoke", features.get(1).path("feature_key").asText());
        assertEquals(50, features.get(1).path("free_remaining").asInt());
        verify(pointsService).getBalance(42L);
        verify(pointsService, never()).getBalance(99L);
    }
}
