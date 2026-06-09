package com.chatapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

@Component
@Primary
@RequiredArgsConstructor
public class HermesImageGenerationClient implements ImageGenerationClient {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final ObjectMapper objectMapper;
    private final OkHttpClient baseHttpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${image-generation.hermes.draw-url:http://hermes-image-api:8765/draw}")
    private String drawUrl;

    @Value("${image-generation.hermes.image-root:/data2/hermes/data/cache/images}")
    private String imageRoot;

    @Value("${image-generation.hermes.timeout-seconds:240}")
    private long timeoutSeconds;

    @Override
    public SubmitResult submit(String apiKey, String prompt, int count, String size) {
        return submit(apiKey, prompt, count, size, true);
    }

    @Override
    public SubmitResult submit(String apiKey, String prompt, int count, String size, boolean expand) {
        if (count != 1) {
            throw new IllegalArgumentException("Hermes image generation supports exactly one image per request");
        }
        try {
            ObjectNode bodyNode = objectMapper.createObjectNode();
            bodyNode.put("prompt", prompt);
            bodyNode.put("ratio", ratioForSize(size));
            bodyNode.put("expand", expand);

            Request request = new Request.Builder()
                    .url(drawUrl)
                    .post(RequestBody.create(objectMapper.writeValueAsString(bodyNode), JSON))
                    .build();

            OkHttpClient client = baseHttpClient.newBuilder()
                    .readTimeout(Duration.ofSeconds(Math.max(1L, timeoutSeconds)))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                String payload = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    throw new IllegalStateException("Hermes /draw failed: HTTP "
                            + response.code() + " " + errorMessage(payload));
                }
                JsonNode root = objectMapper.readTree(payload);
                String imagePath = root.path("image_path").asText("");
                if (imagePath.isBlank()) {
                    throw new IllegalStateException("Hermes /draw response missing image_path");
                }
                return new SubmitResult(imagePath);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Hermes /draw failed", e);
        }
    }

    @Override
    public PollResult poll(String apiKey, String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return new PollResult(PollResult.Status.FAILED, null, "Hermes image path is empty");
        }
        return new PollResult(PollResult.Status.SUCCEEDED, taskId, null);
    }

    @Override
    public byte[] download(String imageUrl) {
        try {
            Path root = Paths.get(imageRoot).toAbsolutePath().normalize().toRealPath();
            Path requested = Paths.get(imageUrl);
            Path file = requested.isAbsolute()
                    ? requested.toAbsolutePath().normalize()
                    : root.resolve(requested).normalize();
            Path realFile = file.toRealPath();
            if (!realFile.startsWith(root)) {
                throw new IllegalStateException("Hermes image path is outside allowed cache root");
            }
            return Files.readAllBytes(realFile);
        } catch (IOException e) {
            throw new IllegalStateException("Hermes generated image file is not readable", e);
        }
    }

    private String ratioForSize(String size) {
        String normalized = size == null ? "" : size.trim().toLowerCase().replace('*', 'x');
        return switch (normalized) {
            case "1024x1792", "9x16", "9:16" -> "9:16";
            case "1792x1024", "16x9", "16:9" -> "16:9";
            case "1024x1365", "3x4", "3:4" -> "3:4";
            case "1365x1024", "4x3", "4:3" -> "4:3";
            default -> "1:1";
        };
    }

    private String errorMessage(String payload) {
        if (payload == null || payload.isBlank()) {
            return "empty response";
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            String error = root.path("error").asText("");
            return error.isBlank() ? payload : error;
        } catch (IOException ignored) {
            return payload;
        }
    }
}
