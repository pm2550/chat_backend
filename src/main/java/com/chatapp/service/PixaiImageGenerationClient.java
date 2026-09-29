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
 * PixAI（api.pixai.art）文生图：v1 建任务 / 查任务，结果是带签名的临时地址，要尽快下载。
 * 提示词自动扩写用 PixAI 自带的 promptHelper（不再经过 Grok），分三档：
 * off = {"enable":false} 原文照发；low = 只翻译不改写；medium = 补充创意细节（PixAI 网页默认）。
 *
 * <p>用 v1 而不是 v2 建任务：v2 不能指定优先级，一律按"高优先级"（priority 1000）排队，
 * 每张多扣 1000 额度；也不能选档位（Tsubaki.2 默认 lite）。priority 500 走会员免费的
 * Turbo 队列，实测排队同样只要几秒。
 */
@Component
public class PixaiImageGenerationClient implements ImageGenerationClient {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long MAX_IMAGE_BYTES = 30L * 1024 * 1024;
    /** 只放画质词，不放任何内容词（水印/文字/nsfw 之类都不加）。 */
    static final String DEFAULT_NEGATIVE_PROMPT = "lowres, worst quality, low quality, blurry, jpeg artifacts, "
            + "bad anatomy, bad hands, extra fingers, missing fingers, fused fingers, deformed, "
            + "overexposed, underexposed";

    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    @Value("${image-generation.pixai.api-key:}")
    private String apiKey;

    @Value("${image-generation.pixai.base-url:https://api.pixai.art}")
    private String baseUrl;

    /** 默认 Tsubaki.2（PixAI 旗舰模型）。 */
    @Value("${image-generation.pixai.model-version-id:1983308862240288769}")
    private String modelVersionId;

    /** 推理档位：Tsubaki.2 有 lite / standard / pro / ultra，Tsubaki.3 只有 pro / ultra。 */
    @Value("${image-generation.pixai.inference-profile:standard}")
    private String inferenceProfile;

    /** 1000 = 高优先级（每张 +1000 额度）；500 = 会员 Turbo（不加钱）。 */
    @Value("${image-generation.pixai.priority:500}")
    private int priority;

    /** 每个任务都带的反向提示词（用户改不了）；留空 = 不传。 */
    @Value("${image-generation.pixai.negative-prompt:" + DEFAULT_NEGATIVE_PROMPT + "}")
    private String negativePrompt = DEFAULT_NEGATIVE_PROMPT;

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

    void configure(String apiKey, String baseUrl, String modelVersionId, String inferenceProfile, int priority) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.modelVersionId = modelVersionId;
        this.inferenceProfile = inferenceProfile;
        this.priority = priority;
    }

    void configureNegativePrompt(String negativePrompt) {
        this.negativePrompt = negativePrompt;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public SubmitResult submit(String ignoredApiKey, String prompt, int count, String size) {
        return submit(ignoredApiKey, prompt, count, size, PROMPT_HELPER_MEDIUM);
    }

    @Override
    public SubmitResult submit(String ignoredApiKey, String prompt, int count, String size, boolean expand) {
        return submit(ignoredApiKey, prompt, count, size, expand ? PROMPT_HELPER_MEDIUM : PROMPT_HELPER_OFF);
    }

    @Override
    public SubmitResult submit(String ignoredApiKey, String prompt, int count, String size, String promptHelperLevel) {
        if (count != 1) {
            throw new IllegalArgumentException("PixAI image generation is used with exactly one image per request");
        }
        requireConfigured();
        int[] dimensions = dimensionsForSize(size);
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode parameters = body.putObject("parameters");
        parameters.put("modelId", modelVersionId);
        parameters.put("prompts", prompt);
        if (negativePrompt != null && !negativePrompt.isBlank()) {
            parameters.put("negativePrompts", negativePrompt.trim());
        }
        parameters.put("width", dimensions[0]);
        parameters.put("height", dimensions[1]);
        parameters.put("batchSize", 1);
        parameters.put("priority", priority);
        if (inferenceProfile != null && !inferenceProfile.isBlank()) {
            parameters.put("inferenceProfile", inferenceProfile.trim());
        }
        ObjectNode promptHelper = parameters.putObject("promptHelper");
        if (PROMPT_HELPER_LOW.equals(promptHelperLevel) || PROMPT_HELPER_MEDIUM.equals(promptHelperLevel)) {
            promptHelper.put("enable", true);
            promptHelper.put("creativity", promptHelperLevel);
        } else {
            promptHelper.put("enable", false);
        }
        try {
            Request request = authorized(url("/v1/task"))
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
        // 自家接口要带 key；OkHttp 跟随跳到别的域名时会自动去掉 Authorization。
        Request request = isPixaiApi(parsed)
                ? authorized(parsed).get().build()
                : new Request.Builder().url(parsed).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
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

    private boolean isPixaiApi(HttpUrl target) {
        HttpUrl api = url("/");
        return target.host().equalsIgnoreCase(api.host());
    }

    /**
     * 按比例取约 2MP 的 XL 尺寸（边长是 16 的倍数）。实测 XL 和 1MP 扣的额度、耗时都一样，
     * PixAI 也接受任意宽高（1104x1824、1072x1904 都试过）。
     */
    static int[] dimensionsForSize(String size) {
        String normalized = size == null ? "" : size.trim().toLowerCase().replace('*', 'x');
        return switch (normalized) {
            case "1024x1792", "9x16", "9:16" -> new int[]{1072, 1904};
            case "1792x1024", "16x9", "16:9" -> new int[]{1904, 1072};
            case "1024x1365", "3x4", "3:4" -> new int[]{1232, 1648};
            case "1365x1024", "4x3", "4:3" -> new int[]{1648, 1232};
            case "2x3", "2:3" -> new int[]{1168, 1744};
            case "3x2", "3:2" -> new int[]{1744, 1168};
            default -> new int[]{1424, 1424};
        };
    }

    /**
     * 优先用媒体 ID 走 /v1/media/{id}/image（带 key，302 到当前可用的文件）：任务结果里的
     * mediaUrls 是 images/temp 临时文件，几分钟后就被删（签名没过期也 403），超时后补发会拿不到。
     */
    private String firstMediaUrl(JsonNode task) {
        for (JsonNode id : task.path("outputs").path("mediaIds")) {
            if (id.isTextual() && !id.asText().isBlank()) {
                return url("/v1/media/" + id.asText() + "/image").toString();
            }
        }
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
