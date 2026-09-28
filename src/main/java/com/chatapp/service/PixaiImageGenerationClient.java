package com.chatapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

/**
 * PixAI（api.pixai.art）文生图：v2 建任务，v1 查任务，结果是带签名的临时地址，要尽快下载。
 * 提示词自动扩写用 PixAI 自带的 promptHelper（不再经过 Grok）。
 */
@Component
public class PixaiImageGenerationClient implements ImageGenerationClient {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long MAX_IMAGE_BYTES = 30L * 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    @Value("${image-generation.pixai.api-key:}")
    private String apiKey;

    @Value("${image-generation.pixai.base-url:https://api.pixai.art}")
    private String baseUrl;

    /** 默认 Tsubaki.2（PixAI 旗舰模型）。 */
    @Value("${image-generation.pixai.model-version-id:1983308862240288769}")
    private String modelVersionId;

    @Autowired
    public PixaiImageGenerationClient(ObjectMapper objectMapper) {
        this(objectMapper, new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(60))
                .writeTimeout(Duration.ofSeconds(20))
                .build());
    }

    PixaiImageGenerationClient(ObjectMapper objectMapper, OkHttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    void configure(String apiKey, String baseUrl, String modelVersionId) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.modelVersionId = modelVersionId;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public SubmitResult submit(String ignoredApiKey, String prompt, int count, String size) {
        return submit(ignoredApiKey, prompt, count, size, true);
    }

    @Override
    public SubmitResult submit(String ignoredApiKey, String prompt, int count, String size, boolean expand) {
        if (count != 1) {
            throw new IllegalArgumentException("PixAI image generation is used with exactly one image per request");
        }
        requireConfigured();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("modelVersionId", modelVersionId);
        body.put("prompt", prompt);
        body.put("aspectRatio", aspectRatioForSize(size));
        body.put("promptHelper", expand ? "enable" : "disable");
        try {
            Request request = authorized(url("/v2/image/create"))
                    .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON))
                    .build();
            JsonNode root = execute(request, "建任务");
            String taskId = root.path("id").asText("");
            if (taskId.isBlank()) {
                throw new IllegalStateException("PixAI 建任务没有返回任务 id");
            }
            return new SubmitResult(taskId);
        } catch (IOException e) {
            throw new IllegalStateException("PixAI 建任务失败: " + e.getMessage(), e);
        }
    }

    @Override
    public PollResult poll(String ignoredApiKey, String taskId) {
        requireConfigured();
        try {
            JsonNode task = execute(authorized(url("/v1/task/" + taskId)).get().build(), "查任务");
            String status = task.path("status").asText("");
            return switch (status) {
                case "completed" -> {
                    String imageUrl = firstMediaUrl(task);
                    yield imageUrl == null
                            ? new PollResult(PollResult.Status.FAILED, null, "PixAI 任务完成但没有图片")
                            : new PollResult(PollResult.Status.SUCCEEDED, imageUrl, null);
                }
                case "failed" -> new PollResult(PollResult.Status.FAILED, null, "PixAI 生成失败");
                case "cancelled" -> new PollResult(PollResult.Status.FAILED, null, "PixAI 任务被取消");
                default -> new PollResult(PollResult.Status.RUNNING, null, null);
            };
        } catch (IOException e) {
            // 网络抖动：当作还在跑，下一轮再查；总超时由调用方控制。
            return new PollResult(PollResult.Status.RUNNING, null, null);
        }
    }

    @Override
    public byte[] download(String imageUrl) {
        HttpUrl parsed = HttpUrl.parse(imageUrl == null ? "" : imageUrl);
        if (parsed == null || !parsed.isHttps()) {
            throw new IllegalStateException("PixAI 图片地址无效");
        }
        try (Response response = httpClient.newCall(new Request.Builder().url(parsed).get().build()).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IllegalStateException("PixAI 图片下载失败: HTTP " + response.code());
            }
            try (InputStream in = body.byteStream()) {
                byte[] bytes = in.readNBytes((int) MAX_IMAGE_BYTES + 1);
                if (bytes.length > MAX_IMAGE_BYTES) {
                    throw new IllegalStateException("PixAI 图片过大");
                }
                return bytes;
            }
        } catch (IOException e) {
            throw new IllegalStateException("PixAI 图片下载失败: " + e.getMessage(), e);
        }
    }

    static String aspectRatioForSize(String size) {
        String normalized = size == null ? "" : size.trim().toLowerCase().replace('*', 'x');
        return switch (normalized) {
            case "1024x1792", "9x16", "9:16" -> "9:16";
            case "1792x1024", "16x9", "16:9" -> "16:9";
            case "1024x1365", "3x4", "3:4" -> "3:4";
            case "1365x1024", "4x3", "4:3" -> "4:3";
            case "2x3", "2:3" -> "2:3";
            case "3x2", "3:2" -> "3:2";
            default -> "1:1";
        };
    }

    private String firstMediaUrl(JsonNode task) {
        for (JsonNode url : task.path("outputs").path("mediaUrls")) {
            if (url.isTextual() && !url.asText().isBlank()) {
                return url.asText();
            }
        }
        return null;
    }

    private JsonNode execute(Request request, String action) throws IOException {
        try (Response response = httpClient.newCall(request).execute()) {
            String payload = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("PixAI " + action + "失败: HTTP " + response.code()
                        + describeError(response.code(), payload));
            }
            return objectMapper.readTree(payload.isBlank() ? "{}" : payload);
        }
    }

    private String describeError(int code, String payload) {
        String hint = switch (code) {
            case 401, 403 -> "（API key 无效或没有权限）";
            case 402 -> "（PixAI 额度不足）";
            case 429 -> "（排队任务过多，稍后再试）";
            default -> "";
        };
        if (payload == null || payload.isBlank()) {
            return hint;
        }
        String trimmed = payload.length() > 200 ? payload.substring(0, 200) + "…" : payload;
        return hint + " " + trimmed;
    }

    private Request.Builder authorized(HttpUrl url) {
        return new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey.trim())
                .header("Accept", "application/json");
    }

    private HttpUrl url(String path) {
        String base = baseUrl == null || baseUrl.isBlank() ? "https://api.pixai.art" : baseUrl.trim();
        HttpUrl url = HttpUrl.parse(base.replaceAll("/+$", "") + path);
        if (url == null) {
            throw new IllegalStateException("PixAI 地址配置错误");
        }
        return url;
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("PixAI 未配置 API key");
        }
    }
}
