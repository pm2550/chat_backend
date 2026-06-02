package com.chatapp.service.tool;

import com.chatapp.entity.BotConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
public class AgentToolRegistry {
    private final Map<String, Tool> toolsByName;
    private final ObjectMapper objectMapper;

    public AgentToolRegistry(List<Tool> tools, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        Map<String, Tool> registered = new LinkedHashMap<>();
        for (Tool tool : tools) {
            Tool previous = registered.put(tool.name(), tool);
            if (previous != null) {
                throw new IllegalStateException("Duplicate agent tool name: " + tool.name());
            }
        }
        this.toolsByName = Map.copyOf(registered);
    }

    public Optional<Tool> getTool(String name) {
        return Optional.ofNullable(toolsByName.get(name));
    }

    /**
     * True only when the bot has an EXPLICIT, non-empty enabled_tools whitelist that
     * resolves to at least one registered tool. A null/blank whitelist returns false
     * (even though {@link #listToolsForBot} would then expose all tools) so that a
     * plain persona bot with no tool opt-in is NOT routed through the agent loop.
     */
    public boolean hasExplicitToolWhitelist(BotConfig bot) {
        Set<String> whitelist = enabledTools(bot);
        if (whitelist == null || whitelist.isEmpty()) {
            return false;
        }
        return whitelist.stream().anyMatch(toolsByName::containsKey);
    }

    public List<Tool> listToolsForBot(BotConfig bot) {
        Set<String> whitelist = enabledTools(bot);
        if (whitelist == null) {
            return new ArrayList<>(toolsByName.values());
        }
        return toolsByName.values().stream()
                .filter(tool -> whitelist.contains(tool.name()))
                .toList();
    }

    private Set<String> enabledTools(BotConfig bot) {
        if (bot == null || bot.getEnabledTools() == null || bot.getEnabledTools().isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(bot.getEnabledTools());
            if (!root.isArray()) {
                log.warn("Ignoring bot {} enabled_tools because it is not a JSON array", bot.getId());
                return null;
            }
            Set<String> names = new LinkedHashSet<>();
            root.forEach(node -> {
                if (node.isTextual() && !node.asText().isBlank()) {
                    names.add(node.asText());
                }
            });
            return names;
        } catch (JsonProcessingException e) {
            log.warn("Ignoring bot {} enabled_tools because JSON parsing failed: {}", bot.getId(), e.getMessage());
            return null;
        }
    }
}
