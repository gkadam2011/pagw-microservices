package com.anthem.pagw.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@SpringBootApplication
@EnableAsync
@ComponentScan(basePackages = {
    "com.anthem.pagw.orchestrator",
    "com.anthem.pagw.core"
})
public class OrchestratorApplication {

    public static void main(String[] args) throws InterruptedException {
        ConfigurableApplicationContext context = SpringApplication.run(OrchestratorApplication.class, args);
        
        // Debug: Check if context is running
        System.out.println("=== Application Context Status ===");
        System.out.println("Context is active: " + context.isActive());
        System.out.println("Context is running: " + context.isRunning());
        System.out.println("Application started on port 8080. Press Ctrl+C to stop.");
        
        // Just let Spring Boot handle keeping the JVM alive (remove our manual blocking)
    }
    
    /**
     * RestTemplate for synchronous service calls.
     * Configured with short timeouts to meet 15-second Da Vinci PAS requirement.
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(2))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }
}
