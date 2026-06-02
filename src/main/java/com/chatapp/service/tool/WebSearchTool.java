package com.chatapp.service.tool;

import com.chatapp.service.BotRateLimitService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class WebSearchTool implements Tool {
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final BotRateLimitService rateLimitService;

    public WebSearchTool(ObjectMapper objectMapper,
                         @Value("${searxng.base-url:http://172.17.0.1:8888}") String baseUrl,
                         BotRateLimitService rateLimitService) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.rateLimitService = rateLimitService;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "Search the web through the self-hosted SearXNG instance.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("query").put("type", "string").put("minLength", 1);
        properties.putObject("max_results").put("type", "integer").put("minimum", 1).put("maximum", 10);
        schema.putArray("required").add("query");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params, ToolContext context) {
        String query = params.path("query").asText("").trim();
        if (query.isBlank()) {
            throw new ToolExecutionException("invalid_params", "query is required");
        }
        if (!rateLimitService.tryAcquireWebSearch(context.roomId())) {
            log.warn("web_search rate limit exceeded for roomId={}", context.roomId());
            return error("search_rate_limited", "web search rate limit exceeded for this room");
        }
        int maxResults = Math.max(1, Math.min(params.path("max_results").asInt(5), 10));
        try {
            HttpUrl searchUrl = HttpUrl.parse(baseUrl + "/search").newBuilder()
                    .addQueryParameter("q", query)
                    .addQueryParameter("format", "json")
                    .build();
            Request request = new Request.Builder().url(searchUrl).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return error("search_unavailable", "SearXNG returned HTTP " + response.code());
                }
                JsonNode responseJson = objectMapper.readTree(response.body().string());
                ObjectNode root = objectMapper.createObjectNode();
                root.put("query", query);
                ArrayNode results = root.putArray("results");
                int count = 0;
                for (JsonNode item : responseJson.path("results")) {
                    if (count >= maxResults) {
                        break;
                    }
                    ObjectNode result = results.addObject();
                    result.put("title", item.path("title").asText(""));
                    result.put("url", item.path("url").asText(""));
                    result.put("snippet", item.path("content").asText(item.path("snippet").asText("")));
                    count++;
                }
                return root;
            }
        } catch (IOException | IllegalArgumentException e) {
            log.warn("SearXNG search failed for query '{}': {}", query, e.getMessage());
            return error("search_unavailable", e.getMessage());
        }
    }

    private ObjectNode error(String code, String message) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode error = root.putObject("error");
        error.put("code", code);
        error.put("message", message != null ? message : "search unavailable");
        return root;
    }
}
