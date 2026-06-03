package com.chatapp.service.tool;

import com.chatapp.websocket.RawWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentToolDispatcher {
    static final long DEFAULT_CLIENT_TIMEOUT_MS = 15000L;

    private final PendingClientCallRegistry pendingClientCallRegistry;
    private final RawWebSocketHandler rawWebSocketHandler;
    private final ObjectMapper objectMapper;

    public JsonNode dispatch(Tool tool, JsonNode params, ToolContext context, long remainingWallclockMs) {
        if (tool.executionContext() == Tool.ExecutionContext.SERVER) {
            return tool.execute(params, context);
        }
        if (tool.executionContext() == Tool.ExecutionContext.EITHER) {
            // v1 has no EITHER tools; prefer server execution if one is introduced later.
            return tool.execute(params, context);
        }
        return dispatchClientTool(tool, params, context, remainingWallclockMs);
    }

    private JsonNode dispatchClientTool(Tool tool, JsonNode params, ToolContext context, long remainingWallclockMs) {
        long timeoutMs = Math.min(DEFAULT_CLIENT_TIMEOUT_MS, Math.max(0L, remainingWallclockMs));
        if (timeoutMs <= 0L) {
            return error("client_tool_timeout", "agent loop wall-clock budget exhausted before client dispatch");
        }

        UUID callId = UUID.randomUUID();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        Instant started = Instant.now();
        pendingClientCallRegistry.register(callId, context.userId(), future, timeoutMs);

        boolean sent = rawWebSocketHandler.sendAgentToolRequest(context.userId(), callId, tool.name(), params);
        log.info("dispatch tool={} ctx=client callId={} targetUserId={} timeoutMs={} sent={}",
                tool.name(), callId, context.userId(), timeoutMs, sent);
        if (!sent) {
            pendingClientCallRegistry.complete(callId, context.userId(),
                    error("client_offline", "no active WebSocket session for client tool"));
            return error("client_offline", "no active WebSocket session for client tool");
        }

        try {
            JsonNode result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            log.info("client tool result received callId={} tool={} resultBytes={} latencyMs={}",
                    callId, tool.name(), result.toString().length(), elapsedMs(started));
            return result;
        } catch (TimeoutException e) {
            pendingClientCallRegistry.cancel(callId);
            log.warn("client tool timeout callId={} tool={} userId={} elapsedMs={}",
                    callId, tool.name(), context.userId(), elapsedMs(started));
            return error("client_tool_timeout", "no client response within " + timeoutMs + "ms");
        } catch (Exception e) {
            pendingClientCallRegistry.cancel(callId);
            log.warn("client tool dispatch failed callId={} tool={} userId={} error={}",
                    callId, tool.name(), context.userId(), e.getMessage());
            return error("client_tool_error", e.getMessage() != null ? e.getMessage() : "client tool failed");
        }
    }

    private long elapsedMs(Instant started) {
        return Duration.between(started, Instant.now()).toMillis();
    }

    private ObjectNode error(String code, String message) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode error = root.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return root;
    }
}
