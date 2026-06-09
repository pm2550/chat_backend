package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.AgentTask;
import com.chatapp.entity.BotConfig;
import com.chatapp.service.tool.AgentToolDispatcher;
import com.chatapp.service.tool.AgentToolRegistry;
import com.chatapp.service.tool.Tool;
import com.chatapp.service.tool.ToolContext;
import com.chatapp.service.tool.ToolExecutionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentExecutionLoop {
    private final LLMService llmService;
    private final AgentToolRegistry toolRegistry;
    private final AgentToolDispatcher toolDispatcher;
    private final AgentContextBuilder agentContextBuilder;
    private final ObjectMapper objectMapper;

    public AgentLoopResult runLoop(AgentTask task, AgentContextBuilder.AgentContextEnvelope envelope) {
        BotConfig bot = task.getBotConfig();
        List<Tool> availableTools = toolRegistry.listToolsForBot(bot);
        List<BotDto.ChatMessage> messages = new ArrayList<>();
        messages.add(new BotDto.ChatMessage("system", agentContextBuilder.assembleSystemPrompt(envelope)));
        messages.add(BotDto.ChatMessage.userWithImages(task.getPrompt(), task.getImageAttachments()));

        ToolContext toolContext = new ToolContext(
                task.getChatRoom().getId(),
                task.getRequestedBy().getId(),
                task.getId(),
                bot != null ? bot.getId() : null);
        List<ToolCallRecord> toolCalls = new ArrayList<>();
        Budget budget = Budget.from(bot);
        Instant startedAt = Instant.now();
        int cumulativeTokens = estimateMessages(messages);
        String lastAssistantContent = "";

        for (int iteration = 1; iteration <= budget.maxIterations(); iteration++) {
            BudgetHit beforeCallHit = budget.check(iteration, startedAt, cumulativeTokens);
            if (beforeCallHit != null) {
                return exhausted(lastAssistantContent, iteration - 1, toolCalls, cumulativeTokens, beforeCallHit, startedAt);
            }

            log.info("Agent loop iteration={} taskId={} tools={} cumulativeTokens={}",
                    iteration, task.getId(), availableTools.size(), cumulativeTokens);
            BotDto.LLMResponse response = llmService.chat(bot, messagesForLlm(messages, envelope), availableTools);
            int responseTokens = response.getTokensUsed() != null && response.getTokensUsed() > 0
                    ? response.getTokensUsed()
                    : agentContextBuilder.estimateTokens(response.getContent());
            cumulativeTokens += responseTokens;
            lastAssistantContent = response.getContent() != null ? response.getContent() : "";

            List<BotDto.ToolCall> requestedTools = response.getToolCalls() != null
                    ? response.getToolCalls()
                    : Collections.emptyList();
            log.info("Agent loop iteration={} taskId={} toolCalls={} cumulativeTokens={}",
                    iteration, task.getId(), requestedTools.size(), cumulativeTokens);

            if (requestedTools.isEmpty()) {
                return new AgentLoopResult(
                        lastAssistantContent.isBlank() ? "任务已完成" : lastAssistantContent,
                        iteration,
                        List.copyOf(toolCalls),
                        TerminationReason.FINAL_ANSWER,
                        new BudgetSnapshot(cumulativeTokens, elapsedMs(startedAt)));
            }

            messages.add(new BotDto.ChatMessage(
                    "assistant",
                    lastAssistantContent,
                    null,
                    null,
                    requestedTools));

            for (BotDto.ToolCall requestedTool : requestedTools) {
                String rawResultJson = executeTool(
                        requestedTool,
                        toolContext,
                        toolCalls,
                        budget.remainingWallclockMs(startedAt));
                BotDto.ChatMessage selectedImageMessage = selectedToolImageMessage(rawResultJson);
                String resultJson = stripToolImagePayload(rawResultJson);
                messages.add(new BotDto.ChatMessage(
                        "tool",
                        resultJson,
                        requestedTool.getId(),
                        requestedTool.getName(),
                        null));
                cumulativeTokens += agentContextBuilder.estimateTokens(resultJson);
                if (selectedImageMessage != null) {
                    messages.add(selectedImageMessage);
                    cumulativeTokens += agentContextBuilder.estimateTokens(selectedImageMessage.textContent());
                }
            }

            BudgetHit afterToolsHit = budget.check(iteration + 1, startedAt, cumulativeTokens);
            if (afterToolsHit != null) {
                return exhausted(lastAssistantContent, iteration, toolCalls, cumulativeTokens, afterToolsHit, startedAt);
            }
        }

        return exhausted(lastAssistantContent, budget.maxIterations(), toolCalls, cumulativeTokens, BudgetHit.ITERATIONS, startedAt);
    }

    private String executeTool(BotDto.ToolCall requestedTool,
                               ToolContext context,
                               List<ToolCallRecord> toolCalls,
                               long remainingWallclockMs) {
        String toolName = requestedTool.getName();
        JsonNode arguments = parseArguments(requestedTool.getArgumentsJson());
        Instant started = Instant.now();
        try {
            Tool tool = toolRegistry.getTool(toolName)
                    .orElseThrow(() -> new ToolExecutionException("tool_not_allowed", "Tool is not available: " + toolName));
            JsonNode result = toolDispatcher.dispatch(tool, arguments, context, remainingWallclockMs);
            String resultJson = result.toString();
            toolCalls.add(new ToolCallRecord(toolName, requestedTool.getId(), result.has("error"), resultJson.length()));
            log.info("Tool executed: {} ctx={} roomId={} taskId={} resultBytes={}",
                    toolName, tool.executionContext(), context.roomId(), context.taskId(), resultJson.length());
            return resultJson;
        } catch (Exception e) {
            String code = e instanceof ToolExecutionException toolException
                    ? toolException.getCode()
                    : "tool_error";
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode error = root.putObject("error");
            error.put("code", code);
            error.put("message", e.getMessage() != null ? e.getMessage() : "Tool execution failed");
            String resultJson = root.toString();
            toolCalls.add(new ToolCallRecord(toolName, requestedTool.getId(), true, resultJson.length()));
            log.warn("Tool execution failed: {} roomId={} taskId={} elapsedMs={} error={}",
                    toolName, context.roomId(), context.taskId(), elapsedMs(started), e.getMessage());
            return resultJson;
        }
    }

    private JsonNode parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(argumentsJson);
        } catch (Exception e) {
            throw new ToolExecutionException("invalid_arguments", "Tool arguments are not valid JSON", e);
        }
    }

    private List<BotDto.ChatMessage> messagesForLlm(
            List<BotDto.ChatMessage> messages,
            AgentContextBuilder.AgentContextEnvelope envelope) {
        String postHistory = envelope.characterCard().postHistoryInstructions();
        if (postHistory == null || postHistory.isBlank()) {
            return messages;
        }
        List<BotDto.ChatMessage> withInstruction = new ArrayList<>(messages);
        int insertAt = -1;
        for (int i = withInstruction.size() - 1; i >= 0; i--) {
            if ("user".equals(withInstruction.get(i).getRole())) {
                insertAt = i;
                break;
            }
        }
        if (insertAt < 0) {
            insertAt = withInstruction.size();
        }
        withInstruction.add(insertAt, new BotDto.ChatMessage("system", postHistory));
        return withInstruction;
    }

    private BotDto.ChatMessage selectedToolImageMessage(String resultJson) {
        try {
            JsonNode root = objectMapper.readTree(resultJson);
            JsonNode payload = root.path("llm_image_attachment");
            String dataUrl = payload.path("dataUrl").asText("");
            if (dataUrl.isBlank()) {
                return null;
            }
            String name = payload.path("name").asText("selected-image");
            String mimeType = payload.path("mimeType").asText("image/jpeg");
            long messageId = root.path("messageId").asLong(0);
            String text = "Image selected by inspect_room_image"
                    + (messageId > 0 ? " from message " + messageId : "")
                    + ". Answer using this image only if it is relevant to the user's request.";
            return BotDto.ChatMessage.userWithImages(
                    text,
                    List.of(new BotDto.ImageAttachment(name, mimeType, dataUrl)));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stripToolImagePayload(String resultJson) {
        try {
            JsonNode root = objectMapper.readTree(resultJson);
            if (root instanceof ObjectNode objectNode && objectNode.has("llm_image_attachment")) {
                objectNode.remove("llm_image_attachment");
                objectNode.put("llm_image_available_to_next_llm_call", true);
                return objectNode.toString();
            }
        } catch (Exception ignored) {
            // Keep the original tool result if it is not a JSON object.
        }
        return resultJson;
    }

    private AgentLoopResult exhausted(String content,
                                      int iterations,
                                      List<ToolCallRecord> toolCalls,
                                      int cumulativeTokens,
                                      BudgetHit budgetHit,
                                      Instant startedAt) {
        String base = content != null && !content.isBlank() ? content : "任务已停止";
        String finalContent = base + "\n\n(stopped: budget exhausted - " + budgetHit.name().toLowerCase() + ")";
        log.warn("Agent loop stopped by budget cap: reason={} iterations={} cumulativeTokens={}",
                budgetHit, iterations, cumulativeTokens);
        return new AgentLoopResult(
                finalContent,
                iterations,
                List.copyOf(toolCalls),
                switch (budgetHit) {
                    case ITERATIONS -> TerminationReason.ITERATION_BUDGET;
                    case WALLCLOCK -> TerminationReason.WALLCLOCK_BUDGET;
                    case TOKENS -> TerminationReason.TOKEN_BUDGET;
                },
                new BudgetSnapshot(cumulativeTokens, elapsedMs(startedAt)));
    }

    private int estimateMessages(List<BotDto.ChatMessage> messages) {
        int total = 0;
        for (BotDto.ChatMessage message : messages) {
            total += agentContextBuilder.estimateTokens(message.textContent());
        }
        return total;
    }

    private long elapsedMs(Instant startedAt) {
        return Duration.between(startedAt, Instant.now()).toMillis();
    }

    private record Budget(int maxIterations, long maxWallclockMs, int maxTotalTokens) {
        static Budget from(BotConfig bot) {
            return new Budget(
                    positiveOrDefault(bot.getMaxAgentIterations(), 8),
                    positiveOrDefault(bot.getMaxAgentWallclockMs(), 30000),
                    positiveOrDefault(bot.getMaxAgentTotalTokens(), 50000));
        }

        BudgetHit check(int nextIteration, Instant startedAt, int cumulativeTokens) {
            if (nextIteration > maxIterations) {
                return BudgetHit.ITERATIONS;
            }
            if (Duration.between(startedAt, Instant.now()).toMillis() > maxWallclockMs) {
                return BudgetHit.WALLCLOCK;
            }
            if (cumulativeTokens > maxTotalTokens) {
                return BudgetHit.TOKENS;
            }
            return null;
        }

        long remainingWallclockMs(Instant startedAt) {
            long elapsed = Duration.between(startedAt, Instant.now()).toMillis();
            return Math.max(0L, maxWallclockMs - elapsed);
        }

        private static int positiveOrDefault(Integer value, int defaultValue) {
            return value != null && value > 0 ? value : defaultValue;
        }
    }

    private enum BudgetHit {
        ITERATIONS,
        WALLCLOCK,
        TOKENS
    }

    public record AgentLoopResult(
            String finalContent,
            int iterations,
            List<ToolCallRecord> toolCallsMade,
            TerminationReason terminationReason,
            BudgetSnapshot budgetUsage) {
    }

    public record ToolCallRecord(String name, String id, boolean error, int resultBytes) {
    }

    public record BudgetSnapshot(int totalTokensEstimate, long elapsedMs) {
    }

    public enum TerminationReason {
        FINAL_ANSWER,
        ITERATION_BUDGET,
        WALLCLOCK_BUDGET,
        TOKEN_BUDGET
    }
}
