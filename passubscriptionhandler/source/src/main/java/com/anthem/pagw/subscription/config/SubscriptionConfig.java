package com.anthem.pagw.subscription.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Configuration for subscription handler components.
 */
@Configuration
public class SubscriptionConfig {

    /**
     * WebClient for making outbound webhook calls to subscribers.
     */
    @Bean
    public WebClient webhookClient() {
        return WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }
}
