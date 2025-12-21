package com.anthem.pagw.parser;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
    "com.anthem.pagw.parser",
    "com.anthem.pagw.core"
})
public class RequestParserApplication {
    public static void main(String[] args) {
        SpringApplication.run(RequestParserApplication.class, args);
    }
}
