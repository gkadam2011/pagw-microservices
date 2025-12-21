package com.anthem.pagw.response;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
    "com.anthem.pagw.response",
    "com.anthem.pagw.core"
})
public class ResponseBuilderApplication {
    public static void main(String[] args) {
        SpringApplication.run(ResponseBuilderApplication.class, args);
    }
}
