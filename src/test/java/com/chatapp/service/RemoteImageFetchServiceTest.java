package com.chatapp.service;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteImageFetchServiceTest {

    private final RemoteImageFetchService service = new RemoteImageFetchService();

    @Test
    void fetchRejectsNonHttpsSchemesBeforeNetworkAccess() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.fetch("file:///etc/passwd"));

        assertEquals("仅支持 https 图片地址", error.getMessage());
    }

    @Test
    void fetchRejectsPlainHttpImages() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.fetch("http://example.com/a.png"));

        assertEquals("仅支持 https 图片地址", error.getMessage());
    }

    @Test
    void fetchRejectsBlankUrl() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.fetch("   "));

        assertEquals("图片地址不能为空", error.getMessage());
    }

    @Test
    void fetchRejectsLoopbackAndPrivateHosts() {
        assertEquals(
                "不允许抓取内网图片",
                assertThrows(IllegalArgumentException.class,
                        () -> service.fetch("https://127.0.0.1/a.png")).getMessage());
        assertEquals(
                "不允许抓取内网图片",
                assertThrows(IllegalArgumentException.class,
                        () -> service.fetch("https://192.168.1.10/a.png")).getMessage());
        assertEquals(
                "不允许抓取内网图片",
                assertThrows(IllegalArgumentException.class,
                        () -> service.fetch("https://10.0.0.5/a.png")).getMessage());
    }

    @Test
    void fileNameKeepsRemoteBaseNameWhenItHasAnExtension() {
        assertEquals(
                "photo.jpg",
                service.fileNameFor(URI.create("https://cdn.example.com/a/b/photo.jpg"), "image/jpeg"));
    }

    @Test
    void fileNameFallsBackToTimestampWhenRemotePathHasNoExtension() {
        String name = service.fileNameFor(URI.create("https://cdn.example.com/image"), "image/webp");

        assertTrue(name.startsWith("paste_"), name);
        assertTrue(name.endsWith(".webp"), name);
    }

    @Test
    void fileNameStripsUnsafeCharacters() {
        String name = service.fileNameFor(
                URI.create("https://cdn.example.com/%2e%2e/..%2Fevil%20name.png"), "image/png");

        assertTrue(name.matches("[A-Za-z0-9._-]+"), name);
        assertTrue(name.endsWith(".png"), name);
    }

    @Test
    void fetchRejectsMalformedUrl() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.fetch("https://"));

        assertEquals("图片地址格式无效", error.getMessage());
    }

    @Test
    void extensionMapsCommonImageTypes() {
        assertEquals("jpg", service.extensionFor("image/jpeg"));
        assertEquals("gif", service.extensionFor("image/gif"));
        assertEquals("webp", service.extensionFor("image/webp"));
        assertEquals("svg", service.extensionFor("image/svg+xml"));
        assertEquals("png", service.extensionFor("image/unknown-format"));
    }
}
