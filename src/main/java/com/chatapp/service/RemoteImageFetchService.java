package com.chatapp.service;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;

/**
 * 代客户端抓取远程图片。
 *
 * 浏览器粘贴网页图片时，剪贴板里常常只有一段 {@code <img src="https://第三方站/...">}，
 * 前端 fetch 会被 CORS 挡住，只能由服务端取回。
 */
@Service
@Slf4j
public class RemoteImageFetchService {
    private static final long MAX_IMAGE_BYTES = 15L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(4))
            .readTimeout(Duration.ofSeconds(8))
            .callTimeout(Duration.ofSeconds(20))
            .followRedirects(false)
            .build();

    public record FetchedImage(byte[] bytes, String contentType, String fileName) {
    }

    public FetchedImage fetch(String rawUrl) {
        URI uri = validateHttpsUrl(rawUrl);
        return fetch(uri, 0);
    }

    private FetchedImage fetch(URI uri, int redirectCount) {
        validatePublicHost(uri);

        Request request = new Request.Builder()
                .url(uri.toString())
                .header("User-Agent", "PM-chat-image-fetch/1.0")
                .header("Accept", "image/*")
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (isRedirect(response.code())) {
                if (redirectCount >= MAX_REDIRECTS) {
                    throw new IllegalArgumentException("图片跳转次数过多");
                }
                String location = response.header("Location");
                if (location == null || location.isBlank()) {
                    throw new IllegalArgumentException("图片跳转地址无效");
                }
                return fetch(validateHttpsUrl(uri.resolve(location).toString()), redirectCount + 1);
            }
            if (!response.isSuccessful()) {
                throw new IllegalArgumentException("图片地址返回 " + response.code());
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new IllegalArgumentException("图片内容为空");
            }
            String contentType = normalizeContentType(body.contentType() == null
                    ? response.header("Content-Type")
                    : body.contentType().toString());
            if (!contentType.startsWith("image/")) {
                throw new IllegalArgumentException("链接不是图片：" + contentType);
            }
            if (body.contentLength() > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("图片超过 15MB");
            }

            byte[] bytes = readLimited(body);
            return new FetchedImage(bytes, contentType, fileNameFor(uri, contentType));
        } catch (IOException e) {
            throw new IllegalArgumentException("抓取图片失败：" + e.getMessage());
        }
    }

    private byte[] readLimited(ResponseBody body) throws IOException {
        try (InputStream input = body.byteStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_IMAGE_BYTES) {
                    throw new IllegalArgumentException("图片超过 15MB");
                }
                output.write(buffer, 0, read);
            }
            if (total == 0) {
                throw new IllegalArgumentException("图片内容为空");
            }
            return output.toByteArray();
        }
    }

    private String normalizeContentType(String rawContentType) {
        if (rawContentType == null || rawContentType.isBlank()) {
            return "application/octet-stream";
        }
        return rawContentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
    }

    String fileNameFor(URI uri, String contentType) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        int slash = path.lastIndexOf('/');
        String candidate = slash >= 0 && slash + 1 < path.length()
                ? path.substring(slash + 1)
                : "";
        candidate = candidate.replaceAll("[^A-Za-z0-9._-]", "");
        if (candidate.length() > 80) {
            candidate = candidate.substring(candidate.length() - 80);
        }
        if (candidate.contains(".")) {
            return candidate;
        }
        return "paste_" + System.currentTimeMillis() + "." + extensionFor(contentType);
    }

    String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/bmp" -> "bmp";
            case "image/avif" -> "avif";
            case "image/heic" -> "heic";
            case "image/svg+xml" -> "svg";
            default -> "png";
        };
    }

    private boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private URI validateHttpsUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("图片地址不能为空");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("图片地址格式无效");
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !scheme.equalsIgnoreCase("https")
                || uri.getHost() == null
                || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("仅支持 https 图片地址");
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
                throw new IllegalArgumentException("不允许抓取内网图片");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("图片地址主机无法解析");
        }
    }
}
