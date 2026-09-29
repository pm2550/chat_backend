package com.chatapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PixaiImageGenerationClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Request> requests = new ArrayList<>();
    private Function<Request, Response> responder;
    private PixaiImageGenerationClient client;

    @BeforeEach
    void setUp() {
        Interceptor fake = chain -> {
            requests.add(chain.request());
            return responder.apply(chain.request());
        };
        client = new PixaiImageGenerationClient(objectMapper,
                new OkHttpClient.Builder().addInterceptor(fake).build());
        client.configure("sk-test", "https://api.pixai.art", "1983308862240288769", "standard", 500);
    }

    @Test
    void submitUsesTurboPriorityProfileSizeAndPromptHelper() throws Exception {
        responder = request -> json(request, 200, "{\"id\":\"task-1\",\"status\":\"waiting\"}");

        assertThat(client.submit("", "一只橘猫", 1, "1024*1792", true).taskId()).isEqualTo("task-1");
        Request sent = requests.get(0);
        assertThat(sent.url().toString()).isEqualTo("https://api.pixai.art/v1/task");
        assertThat(sent.header("Authorization")).isEqualTo("Bearer sk-test");
        JsonNode parameters = body(sent).path("parameters");
        assertThat(parameters.path("modelId").asText()).isEqualTo("1983308862240288769");
        assertThat(parameters.path("prompts").asText()).isEqualTo("一只橘猫");
        assertThat(parameters.path("width").asInt()).isEqualTo(1072);
        assertThat(parameters.path("height").asInt()).isEqualTo(1904);
        assertThat(parameters.path("batchSize").asInt()).isEqualTo(1);
        // 500 = 会员 Turbo；不传或 1000 会被当成高优先级，每张多扣 1000 额度。
        assertThat(parameters.path("priority").asInt()).isEqualTo(500);
        assertThat(parameters.path("inferenceProfile").asText()).isEqualTo("standard");
        assertThat(parameters.path("promptHelper").path("enable").asBoolean()).isTrue();
        assertThat(parameters.path("promptHelper").path("creativity").asText()).isEqualTo("medium");

        // 老的开关：不扩写 = 关掉 PixAI 的自动扩写。
        client.submit("", "cat", 1, "1024*1024", false);
        JsonNode second = body(requests.get(1)).path("parameters");
        assertThat(second.path("promptHelper").path("enable").asBoolean()).isFalse();
        assertThat(second.path("width").asInt()).isEqualTo(1424);
        assertThat(second.path("height").asInt()).isEqualTo(1424);
    }

    @Test
    void promptHelperLevelsMapToPixaiCreativity() throws Exception {
        responder = request -> json(request, 200, "{\"id\":\"task-3\"}");

        client.submit("", "猫", 1, "1024*1024", "off");
        client.submit("", "猫", 1, "1024*1024", "low");
        client.submit("", "猫", 1, "1024*1024", "medium");

        JsonNode off = body(requests.get(0)).path("parameters").path("promptHelper");
        assertThat(off.path("enable").asBoolean()).isFalse();
        assertThat(off.has("creativity")).isFalse();
        JsonNode low = body(requests.get(1)).path("parameters").path("promptHelper");
        assertThat(low.path("enable").asBoolean()).isTrue();
        assertThat(low.path("creativity").asText()).isEqualTo("low");
        JsonNode medium = body(requests.get(2)).path("parameters").path("promptHelper");
        assertThat(medium.path("enable").asBoolean()).isTrue();
        assertThat(medium.path("creativity").asText()).isEqualTo("medium");

        // 默认渠道把档位原样转给 PixAI。
        HermesImageGenerationClient hermes = mock(HermesImageGenerationClient.class);
        new ConfiguredImageGenerationClient(hermes, client, "pixai").submit("", "猫", 1, "1024*1024", "low");
        assertThat(body(requests.get(3)).path("parameters").path("promptHelper").path("creativity").asText())
                .isEqualTo("low");
    }

    @Test
    void sizesUseXlResolutionInMultiplesOf16() {
        // 约 2MP 的 XL 档：实测和 1MP 扣的额度、耗时一样。
        assertThat(PixaiImageGenerationClient.dimensionsForSize("1024*1024")).containsExactly(1424, 1424);
        assertThat(PixaiImageGenerationClient.dimensionsForSize("1024*1792")).containsExactly(1072, 1904);
        assertThat(PixaiImageGenerationClient.dimensionsForSize("9:16")).containsExactly(1072, 1904);
        assertThat(PixaiImageGenerationClient.dimensionsForSize("1792*1024")).containsExactly(1904, 1072);
        assertThat(PixaiImageGenerationClient.dimensionsForSize("16:9")).containsExactly(1904, 1072);
        assertThat(PixaiImageGenerationClient.dimensionsForSize("1024*1365")).containsExactly(1232, 1648);
        assertThat(PixaiImageGenerationClient.dimensionsForSize("3:4")).containsExactly(1232, 1648);
        assertThat(PixaiImageGenerationClient.dimensionsForSize("1365*1024")).containsExactly(1648, 1232);
        assertThat(PixaiImageGenerationClient.dimensionsForSize("4:3")).containsExactly(1648, 1232);
        assertThat(PixaiImageGenerationClient.dimensionsForSize("2:3")).containsExactly(1168, 1744);
        assertThat(PixaiImageGenerationClient.dimensionsForSize("3:2")).containsExactly(1744, 1168);
        assertThat(PixaiImageGenerationClient.dimensionsForSize(null)).containsExactly(1424, 1424);
        for (String size : new String[]{"1:1", "9:16", "16:9", "3:4", "4:3", "2:3", "3:2"}) {
            int[] dims = PixaiImageGenerationClient.dimensionsForSize(size);
            assertThat(dims[0] % 16).isZero();
            assertThat(dims[1] % 16).isZero();
            assertThat((long) dims[0] * dims[1]).isBetween(1_900_000L, 2_100_000L);
        }
    }

    @Test
    void everyTaskCarriesTheQualityOnlyNegativePromptUnlessBlank() throws Exception {
        responder = request -> json(request, 200, "{\"id\":\"task-4\"}");

        client.submit("", "猫", 1, "1024*1024", "medium");
        assertThat(body(requests.get(0)).path("parameters").path("negativePrompts").asText())
                .isEqualTo("lowres, worst quality, low quality, blurry, jpeg artifacts, bad anatomy, bad hands, "
                        + "extra fingers, missing fingers, fused fingers, deformed, overexposed, underexposed");

        client.configureNegativePrompt(" ");
        client.submit("", "猫", 1, "1024*1024", "medium");
        assertThat(body(requests.get(1)).path("parameters").has("negativePrompts")).isFalse();
    }

    @Test
    void blankProfileLetsTheModelPickItsOwn() throws Exception {
        // Tsubaki.3 Flash 只有自己的 flash 档：档位留空，由 PixAI 按模型选。
        client.configure("sk-test", "https://api.pixai.art", "2050048243034896798", " ", 500);
        responder = request -> json(request, 200, "{\"id\":\"task-2\"}");

        client.submit("", "cat", 1, "1024*1024", true);

        JsonNode parameters = body(requests.get(0)).path("parameters");
        assertThat(parameters.path("modelId").asText()).isEqualTo("2050048243034896798");
        assertThat(parameters.has("inferenceProfile")).isFalse();
    }

    @Test
    void pollMapsTaskStatus() {
        responder = request -> json(request, 200, "{\"status\":\"running\"}");
        assertThat(client.poll("", "t").status()).isEqualTo(ImageGenerationClient.PollResult.Status.RUNNING);
        assertThat(requests.get(0).url().toString()).isEqualTo("https://api.pixai.art/v1/task/t");

        responder = request -> json(request, 200,
                "{\"status\":\"completed\",\"outputs\":{\"mediaIds\":[\"m\"],\"mediaUrls\":[null,\"https://cdn.example/a.webp\"]}}");
        ImageGenerationClient.PollResult done = client.poll("", "t");
        assertThat(done.status()).isEqualTo(ImageGenerationClient.PollResult.Status.SUCCEEDED);
        // 用媒体 ID 取图：任务里的 mediaUrls 是几分钟就删的临时文件（隔一会儿补发会 403）。
        assertThat(done.imageUrl()).isEqualTo("https://api.pixai.art/v1/media/m/image");

        responder = request -> json(request, 200,
                "{\"status\":\"completed\",\"outputs\":{\"mediaUrls\":[\"https://cdn.example/a.webp\"]}}");
        assertThat(client.poll("", "t").imageUrl()).isEqualTo("https://cdn.example/a.webp");

        responder = request -> json(request, 200, "{\"status\":\"failed\"}");
        assertThat(client.poll("", "t").status()).isEqualTo(ImageGenerationClient.PollResult.Status.FAILED);
    }

    @Test
    void errorsExplainTheCauseWithoutLeakingTheKey() {
        responder = request -> json(request, 401, "");
        assertThatThrownBy(() -> client.submit("", "cat", 1, "1024*1024", true))
                .hasMessageContaining("HTTP 401")
                .hasMessageContaining("API key 无效")
                .hasMessageNotContaining("sk-test");
    }

    @Test
    void downloadOnlyFollowsHttpsAndMissingKeyIsRefused() {
        assertThatThrownBy(() -> client.download("http://169.254.169.254/latest"))
                .hasMessageContaining("地址无效");
        responder = request -> new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(ResponseBody.create(new byte[]{1, 2, 3}, MediaType.parse("image/webp"))).build();
        assertThat(client.download("https://cdn.example/a.webp")).containsExactly(1, 2, 3);
        assertThat(requests.get(requests.size() - 1).header("Authorization")).isNull();
        // 自家媒体接口要带 key。
        assertThat(client.download("https://api.pixai.art/v1/media/m/image")).containsExactly(1, 2, 3);
        assertThat(requests.get(requests.size() - 1).header("Authorization")).isEqualTo("Bearer sk-test");

        client.configure("", "https://api.pixai.art", "x", "standard", 500);
        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.submit("", "cat", 1, "1024*1024", true))
                .hasMessageContaining("未配置");
    }

    @Test
    void configuredClientRoutesToPixaiOnlyWhenSelected() {
        HermesImageGenerationClient hermes = mock(HermesImageGenerationClient.class);
        assertThat(new ConfiguredImageGenerationClient(hermes, client, "pixai").active()).isSameAs(client);
        assertThat(new ConfiguredImageGenerationClient(hermes, client, " PixAI ").active()).isSameAs(client);
        assertThat(new ConfiguredImageGenerationClient(hermes, client, "hermes").active()).isSameAs(hermes);
        assertThat(new ConfiguredImageGenerationClient(hermes, client, null).active()).isSameAs(hermes);
    }

    private JsonNode body(Request request) throws Exception {
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        return objectMapper.readTree(buffer.readUtf8());
    }

    private Response json(Request request, int code, String payload) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("x")
                .body(ResponseBody.create(payload, MediaType.parse("application/json"))).build();
    }
}
