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
        assertThat(parameters.path("width").asInt()).isEqualTo(720);
        assertThat(parameters.path("height").asInt()).isEqualTo(1280);
        assertThat(parameters.path("batchSize").asInt()).isEqualTo(1);
        // 500 = 会员 Turbo；不传或 1000 会被当成高优先级，每张多扣 1000 额度。
        assertThat(parameters.path("priority").asInt()).isEqualTo(500);
        assertThat(parameters.path("inferenceProfile").asText()).isEqualTo("standard");
        assertThat(parameters.path("promptHelper").path("enable").asBoolean()).isTrue();

        // "快出图"（不扩写）：关掉 PixAI 的自动扩写。
        client.submit("", "cat", 1, "1024*1024", false);
        JsonNode second = body(requests.get(1)).path("parameters");
        assertThat(second.path("promptHelper").path("enable").asBoolean()).isFalse();
        assertThat(second.path("width").asInt()).isEqualTo(1024);
        assertThat(second.path("height").asInt()).isEqualTo(1024);
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
        assertThat(done.imageUrl()).isEqualTo("https://cdn.example/a.webp");

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
