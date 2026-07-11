package com.chatapp.service;

import com.chatapp.entity.BotConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotImageGenerationClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void openAiCompatibleUsesBotEndpointAndDecodesBase64Image() throws Exception {
        byte[] expected = new byte[]{1, 2, 3, 4};
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = ("{\"data\":[{\"b64_json\":\""
                    + Base64.getEncoder().encodeToString(expected) + "\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        BotImageGenerationClient client = client();
        var result = client.generate(
                new BotImageGenerationClient.ProviderConfig(
                        BotConfig.ImageGenerationProvider.OPENAI_COMPATIBLE,
                        "image-secret",
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        "flux-image",
                        null),
                "a blue city",
                "1536*1024");

        assertThat(result.bytes()).isEqualTo(expected);
        assertThat(result.mimeType()).isEqualTo("image/png");
        assertThat(authorization.get()).isEqualTo("Bearer image-secret");
        assertThat(requestBody.get()).contains("\"model\":\"flux-image\"")
                .contains("\"size\":\"1536x1024\"")
                .contains("\"prompt\":\"a blue city\"");
    }

    @Test
    void novelAiUsesOfficialShapeAndExtractsFirstImageFromZip() throws Exception {
        byte[] expected = new byte[]{9, 8, 7};
        byte[] zip = zip("image_0.png", expected);
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ai/generate-image", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.sendResponseHeaders(201, zip.length);
            exchange.getResponseBody().write(zip);
            exchange.close();
        });
        server.start();

        BotImageGenerationClient client = client();
        var result = client.generate(
                new BotImageGenerationClient.ProviderConfig(
                        BotConfig.ImageGenerationProvider.NOVELAI,
                        "nai-secret",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/ai/generate-image",
                        "nai-diffusion-3",
                        "bad hands"),
                "1girl, city lights",
                "1024*1536");

        assertThat(result.bytes()).isEqualTo(expected);
        assertThat(result.mimeType()).isEqualTo("image/png");
        assertThat(requestBody.get()).contains("\"input\":\"1girl, city lights\"")
                .contains("\"model\":\"nai-diffusion-3\"")
                .contains("\"width\":1024")
                .contains("\"height\":1536")
                .contains("\"uc\":\"bad hands\"");
    }

    private BotImageGenerationClient client() {
        ProviderCredentialService credentials = mock(ProviderCredentialService.class);
        OutboundUrlPolicy policy = mock(OutboundUrlPolicy.class);
        when(policy.assertAllowed(anyString(), any())).thenAnswer(invocation -> URI.create(invocation.getArgument(0)));
        return new BotImageGenerationClient(new ObjectMapper(), credentials, policy);
    }

    private byte[] zip(String name, byte[] bytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(bytes);
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
