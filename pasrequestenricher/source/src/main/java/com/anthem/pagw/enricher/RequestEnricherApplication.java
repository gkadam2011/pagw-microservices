package com.anthem.pagw.enricher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
    "com.anthem.pagw.enricher",
    "com.anthem.pagw.core"
})
public class RequestEnricherApplication {
    public static void main(String[] args) {
        SpringApplication.run(RequestEnricherApplication.class, args);
    }
}
