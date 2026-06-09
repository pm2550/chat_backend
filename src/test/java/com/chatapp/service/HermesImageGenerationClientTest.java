package com.chatapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HermesImageGenerationClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void submitPostsHermesDrawPayloadAndReadsReturnedImagePath() throws Exception {
        Path imageRoot = Files.createTempDirectory("hermes-images");
        Path generated = imageRoot.resolve("out.png");
        byte[] pngBytes = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47};
        Files.write(generated, pngBytes);

        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/draw", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = objectMapper.writeValueAsBytes(java.util.Map.of(
                    "image_path", generated.toString(),
                    "provider", "xai"
            ));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        HermesImageGenerationClient client = clientFor(imageRoot, "/draw");

        ImageGenerationClient.SubmitResult submit = client.submit(
                "",
                "蓝色机器人",
                1,
                "1024*1024",
                false);
        ImageGenerationClient.PollResult poll = client.poll("", submit.taskId());

        assertThat(requestBody.get()).contains("\"prompt\":\"蓝色机器人\"");
        assertThat(requestBody.get()).contains("\"ratio\":\"1:1\"");
        assertThat(requestBody.get()).contains("\"expand\":false");
        assertThat(poll.status()).isEqualTo(ImageGenerationClient.PollResult.Status.SUCCEEDED);
        assertThat(client.download(poll.imageUrl())).isEqualTo(pngBytes);
    }

    @Test
    void downloadRejectsPathsOutsideHermesImageRoot() throws Exception {
        Path imageRoot = Files.createTempDirectory("hermes-images");
        Path outside = Files.createTempFile("outside-hermes", ".png");
        Files.write(outside, new byte[]{1, 2, 3});
        HermesImageGenerationClient client = clientFor(imageRoot, "/unused");

        assertThatThrownBy(() -> client.download(outside.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside allowed cache root");
    }

    private HermesImageGenerationClient clientFor(Path imageRoot, String path) {
        HermesImageGenerationClient client = new HermesImageGenerationClient(new ObjectMapper());
        int port = server == null ? 1 : server.getAddress().getPort();
        ReflectionTestUtils.setField(client, "drawUrl", "http://127.0.0.1:" + port + path);
        ReflectionTestUtils.setField(client, "imageRoot", imageRoot.toString());
        ReflectionTestUtils.setField(client, "timeoutSeconds", 5L);
        return client;
    }
}
