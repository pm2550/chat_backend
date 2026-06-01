package com.chatapp.controller;

import com.chatapp.dto.UrlPreviewDto;
import com.chatapp.service.UrlPreviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UrlPreviewControllerTest {

    @Mock
    private UrlPreviewService urlPreviewService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new UrlPreviewController(urlPreviewService))
                .build();
    }

    @Test
    void previewReturnsFetchedMetadata() throws Exception {
        when(urlPreviewService.fetch("https://example.com/post"))
                .thenReturn(new UrlPreviewDto(
                        "https://example.com/post",
                        "Example",
                        "Description",
                        "https://example.com/cover.png",
                        "example.com",
                        "https://example.com/favicon.ico"));

        mockMvc.perform(post("/api/v1/url-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "url", "https://example.com/post"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Example"))
                .andExpect(jsonPath("$.data.imageUrl").value("https://example.com/cover.png"))
                .andExpect(jsonPath("$.data.faviconUrl").value("https://example.com/favicon.ico"));

        verify(urlPreviewService).fetch("https://example.com/post");
    }

    @Test
    void previewRejectsBlankUrl() throws Exception {
        mockMvc.perform(post("/api/v1/url-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("url", " "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("url 不能为空"));

        verifyNoInteractions(urlPreviewService);
    }

    @Test
    void previewReturnsBadRequestForFetchErrors() throws Exception {
        when(urlPreviewService.fetch("https://internal.example"))
                .thenThrow(new IllegalArgumentException("不允许预览内网链接"));

        mockMvc.perform(post("/api/v1/url-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "url", "https://internal.example"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("不允许预览内网链接"));
    }
}
