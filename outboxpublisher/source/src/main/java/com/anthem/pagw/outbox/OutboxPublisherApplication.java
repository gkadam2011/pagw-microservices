package com.anthem.pagw.outbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OutboxPublisherApplication {

    public static void main(String[] args) {
        SpringApplication.run(OutboxPublisherApplication.class, args);
    }
}
