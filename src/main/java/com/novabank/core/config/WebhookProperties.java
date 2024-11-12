package com.novabank.core.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.notifications.webhook")
@Getter
@Setter
public class WebhookProperties {
    private boolean enabled = false;
    private String url;
    private String apiKey;
    private long connectTimeoutMs = 2000;
    private long readTimeoutMs = 3000;
}
