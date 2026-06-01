package com.chatapp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "agent.gateway")
@Data
public class AgentGatewayProperties {

    private boolean enabled = false;
    private String provider = "generic";
    private String baseUrl = "";
    private String executePath = "/api/v1/agent/tasks";
    private String healthPath = "/actuator/health";
    private String apiKey = "";
    private String agentId = "";
    private String sessionKeyPrefix = "chatapp";
    private int taskTimeoutSeconds = 600;
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 300000;

    public boolean isConfigured() {
        return enabled && baseUrl != null && !baseUrl.isBlank();
    }
}
