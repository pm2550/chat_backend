package com.chatapp.service;

import com.chatapp.config.AgentGatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentGatewayServiceTest {

    @Test
    void extractResultAcceptsGatewayResponseShapes() throws Exception {
        AgentGatewayService service = new AgentGatewayService(new AgentGatewayProperties(), new ObjectMapper());

        assertThat(service.extractResult("{\"result\":\"direct\"}")).isEqualTo("direct");
        assertThat(service.extractResult("{\"data\":{\"output\":\"nested\"}}")).isEqualTo("nested");
        assertThat(service.extractResult(
                "{\"status\":\"ok\",\"result\":{\"payloads\":[{\"text\":\"first\"},{\"text\":\"second\"}]}}"))
                .isEqualTo("first\nsecond");
        assertThat(service.extractResult(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"openclaw reply\"}}]}"))
                .isEqualTo("openclaw reply");
        assertThat(service.extractResult("plain result")).isEqualTo("plain result");
        assertThatThrownBy(() -> service.extractResult(""))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("empty response");
    }

    @Test
    void disabledGatewayHealthIsUpButMarkedDisabled() {
        AgentGatewayService service = new AgentGatewayService(new AgentGatewayProperties(), new ObjectMapper());

        AgentGatewayService.GatewayProbe probe = service.probeHealth();

        assertThat(probe.enabled()).isFalse();
        assertThat(probe.up()).isTrue();
        assertThat(probe.detail()).isEqualTo("disabled");
    }

    @Test
    void openClawHttpHealthUrlIsDerivedFromWebSocketBaseUrl() {
        AgentGatewayProperties properties = new AgentGatewayProperties();
        properties.setBaseUrl("ws://gateway.example.test:18789/");
        AgentGatewayService service = new AgentGatewayService(properties, new ObjectMapper());

        assertThat(service.resolveOpenClawHttpUrl("/healthz"))
                .isEqualTo("http://gateway.example.test:18789/healthz");
    }
}
