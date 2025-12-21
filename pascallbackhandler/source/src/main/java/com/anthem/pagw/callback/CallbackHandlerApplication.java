package com.anthem.pagw.callback;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
    "com.anthem.pagw.callback",
    "com.anthem.pagw.core"
})
public class CallbackHandlerApplication {
    public static void main(String[] args) {
        SpringApplication.run(CallbackHandlerApplication.class, args);
    }
}
