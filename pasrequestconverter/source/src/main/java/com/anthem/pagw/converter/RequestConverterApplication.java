package com.anthem.pagw.converter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
    "com.anthem.pagw.converter",
    "com.anthem.pagw.core"
})
public class RequestConverterApplication {
    public static void main(String[] args) {
        SpringApplication.run(RequestConverterApplication.class, args);
    }
}
