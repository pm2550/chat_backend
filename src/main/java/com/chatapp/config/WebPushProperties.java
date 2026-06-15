package com.chatapp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "web-push.vapid")
@Data
public class WebPushProperties {

    /**
     * Public VAPID key, URL-safe base64, safe to expose to browsers.
     */
    private String publicKey = "";

    /**
     * Private VAPID key, URL-safe base64. Must be supplied via env/secret in prod.
     */
    private String privateKey = "";

    /**
     * VAPID subject, usually a mailto: or https: contact.
     */
    private String subject = "mailto:admin@pm2550.com";

    public boolean isConfigured() {
        return hasText(publicKey) && hasText(privateKey) && hasText(subject);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
