package com.chatapp.config;

import com.chatapp.service.AgentGatewayService;
import com.chatapp.service.AgentGatewayService.GatewayProbe;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("agentGateway")
@RequiredArgsConstructor
public class AgentGatewayHealthIndicator implements HealthIndicator {

    private final AgentGatewayService agentGatewayService;

    @Override
    public Health health() {
        GatewayProbe probe = agentGatewayService.probeHealth();
        Health.Builder builder = probe.up() ? Health.up() : Health.down();
        return builder
                .withDetail("enabled", probe.enabled())
                .withDetail("statusCode", probe.statusCode())
                .build();
    }
}
