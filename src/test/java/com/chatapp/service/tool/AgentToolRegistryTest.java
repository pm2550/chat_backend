package com.chatapp.service.tool;

import com.chatapp.entity.BotConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolRegistryTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registersAndLooksUpToolByName() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool("alpha"), tool("beta")), objectMapper);

        assertTrue(registry.getTool("alpha").isPresent());
        assertTrue(registry.getTool("missing").isEmpty());
    }

    @Test
    void filtersToolsByBotWhitelist() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool("alpha"), tool("beta")), objectMapper);
        BotConfig bot = new BotConfig();
        bot.setEnabledTools("[\"beta\"]");

        List<Tool> tools = registry.listToolsForBot(bot);

        assertEquals(1, tools.size());
        assertEquals("beta", tools.get(0).name());
    }

    @Test
    void nullWhitelistAllowsAllTools() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool("alpha"), tool("beta")), objectMapper);
        BotConfig bot = new BotConfig();

        assertEquals(2, registry.listToolsForBot(bot).size());
    }

    @Test
    void imageInspectionRespectsBothVisionSwitches() {
        AgentToolRegistry registry = new AgentToolRegistry(
                List.of(tool("inspect_room_image"), tool("web_search")), objectMapper);
        BotConfig bot = new BotConfig();
        bot.setEnabledTools("[\"inspect_room_image\",\"web_search\"]");
        bot.setVisionInputEnabled(false);

        assertEquals(List.of("web_search"), registry.listToolsForBot(bot).stream().map(Tool::name).toList());

        bot.setVisionInputEnabled(true);
        bot.setHistoryImageInspectionEnabled(false);
        assertEquals(List.of("web_search"), registry.listToolsForBot(bot).stream().map(Tool::name).toList());

        bot.setHistoryImageInspectionEnabled(true);
        assertEquals(Set.of("inspect_room_image", "web_search"),
                registry.listToolsForBot(bot).stream().map(Tool::name).collect(java.util.stream.Collectors.toSet()));
    }

    private Tool tool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name + " description";
            }

            @Override
            public JsonNode parametersSchema() {
                return objectMapper.createObjectNode().put("type", "object");
            }

            @Override
            public JsonNode execute(JsonNode params, ToolContext context) {
                return objectMapper.createObjectNode().put("ok", true);
            }
        };
    }
}
