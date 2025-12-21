package com.anthem.pagw.validator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
    "com.anthem.pagw.validator",
    "com.anthem.pagw.core"
})
public class BusinessValidatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(BusinessValidatorApplication.class, args);
    }
}
