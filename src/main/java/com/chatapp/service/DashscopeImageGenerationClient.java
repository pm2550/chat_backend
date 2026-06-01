package com.chatapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

@Component
@RequiredArgsConstructor
public class DashscopeImageGenerationClient implements ImageGenerationClient {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofSeconds(60))
            .build();

    @Value("${dashscope.image.base-url:https://dashscope-intl.aliyuncs.com/api/v1}")
    private String baseUrl;

    @Value("${dashscope.image.model:wan2.7-image-pro}")
    private String model;

    @Override
    public SubmitResult submit(String apiKey, String prompt, int count, String size) {
        try {
            ObjectNode bodyNode = objectMapper.createObjectNode();
            bodyNode.put("model", model);
            ObjectNode inputNode = bodyNode.putObject("input");
            ArrayNode messagesNode = inputNode.putArray("messages");
            ObjectNode userMessageNode = messagesNode.addObject();
            userMessageNode.put("role", "user");
            ArrayNode contentNode = userMessageNode.putArray("content");
            contentNode.addObject().put("text", prompt);
            ObjectNode parametersNode = bodyNode.putObject("parameters");
            parametersNode.put("n", count);
            parametersNode.put("size", normalizeSize(size));
            parametersNode.put("watermark", false);
            String body = objectMapper.writeValueAsString(bodyNode);
            Request request = new Request.Builder()
                    .url(trimSlash(baseUrl) + "/services/aigc/image-generation/generation")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("X-DashScope-Async", "enable")
                    .post(RequestBody.create(body, JSON))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                String payload = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    throw new IllegalStateException("DashScope submit failed: HTTP "
                            + response.code() + " " + payload);
                }
                JsonNode root = objectMapper.readTree(payload);
                String taskId = root.path("output").path("task_id").asText("");
                if (taskId.isBlank()) {
                    throw new IllegalStateException("DashScope submit response missing task_id");
                }
                return new SubmitResult(taskId);
            }
        } catch (IOException e) {
            throw new IllegalStateException("DashScope submit failed", e);
        }
    }

    @Override
    public PollResult poll(String apiKey, String taskId) {
        Request request = new Request.Builder()
                .url(trimSlash(baseUrl) + "/tasks/" + taskId)
                .header("Authorization", "Bearer " + apiKey)
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String payload = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("DashScope poll failed: HTTP "
                        + response.code() + " " + payload);
            }
            JsonNode output = objectMapper.readTree(payload).path("output");
            String taskStatus = output.path("task_status").asText("PENDING");
            return switch (taskStatus) {
                case "SUCCEEDED" -> new PollResult(PollResult.Status.SUCCEEDED, firstImageUrl(output), null);
                case "FAILED", "CANCELED", "UNKNOWN" -> new PollResult(
                        PollResult.Status.FAILED,
                        null,
                        output.path("message").asText(output.path("code").asText("生成失败")));
                case "RUNNING" -> new PollResult(PollResult.Status.RUNNING, null, null);
                default -> new PollResult(PollResult.Status.PENDING, null, null);
            };
        } catch (IOException e) {
            throw new IllegalStateException("DashScope poll failed", e);
        }
    }

    @Override
    public byte[] download(String imageUrl) {
        Request request = new Request.Builder().url(imageUrl).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("Generated image download failed: HTTP " + response.code());
            }
            return response.body().bytes();
        } catch (IOException e) {
            throw new IllegalStateException("Generated image download failed", e);
        }
    }

    private String firstImageUrl(JsonNode output) {
        JsonNode results = output.path("results");
        if (results.isArray() && !results.isEmpty()) {
            String url = results.get(0).path("url").asText("");
            if (!url.isBlank()) return url;
        }
        JsonNode choices = output.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode content = choices.get(0).path("message").path("content");
            if (content.isArray()) {
                for (JsonNode item : content) {
                    String image = item.path("image").asText("");
                    if (!image.isBlank()) return image;
                }
            }
        }
        throw new IllegalStateException("DashScope result missing image URL");
    }

    private String normalizeSize(String size) {
        if (size == null || size.isBlank()) {
            return "1024*1024";
        }
        if (size.endsWith("K")) {
            return size;
        }
        return size.replace('x', '*').replace('X', '*');
    }

    private String trimSlash(String value) {
        return value == null || value.isBlank()
                ? "https://dashscope-intl.aliyuncs.com/api/v1"
                : value.replaceAll("/+$", "");
    }
}
