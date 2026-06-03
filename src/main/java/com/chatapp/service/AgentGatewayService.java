package com.chatapp.service;

import com.chatapp.config.AgentGatewayProperties;
import com.chatapp.entity.AgentTask;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentGatewayService {

    private static final MediaType JSON = MediaType.parse("application/json");

    private final AgentGatewayProperties properties;
    private final ObjectMapper objectMapper;

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    public String execute(AgentTask task) {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("Agent gateway is not configured");
        }

        if (isOpenClawProvider()) {
            return executeOpenClaw(task);
        }

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("provider", properties.getProvider());
            body.put("taskId", task.getId());
            body.put("chatRoomId", task.getChatRoom().getId());
            body.put("requestedById", task.getRequestedBy().getId());
            body.put("prompt", task.getPrompt());

            Request.Builder builder = new Request.Builder()
                    .url(resolveUrl(properties.getExecutePath()))
                    .header("Content-Type", "application/json")
                    .header("X-Agent-Gateway-Provider", properties.getProvider())
                    .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON));
            applyAuth(builder);

            try (Response response = client().newCall(builder.build()).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("Agent gateway returned {}: {}", response.code(), responseBody);
                    throw new RuntimeException("Agent Gateway 调用失败: " + response.code());
                }
                return extractResult(responseBody);
            }
        } catch (IOException e) {
            throw new RuntimeException("Agent Gateway 服务调用失败", e);
        }
    }

    public GatewayProbe probeHealth() {
        if (!properties.isConfigured()) {
            return GatewayProbe.disabled();
        }

        if (isOpenClawProvider()) {
            return probeOpenClaw();
        }

        try {
            Request.Builder builder = new Request.Builder()
                    .url(resolveUrl(properties.getHealthPath()))
                    .get();
            applyAuth(builder);

            try (Response response = client().newCall(builder.build()).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                return response.isSuccessful()
                        ? GatewayProbe.up(response.code(), body)
                        : GatewayProbe.down(response.code(), body);
            }
        } catch (IOException e) {
            return GatewayProbe.down(0, e.getMessage());
        }
    }

    private OkHttpClient client() {
        return new OkHttpClient.Builder()
                .connectTimeout(properties.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(properties.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(properties.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
    }

    private boolean isOpenClawProvider() {
        return "openclaw".equalsIgnoreCase(properties.getProvider());
    }

    private String executeOpenClaw(AgentTask task) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", resolveOpenClawModel());
            body.put("stream", false);
            body.put("user", "chat-room-" + task.getChatRoom().getId());
            ArrayNode messages = body.putArray("messages");
            ObjectNode userMessage = messages.addObject();
            userMessage.put("role", "user");
            userMessage.put("content", task.getPrompt());

            Request.Builder builder = new Request.Builder()
                    .url(resolveOpenClawHttpUrl("/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("X-OpenClaw-Session-Key", openClawSessionKey(task))
                    .header("X-OpenClaw-Message-Channel", "webchat")
                    .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON));
            if (properties.getAgentId() != null && !properties.getAgentId().isBlank()) {
                builder.header("X-OpenClaw-Agent-Id", properties.getAgentId().trim());
            }
            applyAuth(builder);

            try (Response response = client().newCall(builder.build()).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("OpenClaw Gateway returned {}: {}", response.code(), responseBody);
                    throw new RuntimeException("OpenClaw Gateway 调用失败: " + response.code());
                }
                return extractResult(responseBody);
            }
        } catch (IOException e) {
            throw new RuntimeException("OpenClaw Gateway 服务调用失败", e);
        }
    }

    private GatewayProbe probeOpenClaw() {
        try {
            Request request = new Request.Builder()
                    .url(resolveOpenClawHttpUrl("/healthz"))
                    .get()
                    .build();

            try (Response response = client().newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                return response.isSuccessful()
                        ? GatewayProbe.up(response.code(), body)
                        : GatewayProbe.down(response.code(), body);
            }
        } catch (IOException e) {
            return GatewayProbe.down(0, e.getMessage());
        }
    }

    private String openClawSessionKey(AgentTask task) {
        String prefix = properties.getSessionKeyPrefix() == null || properties.getSessionKeyPrefix().isBlank()
                ? "chatapp"
                : properties.getSessionKeyPrefix().trim();
        return prefix + ":room:" + task.getChatRoom().getId();
    }

    private String resolveOpenClawModel() {
        if (properties.getAgentId() != null && !properties.getAgentId().isBlank()) {
            return "openclaw/" + properties.getAgentId().trim();
        }
        return "openclaw/default";
    }

    private JsonNode callOpenClaw(String method, ObjectNode params, boolean expectFinal) throws IOException {
        String connectId = "connect-" + UUID.randomUUID();
        String requestId = "call-" + UUID.randomUUID();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<JsonNode> payloadRef = new AtomicReference<>();
        AtomicReference<IOException> errorRef = new AtomicReference<>();

        OkHttpClient wsClient = client();
        Request request = new Request.Builder()
                .url(resolveOpenClawWebSocketUrl())
                .build();

        WebSocket webSocket = wsClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleOpenClawMessage(
                        webSocket, text, connectId, requestId, method, params, expectFinal, payloadRef, errorRef, done);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                errorRef.compareAndSet(null, new IOException(t.getMessage(), t));
                done.countDown();
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (payloadRef.get() == null && errorRef.get() == null) {
                    errorRef.set(new IOException("OpenClaw Gateway WebSocket closed: " + code + " " + reason));
                    done.countDown();
                }
            }
        });

        boolean completed;
        try {
            long waitMs = expectFinal
                    ? Math.max(properties.getReadTimeoutMs(), properties.getTaskTimeoutSeconds() * 1000L + 30_000L)
                    : properties.getReadTimeoutMs();
            completed = done.await(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("OpenClaw Gateway call interrupted", e);
        } finally {
            webSocket.close(1000, "done");
            wsClient.dispatcher().executorService().shutdown();
            wsClient.connectionPool().evictAll();
        }

        if (!completed) {
            throw new IOException("OpenClaw Gateway call timed out");
        }
        if (errorRef.get() != null) {
            throw errorRef.get();
        }
        JsonNode payload = payloadRef.get();
        if (payload == null) {
            throw new IOException("OpenClaw Gateway returned no payload");
        }
        return payload;
    }

    private void handleOpenClawMessage(
            WebSocket webSocket,
            String text,
            String connectId,
            String requestId,
            String method,
            ObjectNode params,
            boolean expectFinal,
            AtomicReference<JsonNode> payloadRef,
            AtomicReference<IOException> errorRef,
            CountDownLatch done) {
        try {
            JsonNode frame = objectMapper.readTree(text);
            if ("event".equals(frame.path("type").asText()) &&
                    "connect.challenge".equals(frame.path("event").asText())) {
                webSocket.send(objectMapper.writeValueAsString(openClawConnectFrame(connectId)));
                return;
            }

            if (!"res".equals(frame.path("type").asText())) {
                return;
            }

            String frameId = frame.path("id").asText();
            if (connectId.equals(frameId)) {
                if (!frame.path("ok").asBoolean(false)) {
                    errorRef.set(new IOException(openClawErrorMessage(frame)));
                    done.countDown();
                    return;
                }
                ObjectNode call = objectMapper.createObjectNode();
                call.put("type", "req");
                call.put("id", requestId);
                call.put("method", method);
                call.set("params", params);
                webSocket.send(objectMapper.writeValueAsString(call));
                return;
            }

            if (!requestId.equals(frameId)) {
                return;
            }

            if (!frame.path("ok").asBoolean(false)) {
                errorRef.set(new IOException(openClawErrorMessage(frame)));
                done.countDown();
                return;
            }

            JsonNode payload = frame.path("payload");
            if (expectFinal && "accepted".equalsIgnoreCase(payload.path("status").asText())) {
                return;
            }
            payloadRef.set(payload);
            done.countDown();
        } catch (Exception e) {
            errorRef.set(new IOException("OpenClaw Gateway frame parse failed", e));
            done.countDown();
        }
    }

    private ObjectNode openClawConnectFrame(String connectId) {
        ObjectNode frame = objectMapper.createObjectNode();
        frame.put("type", "req");
        frame.put("id", connectId);
        frame.put("method", "connect");

        ObjectNode params = frame.putObject("params");
        params.put("minProtocol", 3);
        params.put("maxProtocol", 3);
        ObjectNode client = params.putObject("client");
        client.put("id", "cli");
        client.put("version", "chatapp-backend");
        client.put("platform", "java");
        client.put("mode", "cli");
        params.put("role", "operator");
        params.putArray("scopes").add("operator.read").add("operator.write");
        params.putArray("caps");
        params.putArray("commands");
        params.putObject("permissions");
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            params.putObject("auth").put("token", properties.getApiKey().trim());
        }
        params.put("locale", "zh-CN");
        params.put("userAgent", "chatapp-backend");
        return frame;
    }

    private String openClawErrorMessage(JsonNode frame) {
        JsonNode error = frame.path("error");
        String message = error.path("message").asText("");
        String code = error.path("code").asText("");
        if (!message.isBlank() && !code.isBlank()) {
            return "OpenClaw Gateway " + code + ": " + message;
        }
        if (!message.isBlank()) {
            return "OpenClaw Gateway: " + message;
        }
        return "OpenClaw Gateway call failed: " + frame;
    }

    private String resolveOpenClawWebSocketUrl() {
        String base = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        if (base.startsWith("http://")) {
            return "ws://" + base.substring("http://".length()).replaceAll("/+$", "");
        }
        if (base.startsWith("https://")) {
            return "wss://" + base.substring("https://".length()).replaceAll("/+$", "");
        }
        return base.replaceAll("/+$", "");
    }

    String resolveOpenClawHttpUrl(String path) {
        String base = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        if (base.startsWith("ws://")) {
            base = "http://" + base.substring("ws://".length());
        } else if (base.startsWith("wss://")) {
            base = "https://" + base.substring("wss://".length());
        }
        String suffix = path == null || path.isBlank() ? "/" : path;
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        return base.replaceAll("/+$", "") + suffix;
    }

    private void applyAuth(Request.Builder builder) {
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + properties.getApiKey());
        }
    }

    private String resolveUrl(String path) {
        String base = properties.getBaseUrl().replaceAll("/+$", "");
        String suffix = path == null || path.isBlank() ? "/" : path;
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        return base + suffix;
    }

    String extractResult(String responseBody) throws IOException {
        if (responseBody == null || responseBody.isBlank()) {
            return "任务已完成";
        }

        JsonNode json;
        try {
            json = objectMapper.readTree(responseBody);
        } catch (IOException notJson) {
            return responseBody.trim();
        }

        String direct = firstText(json, "result", "output", "content", "message");
        if (direct != null) {
            return direct;
        }

        JsonNode payloads = json.path("result").path("payloads");
        if (payloads.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode payload : payloads) {
                String text = firstText(payload, "text", "content", "message");
                if (text != null) {
                    if (!builder.isEmpty()) {
                        builder.append("\n");
                    }
                    builder.append(text);
                }
            }
            if (!builder.isEmpty()) {
                return builder.toString();
            }
        }

        JsonNode choices = json.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            for (JsonNode choice : choices) {
                String messageContent = firstText(choice.path("message"), "content");
                if (messageContent != null) {
                    return messageContent;
                }
                String deltaContent = firstText(choice.path("delta"), "content");
                if (deltaContent != null) {
                    return deltaContent;
                }
                String text = firstText(choice, "text");
                if (text != null) {
                    return text;
                }
            }
        }

        JsonNode data = json.path("data");
        String nested = firstText(data, "result", "output", "content", "message");
        return nested != null ? nested : json.toString();
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    public record GatewayProbe(boolean enabled, boolean up, int statusCode, String detail) {
        static GatewayProbe disabled() {
            return new GatewayProbe(false, true, 0, "disabled");
        }

        static GatewayProbe up(int statusCode, String detail) {
            return new GatewayProbe(true, true, statusCode, detail);
        }

        static GatewayProbe down(int statusCode, String detail) {
            return new GatewayProbe(true, false, statusCode, detail);
        }
    }
}
