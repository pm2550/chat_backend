package com.chatapp.service;

import com.chatapp.dto.UrlPreviewDto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlPreviewServiceTest {

    private final UrlPreviewService service = new UrlPreviewService();

    @Test
    void parseHtmlPrefersOpenGraphMetadataAndResolvesRelativeImages() {
        String html = """
                <html>
                  <head>
                    <meta property="og:title" content="PM chat &amp; Links">
                    <meta property="og:description" content="A compact preview card">
                    <meta property="og:image" content="/cover.png">
                    <meta property="og:site_name" content="PM Docs">
                    <link rel="shortcut icon" href="../favicon.svg">
                  </head>
                </html>
                """;

        UrlPreviewDto preview = service.parseHtml("https://docs.pm2550.com/a/b", html);

        assertEquals("https://docs.pm2550.com/a/b", preview.getUrl());
        assertEquals("PM chat & Links", preview.getTitle());
        assertEquals("A compact preview card", preview.getDescription());
        assertEquals("https://docs.pm2550.com/cover.png", preview.getImageUrl());
        assertEquals("PM Docs", preview.getSiteName());
        assertEquals("https://docs.pm2550.com/favicon.svg", preview.getFaviconUrl());
    }

    @Test
    void parseHtmlFallsBackToTitleAndHost() {
        String html = """
                <html>
                  <head><title>Example Domain</title></head>
                  <body>hello</body>
                </html>
                """;

        UrlPreviewDto preview = service.parseHtml("https://example.com/page", html);

        assertEquals("Example Domain", preview.getTitle());
        assertEquals("example.com", preview.getSiteName());
    }

    @Test
    void fetchRejectsNonHttpsSchemesBeforeNetworkAccess() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.fetch("file:///etc/passwd"));

        assertEquals("仅支持 https 链接", error.getMessage());
    }

    @Test
    void fetchRejectsPlainHttpBeforeNetworkAccess() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.fetch("http://example.com"));

        assertEquals("仅支持 https 链接", error.getMessage());
    }
}
