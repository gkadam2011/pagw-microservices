package com.anthem.pagw.connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
    "com.anthem.pagw.connector",
    "com.anthem.pagw.core"
})
public class ApiConnectorApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiConnectorApplication.class, args);
    }
}
