package com.chatapp.service;

import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ProviderCredential;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Executes image generation against a bot owner's encrypted BYO provider. */
@Service
@RequiredArgsConstructor
public class BotImageGenerationClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long MAX_RESPONSE_BYTES = 32L * 1024L * 1024L;
    private static final String NOVELAI_ENDPOINT = "https://api.novelai.net/ai/generate-image";

    private final ObjectMapper objectMapper;
    private final ProviderCredentialService providerCredentialService;
    private final OutboundUrlPolicy outboundUrlPolicy;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15))
            .writeTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofSeconds(240))
            .callTimeout(Duration.ofSeconds(250))
            .build();

    public ProviderConfig resolve(BotConfig botConfig) {
        if (botConfig == null
                || botConfig.getImageGenerationProvider() == null
                || botConfig.getImageGenerationProvider() == BotConfig.ImageGenerationProvider.HERMES) {
            return ProviderConfig.hermes();
        }
        ProviderCredential credential = botConfig.getImageProviderCredential();
        if (credential == null) {
            throw new IllegalStateException("这个 Bot 还没有配置画图 API 凭据");
        }
        String secret = providerCredentialService.decrypt(credential);
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("这个 Bot 的画图 API 凭据已失效");
        }
        String endpoint = credential.getBaseUrl();
        if (botConfig.getImageGenerationProvider() == BotConfig.ImageGenerationProvider.NOVELAI
                && (endpoint == null || endpoint.isBlank())) {
            endpoint = NOVELAI_ENDPOINT;
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException("请为这个 Bot 配置画图 API Endpoint");
        }
        outboundUrlPolicy.assertAllowed(endpoint, OutboundUrlPolicy.Caller.USER_SUPPLIED);
        String model = firstNonBlank(botConfig.getImageModel(), credential.getModelOverride());
        return new ProviderConfig(
                botConfig.getImageGenerationProvider(),
                secret,
                endpoint.trim(),
                model,
                botConfig.getImageNegativePrompt());
    }

    public GeneratedImage generate(ProviderConfig config, String prompt, String size) {
        if (config == null || config.provider() == BotConfig.ImageGenerationProvider.HERMES) {
            throw new IllegalArgumentException("Hermes generation is handled by the platform client");
        }
        return switch (config.provider()) {
            case OPENAI_COMPATIBLE -> generateOpenAiCompatible(config, prompt, size);
            case NOVELAI -> generateNovelAi(config, prompt, size);
            case HERMES -> throw new IllegalArgumentException("Unexpected Hermes provider");
        };
    }

    private GeneratedImage generateOpenAiCompatible(ProviderConfig config, String prompt, String size) {
        String endpoint = openAiImageEndpoint(config.endpoint());
        outboundUrlPolicy.assertAllowed(endpoint, OutboundUrlPolicy.Caller.USER_SUPPLIED);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("prompt", prompt);
        body.put("model", firstNonBlank(config.model(), "gpt-image-1"));
        body.put("n", 1);
        body.put("size", normalizeOpenAiSize(size));
        body.put("response_format", "b64_json");
        return executeJsonImageRequest(endpoint, config.apiKey(), body, false);
    }

    private GeneratedImage generateNovelAi(ProviderConfig config, String prompt, String size) {
        int[] dimensions = dimensions(size);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("input", prompt);
        body.put("model", firstNonBlank(config.model(), "nai-diffusion-3"));
        body.put("action", "generate");
        ObjectNode parameters = body.putObject("parameters");
        parameters.put("width", dimensions[0]);
        parameters.put("height", dimensions[1]);
        parameters.put("scale", 5.0);
        parameters.put("sampler", "k_euler_ancestral");
        parameters.put("steps", 28);
        parameters.put("n_samples", 1);
        parameters.put("uc", firstNonBlank(
                config.negativePrompt(),
                "lowres, bad anatomy, bad hands, text, watermark, signature"));
        parameters.put("ucPreset", 0);
        parameters.put("qualityToggle", true);
        parameters.put("sm", false);
        parameters.put("sm_dyn", false);
        return executeJsonImageRequest(config.endpoint(), config.apiKey(), body, true);
    }

    private GeneratedImage executeJsonImageRequest(
            String endpoint,
            String apiKey,
            JsonNode body,
            boolean acceptZip) {
        Request request;
        try {
            request = new Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", acceptZip ? "application/zip, application/json, image/*" : "application/json")
                    .post(RequestBody.create(objectMapper.writeValueAsBytes(body), JSON))
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("画图请求序列化失败", e);
        }

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.body() == null) {
                throw new IllegalStateException("画图 API 返回了空响应");
            }
            long declaredLength = response.body().contentLength();
            if (declaredLength > MAX_RESPONSE_BYTES) {
                throw new IllegalStateException("画图 API 返回内容过大");
            }
            byte[] payload = response.body().bytes();
            if (payload.length > MAX_RESPONSE_BYTES) {
                throw new IllegalStateException("画图 API 返回内容过大");
            }
            if (!response.isSuccessful()) {
                throw new IllegalStateException("画图 API 调用失败: HTTP "
                        + response.code() + " " + safeError(payload));
            }
            String contentType = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
            if (contentType.contains("zip") || looksLikeZip(payload)) {
                return unzipFirstImage(payload);
            }
            if (contentType.startsWith("image/")) {
                return new GeneratedImage(payload, contentType.split(";", 2)[0]);
            }
            return decodeJsonOrEventStream(payload);
        } catch (IOException e) {
            throw new IllegalStateException("画图 API 连接失败", e);
        }
    }

    private GeneratedImage decodeJsonOrEventStream(byte[] payload) throws IOException {
        String text = new String(payload, java.nio.charset.StandardCharsets.UTF_8).trim();
        if (text.startsWith("data:")) {
            String latest = null;
            for (String line : text.split("\\R")) {
                if (line.startsWith("data:")) latest = line.substring(5).trim();
            }
            if (latest != null) text = latest;
        }
        JsonNode root = objectMapper.readTree(text);
        String encoded = root.path("image").asText("");
        if (encoded.isBlank()) encoded = root.path("data").path(0).path("b64_json").asText("");
        if (!encoded.isBlank()) {
            return new GeneratedImage(Base64.getDecoder().decode(stripDataPrefix(encoded)), "image/png");
        }
        String imageUrl = root.path("data").path(0).path("url").asText("");
        if (!imageUrl.isBlank()) {
            return downloadPublicImage(imageUrl);
        }
        throw new IllegalStateException("画图 API 响应中没有图片");
    }

    private GeneratedImage downloadPublicImage(String imageUrl) throws IOException {
        outboundUrlPolicy.assertAllowed(imageUrl, OutboundUrlPolicy.Caller.USER_SUPPLIED);
        try (Response response = httpClient.newCall(new Request.Builder().url(imageUrl).get().build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("画图 API 返回的图片无法下载");
            }
            byte[] bytes = response.body().bytes();
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new IllegalStateException("画图 API 返回图片过大");
            }
            String mime = response.header("Content-Type", "image/png").split(";", 2)[0];
            return new GeneratedImage(bytes, mime);
        }
    }

    private GeneratedImage unzipFirstImage(byte[] zipBytes) throws IOException {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (entry.isDirectory() || !(name.endsWith(".png") || name.endsWith(".jpg")
                        || name.endsWith(".jpeg") || name.endsWith(".webp"))) {
                    continue;
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                input.transferTo(output);
                byte[] bytes = output.toByteArray();
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("压缩包中的图片过大");
                }
                String mime = name.endsWith(".webp") ? "image/webp"
                        : name.endsWith(".jpg") || name.endsWith(".jpeg") ? "image/jpeg"
                        : "image/png";
                return new GeneratedImage(bytes, mime);
            }
        }
        throw new IllegalStateException("画图 API 压缩包中没有图片");
    }

    private String openAiImageEndpoint(String baseUrl) {
        String value = baseUrl.trim().replaceAll("/+$", "");
        if (value.endsWith("/images/generations")) return value;
        if (value.endsWith("/v1")) return value + "/images/generations";
        return value + "/v1/images/generations";
    }

    private String normalizeOpenAiSize(String raw) {
        int[] value = dimensions(raw);
        return value[0] + "x" + value[1];
    }

    private int[] dimensions(String raw) {
        String value = raw == null ? "" : raw.toLowerCase(Locale.ROOT).replace('*', 'x');
        return switch (value) {
            case "1024x1536", "3:4" -> new int[]{1024, 1536};
            case "1536x1024", "4:3" -> new int[]{1536, 1024};
            case "1024x1792", "9:16" -> new int[]{1024, 1792};
            case "1792x1024", "16:9" -> new int[]{1792, 1024};
            default -> new int[]{1024, 1024};
        };
    }

    private String stripDataPrefix(String encoded) {
        int comma = encoded.indexOf(',');
        return encoded.startsWith("data:") && comma >= 0 ? encoded.substring(comma + 1) : encoded;
    }

    private boolean looksLikeZip(byte[] payload) {
        return payload.length >= 4 && payload[0] == 'P' && payload[1] == 'K';
    }

    private String safeError(byte[] payload) {
        String text = new String(payload, java.nio.charset.StandardCharsets.UTF_8).trim();
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first.trim();
    }

    public record ProviderConfig(
            BotConfig.ImageGenerationProvider provider,
            String apiKey,
            String endpoint,
            String model,
            String negativePrompt) {
        static ProviderConfig hermes() {
            return new ProviderConfig(BotConfig.ImageGenerationProvider.HERMES, null, null, null, null);
        }
    }

    public record GeneratedImage(byte[] bytes, String mimeType) {
    }
}
