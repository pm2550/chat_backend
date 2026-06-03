package com.chatapp.service;

import com.chatapp.dto.UrlPreviewDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class UrlPreviewService {
    private static final int MAX_HTML_BYTES = 1_048_576;
    private static final int MAX_REDIRECTS = 3;
    private static final Duration CACHE_TTL = Duration.ofDays(7);
    private static final String CACHE_PREFIX = "url-preview:";
    private static final Pattern META_TAG_PATTERN = Pattern.compile(
            "<meta\\s+[^>]*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LINK_TAG_PATTERN = Pattern.compile(
            "<link\\s+[^>]*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "<title[^>]*>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile(
            "([a-zA-Z_:.-]+)\\s*=\\s*(['\"])(.*?)\\2",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(4))
            .readTimeout(Duration.ofSeconds(5))
            .callTimeout(Duration.ofSeconds(7))
            .followRedirects(false)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    public UrlPreviewDto fetch(String rawUrl) {
        URI uri = validateHttpsUrl(rawUrl);
        String key = cacheKey(uri.toString());
        UrlPreviewDto cached = readCachedPreview(key);
        if (cached != null) {
            return cached;
        }

        UrlPreviewDto preview = fetchUncached(uri, 0);
        writeCachedPreview(key, preview);
        return preview;
    }

    private UrlPreviewDto fetchUncached(URI uri, int redirectCount) {
        validatePublicHost(uri);

        Request request = new Request.Builder()
                .url(uri.toString())
                .header("User-Agent", "PM-chat-link-preview/1.0")
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (isRedirect(response.code())) {
                if (redirectCount >= MAX_REDIRECTS) {
                    throw new IllegalArgumentException("链接重定向次数过多");
                }
                String location = response.header("Location");
                if (location == null || location.isBlank()) {
                    throw new IllegalArgumentException("链接重定向地址无效");
                }
                URI redirected = validateHttpsUrl(uri.resolve(location.trim()).toString());
                return fetchUncached(redirected, redirectCount + 1);
            }
            if (!response.isSuccessful()) {
                throw new IllegalArgumentException("链接预览抓取失败: HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IllegalArgumentException("链接预览响应为空");
            }
            String contentType = Optional.ofNullable(response.header("Content-Type")).orElse("");
            if (!contentType.isBlank()
                    && !contentType.toLowerCase(Locale.ROOT).contains("text/html")
                    && !contentType.toLowerCase(Locale.ROOT).contains("application/xhtml")) {
                throw new IllegalArgumentException("链接预览仅支持 HTML 页面");
            }
            String html = readLimitedHtml(body);
            return parseHtml(uri.toString(), html);
        } catch (IOException e) {
            log.warn("Failed to fetch URL preview for {}: {}", uri, e.getMessage());
            throw new IllegalArgumentException("链接预览抓取失败");
        }
    }

    UrlPreviewDto parseHtml(String url, String html) {
        String title = firstNonBlank(
                metaContent(html, "property", "og:title"),
                metaContent(html, "name", "twitter:title"),
                titleTag(html));
        String description = firstNonBlank(
                metaContent(html, "property", "og:description"),
                metaContent(html, "name", "description"),
                metaContent(html, "name", "twitter:description"));
        String image = firstNonBlank(
                metaContent(html, "property", "og:image"),
                metaContent(html, "name", "twitter:image"));
        String siteName = firstNonBlank(
                metaContent(html, "property", "og:site_name"),
                hostFromUrl(url));
        String canonicalUrl = firstNonBlank(metaContent(html, "property", "og:url"), url);
        String favicon = firstNonBlank(
                linkHref(html, "icon"),
                "/favicon.ico");

        return new UrlPreviewDto(
                normalizeUrl(url, canonicalUrl),
                truncate(clean(title), 160),
                truncate(clean(description), 260),
                normalizeUrl(url, image),
                truncate(clean(siteName), 80),
                normalizeUrl(url, favicon));
    }

    private URI validateHttpsUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("url 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("url 格式无效");
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !scheme.equalsIgnoreCase("https")
                || uri.getHost() == null
                || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("仅支持 https 链接");
        }
        return uri;
    }

    private void validatePublicHost(URI uri) {
        try {
            InetAddress address = InetAddress.getByName(uri.getHost());
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()) {
                throw new IllegalArgumentException("不允许预览内网链接");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("url 主机无法解析");
        }
    }

    private boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private String readLimitedHtml(ResponseBody body) throws IOException {
        long contentLength = body.contentLength();
        if (contentLength > MAX_HTML_BYTES) {
            throw new IllegalArgumentException("页面过大，无法生成预览");
        }
        Charset charset = Optional.ofNullable(body.contentType())
                .map(type -> type.charset(StandardCharsets.UTF_8))
                .orElse(StandardCharsets.UTF_8);

        try (InputStream input = body.byteStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_HTML_BYTES) {
                    throw new IllegalArgumentException("页面过大，无法生成预览");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(charset);
        }
    }

    private String metaContent(String html, String key, String expectedValue) {
        Matcher matcher = META_TAG_PATTERN.matcher(html);
        while (matcher.find()) {
            String tag = matcher.group();
            String marker = attribute(tag, key);
            if (expectedValue.equalsIgnoreCase(marker)) {
                return attribute(tag, "content");
            }
        }
        return null;
    }

    private String attribute(String tag, String name) {
        Matcher matcher = ATTRIBUTE_PATTERN.matcher(tag);
        while (matcher.find()) {
            if (name.equalsIgnoreCase(matcher.group(1))) {
                return matcher.group(3);
            }
        }
        return null;
    }

    private String linkHref(String html, String relNeedle) {
        Matcher matcher = LINK_TAG_PATTERN.matcher(html);
        while (matcher.find()) {
            String tag = matcher.group();
            String rel = attribute(tag, "rel");
            if (rel != null && rel.toLowerCase(Locale.ROOT).contains(relNeedle)) {
                return attribute(tag, "href");
            }
        }
        return null;
    }

    private String titleTag(String html) {
        Matcher matcher = TITLE_PATTERN.matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String normalizeUrl(String baseUrl, String maybeRelative) {
        String value = clean(maybeRelative);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return URI.create(baseUrl).resolve(value).toString();
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private String hostFromUrl(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = HtmlUtils.htmlUnescape(value)
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 1) + "…";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String cleaned = clean(value);
            if (cleaned != null) {
                return cleaned;
            }
        }
        return null;
    }

    private UrlPreviewDto readCachedPreview(String key) {
        if (redisTemplate == null) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, UrlPreviewDto.class);
        } catch (Exception e) {
            log.debug("URL preview cache read skipped: {}", e.getMessage());
            return null;
        }
    }

    private void writeCachedPreview(String key, UrlPreviewDto preview) {
        if (redisTemplate == null || preview == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(preview), CACHE_TTL);
        } catch (Exception e) {
            log.debug("URL preview cache write skipped: {}", e.getMessage());
        }
    }

    private String cacheKey(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(url.getBytes(StandardCharsets.UTF_8));
            return CACHE_PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return CACHE_PREFIX + Integer.toHexString(url.hashCode());
        }
    }
}
